static char *stdid_TRSQUEUE_10101 ="IDHFR TRSQUEUE_10101 D.1 20.12.2007";
static char *VERSION = "D.1 20.12.2007";

/* -*- c -*- */

/* Rewritten from SQR version, as Brio Technologies 'SQR' product is being
 * written out of TRS, in favour of standard languages.
 *
 * Modification History
 * 20.12.2007 Kill with signal 0 not reliable under linux, replace with waitpid with NoHang.
 * 13.11.2007 FAST SUBMIT now valid too for submit to pending
 * 20.07.2007 D.1 DJB Created from SQR version (Inspired by, and not translated from)   
 *
 *   Log files:
 *   $LOG/qct.$DB_NAME.log     things that are always logged (eg starts and stops, errors)
 *   $LOG/qct.$DB_NAME.errlog  output from activated tasks if not redirected into a report
 */  

 
EXEC SQL include sqlca;       /* Standard ingres connection libs */
EXEC SQL include 'SESSION'; 

EXEC SQL include 'TRSQUEUE_101.sh';

#include <stdio.h>          // fopen, flock
#include "db_functions.h"   // logon, db_error, catch_error
#include <sys/wait.h>       // to wait for process to finish, and REAP          
#include "trs_report.h"     // timed_message, operating_system_file tools
#include <search.h>         // lfind
#include <unistd.h>         // chdir, chown, getopt, exec ...
#include <stdlib.h>         // gmtime, strftime, localtime, ...
#include <string.h>         // strcmp, strcpy
#include <ctype.h>          // isalpha, isdigit, ...
#include <time.h>           // gmtime, difftime, strftime
#include <signal.h>         // kill

#include <fcntl.h>          // dup2, dup, open
#include <sys/stat.h>
#include <sys/types.h>

// Global and Module Static Variables 
char *db_name=0,
      qmc_file[100],failed_job_hook[SYSPARM_LEN]="\0", sqr_rewrite[SYSPARM_LEN]="\0"; 
int loops=0,          // =0 for infinite, Positive number for restricted loops.
    debug=0,          // Debug enables extra diagnostic messages 
    queue_running=1;  // When reset to false, the queue will stop on next loop.
EXEC SQL begin declare section;
#define QUEUE_PERIOD 60 /* period in seconds, DEV: can be as low as 5sec for development testing */
EXEC SQL end declare section;

// --------------------------------------------------------------------------------
struct launch { // expect 200 rows max 
  int entry_id;
  char queue_type;
  char command_line[251]; 
  int  entry_status;
};

#define launch_max 200
struct    launch launch_list[launch_max];
int       launch_count =0;

struct auto_resubmit {     // expect 200 rows max
  int entry_id;
  char auto_process_id[9]; //auto_process_id    varchar         8  null ok
};

#define auto_resubmit_max 200
int       auto_resubmit_count =0;
struct    auto_resubmit auto_resubmit_list[auto_resubmit_max];

#define printer_state_max 200
int       printer_state_count=0;
// expect 200 rows max
typedef struct { 
  char queue_id[13];          //queue_id        varchar        12   not null no default
  char printer_name[13];      //printer_id      varchar        12   null with default
} printer_state;

printer_state printer_state_list[printer_state_max];

// Function Prototypes
void opening_messages();
void debug_timed_message(char *msg);
void parse_args(int argc, char *argv[]);
void on_err_lockmode();
void exit_error( void );
void main_loop();
void reset_queue_entries();
void log_status();
int auto_duplicate(int old_entry_id, char *auto_process_id);

//int commit();
int lockmode(int table, char *newlevel);
int pending2active();
int submitted2pending();
int process_has_stopped(int pid);

int reap_processors();
int queue_processor(char *queue_id, int first_entry_id, char *first_command_line);
int wait_for_children_to_exit();

/* ****************************************************************************
 * is process finished
 *
 */
int process_has_stopped(int pid) {
  // 20Dec07 DJB Kill 
  //  return kill(pid, 0); /* Doesn't send a real kill, just checks that we 'could' */
  int wret = 0;
  return waitpid(pid, &wret, WNOHANG); /* Reap, wret is the process's exit status, ignore return value*/
}


/* ****************************************************************************
 * New classes for queue registers
 *
 */

/* ****************************************************************************
 * Internal storage of the queues, and their processor status'
 *
 */
#define MAX_QUEUES 50
typedef struct { // Max 50 'queues'
  char  queue_id[13];
  char  queue_type;
  pid_t qprocessor_pid;
} queue_memory;
 
unsigned int queue_count=0;
queue_memory queue_list[MAX_QUEUES];

int queue_memory_comparitor(const void *a, const void *b) {
  queue_memory *aq, *bq;
  aq = (queue_memory *)a;
  bq = (queue_memory *)b;
  return strcmp(aq->queue_id,bq->queue_id);
}

queue_memory *queue_memory_for_queue(char *queue) {
  queue_memory *qfind;
  queue_memory key;
  strcpy(key.queue_id,queue);
  
  qfind = (queue_memory *) lfind( &key, queue_list, &queue_count, sizeof(queue_memory),
					&queue_memory_comparitor);
  if(qfind) {
    return qfind;
  }
  return 0;
}

int pid_for_queue(char *queue) {
  queue_memory *qfind = queue_memory_for_queue(queue);
  if(qfind) {
    return qfind->qprocessor_pid;
  }
  return 0;
}

void stop_signal_handler(int signum) {
  signal(signum,SIG_DFL); // Revert signal to default handler
  queue_running =0;
  fprintf(stderr,"Queue manager was signalled to stop after the completion of this job\n");
}



/* ****************************************************************************
 *
 *      #     #    #      ###   #     #
 *      ##   ##   # #      #    ##    #
 *      # # # #  #   #     #    # #   #
 *      #  #  # #     #    #    #  #  #
 *      #     # #######    #    #   # #
 *      #     # #     #    #    #    ##
 *      #     # #     #   ###   #     #
 *       
 */
int main( int argc, char *argv[] ) 
{
  char tempstr[240];

  sprintf(tempstr, "Commenced qct.sc - Queue manager %s", VERSION );
  timed_message( stderr, tempstr );
    
  // Preconditions
  //////////////////////////////////////////////////////////////////////////////////
  db_name=getenv("DB_NAME");
  if( !db_name ) {
    timed_message( stderr, "Error database name required" );
    exit(1);
  }

  sprintf(qmc_file,"%s/%s.qctstop",getenv("TOP"),db_name); /* Non-standard, but effective for UAT */
  
  fprintf(stderr,"Starting queue manager for database %s.\n",db_name);

  parse_args(argc,argv);

  logon(0); // DB connect control session
  on_err_lockmode();
  if (lockmode(0,"nolock")) {
    exit_error();
  }
  opening_messages();
  debug_timed_message("QCT Start");

  signal(SIGINT,stop_signal_handler); // An interupt 'Ctrl-C' should be caught to notify kids
  EXEC SQL register dbevent qmqe_fast_event;
  if( catch_error("Error registering for event qmqe_fast_event.") || commit() ) {
    exit_error();
  }
  
  reset_queue_entries();
  main_loop();
  timed_message(stderr,"QCT end status");
}

/* ****************************************************************************
 * Parse intial command line
 *
 */
void parse_args(int argc, char *argv[]) {
  int  opt_flg=0;
  optarg = NULL;

  opt_flg = getopt(argc,argv,"d");
  if ( opt_flg=='d' ) {
    debug=1;
  }
}

/* ****************************************************************************
 * Timed messages during debugging only.
 *
 */
void debug_timed_message(char *msg) {
  if(debug) {
    timed_message(stderr,msg);
  }
}

/* ****************************************************************************
 * All the opening messages for the queue mgr MUST be in here.
 *
 */
void opening_messages()  {
  EXEC SQL begin declare section;
  char queue_id[13];
  char queue_type[2];
  int queue_status=0;
  EXEC SQL end declare section;
  queue_memory *new_row;

  fprintf(stderr,"This database contains the following 'batch' queues:\n");

  // Open the display with a quick listing of queue's and their respecitve states
  EXEC SQL 
    SELECT queue_id,  queue_type,  queue_status
    INTO  :queue_id, :queue_type, :queue_status
    FROM  queue
    ; 
  EXEC SQL begin;
  if( *queue_type == QT_BATCH) {
    fprintf(stderr,"%18.12s  %s\n",queue_id, (queue_status == Q_OPEN)?"Running":"On Hold");
  }
  strcpy(queue_list[queue_count].queue_id,queue_id);
  queue_list[queue_count].queue_type = *queue_type;
  queue_list[queue_count].qprocessor_pid=0;
  queue_count++;
  EXEC SQL end;

  if( catch_error( "finding results of a query" )) {
    exit_error();
  } else if (sqlca.sqlcode == 100) {
    commit();
    timed_message(stderr, "No entries in the queue table, exiting\n" );
    exit_error();
  }

  commit();
}

int lockmode(int table, char *newlevel){
#ifdef __ESQL_ONLY__ 
  EXEC SQL begin declare section; 
  char *newlevel;
  EXEC SQL end declare section;
#endif

  if (table) {
    EXEC SQL
      set lockmode on queue_entry where level = :newlevel
      ;
  } else {
    EXEC SQL
      set lockmode session where readlock = nolock
      ;
  }
  return catch_error("setting lockmode");

}

/* ****************************************************************************
 * Worker: set 'on error' and lockmode to this programs defaults.
 *
 */
void on_err_lockmode() {
  EXEC SQL 
    set session with on_error = rollback transaction
    ;
  if (catch_error("Disabling intra-transaction savepoints.")) {
    exit_error();
  }

}

/* ****************************************************************************
 * Exit with standard messages and clean up database connections
 *
 */
void exit_error() {
  EXEC SQL rollback;
  EXEC SQL disconnect all;
  timed_message( stderr,
		 "Completed QCT Queue Manager in error" );
  exit(1);
}

int reap_processors() {
  int queue_pid=0, i=0, wret=0, active=0;
  for(i=0;i<queue_count;i++) { // Pulse check, record zero for stopped processes.
    queue_pid = queue_list[i].qprocessor_pid;
    if(queue_pid) {
      if(process_has_stopped(queue_pid)) {
	// 20Dec07 DJB has stopped has already reaped if needed
        // waitpid(queue_pid, &wret, 0); /* Reap, wret is the process's exit status, ignore return value*/
        //fprintf(stderr,"%s Processor wait on %d\n", queue_list[i].queue_id, queue_pid);
        queue_list[i].qprocessor_pid=0; 
        fprintf(stderr,"%s queue has finished running jobs\n",queue_list[i].queue_id);
      } else {
        active++;
      }
    }
  }
  return active; //Count of active processors
}


int notify_children() { // Notify the queue_process children that the queues are stopping.
  int child_count=0;
  int queue_pid=0, i=0;
  fprintf(stderr,"Sending USR1 signal to children to stop on the next job\n");
  for(i=0;i<queue_count;i++) { // Pulse check, record zero for stopped processes.
    queue_pid = queue_list[i].qprocessor_pid;
    if(queue_pid && !process_has_stopped(queue_pid)) {
      fprintf(stderr,"Sending USR1 signal to %d\n",queue_pid);
      kill(queue_pid, SIGUSR1);
      child_count++;
    }
  }
  return child_count;
}

int wait_for_children_to_exit() {
  int retries=0, active_processors=0;
  while ( (retries++ < 100 ) && (active_processors=reap_processors()) ) {
    fprintf(stderr,"Waiting for %d processor to finish\n",active_processors);
    sleep(5);
  }
  return active_processors;
}
 
int update_queue_entry(int the_entry_id, int new_status) {
#ifdef __ESQL_ONLY__
  EXEC SQL begin declare section;
  int new_status, pid;
  int the_entry_id;
  EXEC SQL end declare section;
#endif
  
  EXEC SQL begin declare section;
  int pid = getpid();
  int row_count=0;
  EXEC SQL end declare section;

  if (new_status == QE_ACTIVE) {  // we're starting, so set 'start_time' & Pid
    EXEC SQL
      UPDATE queue_entry
      SET    entry_status = :new_status
        , lst_mod_date = date('now')
        , lst_mod_by   = dbmsinfo('username')
        , start_time   = date('now')
        , pid          = varchar(:pid)
      WHERE entry_id  = :the_entry_id
      ;
      
  } else if (new_status == QE_SUCCESS ||
             new_status == QE_ERROR ) { // we're finishing, so set 'end_time'
    EXEC SQL
      UPDATE queue_entry
      SET entry_status = :new_status
      , lst_mod_date = date('now')
      , lst_mod_by   = dbmsinfo('username')
      , end_time     = date('now')
      WHERE entry_id = :the_entry_id
      ;
      
  } else { // The new status doesn't effect start or end time.
    EXEC SQL
      UPDATE queue_entry
      SET entry_status = :new_status
        , lst_mod_date = date('now')
        , lst_mod_by   = dbmsinfo('username')
      WHERE entry_id  = :the_entry_id
      ;
  } 
  if (catch_error("Update queue_entry failed")){
    return -1;
  }
  
  EXEC SQL inquire_sql( :row_count = rowcount );
  
  if (row_count != 1) {
    timed_message(stderr,"Failed to set the entry status");
    return -1;
  }
  commit();
  return 0;
}


/*
 * Check for the control file which may indicate command to exit
 */
int check_control_file() {
  int retval=!access(qmc_file,R_OK) ; /* Check the existance of the 'control' file */
  return retval;
} // end check_control_file


void remove_control_file() {
  if (check_control_file() && unlink(qmc_file)) { /* error if unlink FAILED, and YES it can, even if you own the file */
    fprintf(stderr,"Unable to remove the control file %s\n",qmc_file);
    exit_error();
  }
}

/*
 *  Move submitted entries to pending when batch time has passed
 *  Note that automatic processes need to be requeued immediately
 */
int submitted2pending() {
  int number_auto_resubmit=0;
EXEC SQL begin declare section;
  int qe_entry_id =0;
  char qe_auto_process_id[9]="\0", qe_batch_time[15]="\0";
  char now[20];
EXEC SQL end declare section;
  int i=0;

  // Date 'now' is selected so that both select's 'see' the same rows
  // Or one could be missed if the clock ticks over on the second that
  // the resub job is due.
  EXEC SQL 
   select date('now')
   into :now
  ;
  if(catch_error("Finding out when is now")) {
    return -1;
  }
  
  auto_resubmit_count = 0;
  // check for any automatic processes to be resubmitted
  EXEC SQL
    select entry_id,     auto_process_id    , batch_time
    into  :qe_entry_id, :qe_auto_process_id , :qe_batch_time
    from  queue_entry
    where ifnull(auto_process_id,'') != ''
    and   entry_status in(:QE_SUBMITTED,:QE_FASTSUBMIT)
    and   batch_time  <= date(:now)
    order by batch_time, entry_id
   ; 
  EXEC SQL begin;
    if (!auto_resubmit_count) {
        fprintf(stderr,"autoresubmit job list: ");
    } 
    fprintf(stderr,"%ld-%s, ",qe_entry_id, qe_auto_process_id);
    auto_resubmit_list[auto_resubmit_count].entry_id = qe_entry_id;
    strcpy(auto_resubmit_list[auto_resubmit_count++].auto_process_id, qe_auto_process_id);
    if (auto_resubmit_count >= auto_resubmit_max) {
      debug_timed_message("Autoresubmit list full"); 
      EXEC SQL endselect;
    }
  EXEC SQL end;
  
  if( catch_error("Selecting autoresubmit jobs")) {
    return -1;
  }
  commit(); // Ok to commit here, just not between the update and the bunch duplicate calls 

  if (auto_resubmit_count) {
    fprintf(stderr,"\n");
  } 
   
  //  now update submitted entries
// 13.11.2007 FAST SUBMIT now valid too.
  EXEC SQL
    UPDATE queue_entry
    SET    entry_status  = :QE_PENDING
    WHERE  entry_status  in(:QE_SUBMITTED,:QE_FASTSUBMIT)
    AND    batch_time   <= date(:now)
  ;
  
  if (catch_error("Unable to update from submitted to pending")) {
    return -1;  
  }

  // Can't commit() here, it allows the possibility of an autoprocess job to be missed

  // now duplicate automatic process entries as necessary 
  // the order is such that we may reduplicate (perhaps several times if submit 
  // interval is small and we haven't checked for a while), 
  // but only in the next cycle, and with all the current triggered
  // automatic processes safely moved to pending
  for(i = 0; i<auto_resubmit_count; i++) {
    if(auto_duplicate( auto_resubmit_list[i].entry_id, 
                       auto_resubmit_list[i].auto_process_id)) {
      return -1;
    } 
  }
  commit();
  return 0;
} /*  submitted2pending */

/*
 *  Reset Queue Entries
 *
 * TCN1539: "Flush" queue entries to a known state eg on start up.
 * There is not too much harm if a job is incorrectly considered
 * "dead" when infact still active, although the operators are
 * supposed to check these things.
 *  
 */
void reset_queue_entries() {
  EXEC SQL BEGIN DECLARE SECTION;
  int  row_count=0;
  EXEC SQL END DECLARE SECTION;

  debug_timed_message("reset_qe");
  
  EXEC SQL
    UPDATE queue_entry
      SET  entry_status = :QE_CANCELLED
    WHERE  entry_status = :QE_ACTIVE;

  if( catch_error("Updating from active to cancelled")) {
    exit_error();
  }
  
  EXEC SQL inquire_sql( :row_count = rowcount );
  
  if (row_count) {
    char msg[100];
    sprintf(msg,"%d entries reset.",row_count);
    timed_message(stderr,msg);
  }
  
  commit();
} // end reset_queue_entries  
 

/* ---------------------------------------------------------------
 * resubmit an automatic process using the periods stored 
 * in the automatic_process table
 *   
 */
int auto_duplicate(int old_entry_id, char *auto_process_id) { 
  EXEC SQL BEGIN DECLARE SECTION;
  int  ap_submit_frequency=-1;
  char ap_submit_frequency_units[11]="",frequency[20]="";
  char ap_default_batch_queue[13]="", new_batch_time[20]="\0";
  int new_entry_id=0;
  int  row_count=0;
  EXEC SQL END DECLARE SECTION;
  char msg[100]="\0";
  //    char  batch_queue[13]="\0";

#ifdef __ESQL_ONLY__
  EXEC SQL BEGIN DECLARE SECTION;
  char *auto_process_id;
  int old_entry_id;
  EXEC SQL END DECLARE SECTION;
#endif
  // Cannot commit during this procedure as we need the whole list of jobs to
  // rollback or complete, on a batch insert 'or bust'
  fprintf(stderr,"Checking id %ld for autoprocess %s - ",old_entry_id, auto_process_id);
  // FYI, auto_process_id is NOT a unique key in automatic_process
  //      duplicate rows ARE possible, just nonsense data. 
  EXEC SQL
    SELECT submit_frequency,      submit_frequency_units,     default_batch_queue
    INTO   :ap_submit_frequency, :ap_submit_frequency_units, :ap_default_batch_queue
    FROM   automatic_process
    WHERE  auto_process_id = :auto_process_id
    ;
  EXEC SQL begin;
    sprintf(frequency,"%d %s",ap_submit_frequency,ap_submit_frequency_units);
  EXEC SQL end;
  
  if( catch_error("Automatic process select failed")) {
    return -1;
  }

  if (ap_submit_frequency < 0) { //no corresponding entry in AP table found
    fprintf(stderr,"WARN: Autoprocess %s does not have a corresponding row!\n",auto_process_id);
    return 0;
  }
    
  if (ap_submit_frequency == 0 ) { // ! respawn (temporarily) disabled
    char msg[100]="\0";
    sprintf(msg, "I - Automatic Process Respawn: Cancelled for %d",auto_process_id);
    debug_timed_message(msg);
    return 0; 
  }
  
  new_entry_id = next_entry_id();  // now spawn next incarnation of automatic process
  if(new_entry_id<=0) {
    return -1;
  }
  
  fprintf(stderr,"New entry %ld at %s\n",new_entry_id, frequency);
  
//  sprintf(batch_queue,"""%s""",ap_default_batch_queue);

  EXEC SQL
    INSERT INTO queue_entry (queue_id, user_id, auto_process_id,
      paper_type, auto_print_result, print_time, print_queue_id,
      command_line, entry_priority,   description,
      entry_status, entry_id, batch_time)
    SELECT 
      :ap_default_batch_queue,     qe1.user_id, qe1.auto_process_id,
      qe1.paper_type,   qe1.auto_print_result, qe1.print_time, qe1.print_queue_id,
      qe1.command_line, qe1.entry_priority, description, 
      :QE_SUBMITTED,    :new_entry_id, qe1.batch_time + :frequency
    FROM  queue_entry qe1
    WHERE qe1.entry_id = :old_entry_id
  ;
  
  if ( catch_error("Inserting new queue_entry")) {
    return -1;
  }
  
  EXEC SQL inquire_sql( :row_count = rowcount );

  // fprintf(stderr,"Id %s count %d\n", auto_process_id, row_count);

  if ( row_count != 1) {
    sprintf(msg, "E - Automatic Process Respawn: failure on insert of %s", auto_process_id );
    timed_message(stderr,msg);
    return -1;
  }

  debug_timed_message("I - Automatic Process: Respawned Id "); 
  return 0;
} // end auto_duplicate 



/*
 *  Find entry with highest priority in queue_entry for each queue
 *  - a queue must be "open"
 *  - there must be no "active" entry already in the queue
 *  - for a particular queue this is a entry with the lowest
 *  entry_id of those with the lowest batch_time of those with pending status
 * - for "P" printer queues, only consider entries with the currently mounted
 * paper type
 */

int pending2active() {
  char last_queue[20] =""; /* use? */
  EXEC SQL BEGIN DECLARE SECTION;
  char q_queue_id[13]="";
  char q_queue_type[2]="";
  char command_line[251]="", batch_time[25]="";
  int job_count=0, entry_status=0;
  int entry_id=0;
  EXEC SQL END DECLARE SECTION;
  int new_pid=0,i=0;
  queue_memory *qfind =0; 
  commit();
  lockmode(1,"table");

  launch_count=0;
  reap_processors();
  
  EXEC SQL
    SELECT q1.queue_id, entry_status,  entry_id,  command_line,  batch_time
    INTO  :q_queue_id, :entry_status, :entry_id, :command_line, :batch_time
    FROM queue q1, queue_entry qe1
    WHERE q1.queue_status = :Q_OPEN
    AND q1.queue_id = qe1.queue_id
    AND   qe1.entry_status in (:QE_PENDING, :QE_ACTIVE)
    AND   (q1.queue_type <> 'P' or 
	         ifnull(qe1.paper_type, '') = '' or
	         ifnull(qe1.paper_type, q1.paper_type) = q1.paper_type )
   ORDER BY q1.queue_id, entry_status desc, batch_time, entry_id
   ;
  EXEC SQL begin;
    if(debug) {
      fprintf(stderr,"Queue %s on status %d entry %d for %25.25s,",
              q_queue_id, entry_status, entry_id, command_line);
    }
    qfind = queue_memory_for_queue(q_queue_id);
    new_pid=0;

    if( qfind->queue_type == QT_PRINTER) {
      launch_list[launch_count].entry_id     = entry_id;
      launch_list[launch_count].queue_type   = qfind->queue_type;
      strcpy(launch_list[launch_count].command_line,command_line); 
      launch_list[launch_count].entry_status = entry_status;
      launch_count++;
      if (launch_count>= launch_max) {
        fprintf(stderr,"ERROR, launch list exhausted\n");
        EXEC SQL endselect;
      }
    }

    if (!qfind->qprocessor_pid) {
      if(debug) {
        fprintf(stderr," and the entry needs a processor\n");
      }
      if( qfind->queue_type == QT_BATCH && (new_pid = fork()) < 0 ) { 
        fprintf(stderr,"Fork failed"); // Only batch queues need forking
        exit_error();
      }

      if (!new_pid) { 
        signal(SIGUSR1,stop_signal_handler);

        if(qfind->queue_type == QT_BATCH) {
        /* If you do a 'real' exit, it disconnects the parent, which is BAD */
         _exit(queue_processor(q_queue_id,entry_id,command_line));
        }
      }
      qfind->qprocessor_pid= new_pid;
    } else {
      if(debug) {
        fprintf(stderr," and processor is running\n");
      }
    }
  EXEC SQL end;
  if (catch_error("Selecting pending & active jobs")) {
	   return -1;
  }	

  commit();
  
  for( i = 0; i<launch_count;i++) {
    if (launch_list[i].entry_status == QE_PENDING &&
        launch_list[i].queue_type   == QT_PRINTER) {
     // Confirm list only has pending print jobs 
     job_processor(launch_list[i].entry_id, launch_list[i].command_line,0);
    }
  }

  commit();
  lockmode(1,"session");
} // end find_next_entries

//int commit() {
//  EXEC SQL 
//    commit;
//    
//  return (catch_error("Failed to commit transaction")); 
//}

/* ****************************************************************************
 * was the main from the fork concept
 */
void concept_main( int argc, char *argv[] ) {
  pid_t pid=0;
  int i=0, kret=0, eret=0,wret=0;
  int runloops=2;
  char time_msg_buf[80] = "";
  logon(0); /*if we used open_sessions() it would connect twice */
  
  sprintf(time_msg_buf, "Commenced %s", stdid_TRSQUEUE_10101);
  timed_message (stderr, time_msg_buf);

  while (runloops--) {
    if ( (pid = fork()) < 0 ) {
      printf ("Fork failed\n");
      exit(1);
    }

    printf("WE ARE %d\n", getpid());

    if (pid) { /* we are the parent */
      for(i=0;i<4;i++){
      	printf ("PARENT: child is %s loop %d\n", (process_has_stopped(pid)?"finished":"running"), i);
      	sleep(1);
      }

      waitpid(pid, &wret, 0); /* Reap, wret is the process's exit status, ignore return value*/
      printf("PARENT:wait returned %d, %d\n", pid, wret);
      EXEC SQL disconnect all;
    } else { /* we are the child */
      freopen ("myfile.txt", "a", stdout);
      printf("CHILD:I am the new job, so I'll do the work\n");
      fprintf(stderr, "CHILD:I am the new job, so I'll do the work\n");
	
      sleep(1); 
      printf("CHILD:I'm done, so exiting\n");
      fprintf(stderr, "CHILD:I'm done, so exiting\n");
      fclose (stdout);

      _exit(0); /* If you do a 'real' exit, it disconnects the parent */
    }
  } /* end while */

  sprintf(time_msg_buf, "Completed %s", stdid_TRSQUEUE_10101);
  timed_message (stderr, time_msg_buf);

  exit(0);
}

/* ****************************************************************************
 *
 * POSTX
 *
 */
void postx() { // TODO, remove if not used.
  int new_entry_id=0, entry_id = 0;
  debug_timed_message("QCT1 Start");
  timed_message(stderr,"postx end");
}

int queue_processor(char *queue_id, int first_entry_id,
                    char *first_command_line) {
EXEC SQL begin declare section;
  int entry_id = first_entry_id;
  char command_line[251],batch_time[25];
EXEC SQL end declare section;
  int loop_count=0, no_job_count=0;
  strcpy(command_line,first_command_line);
  
  fprintf(stderr,"%s Queue processor to connect to ingres for job selection\n",queue_id);
  logon(2); // Must log on, as we can't use the session from the parent process, Ingres WILL be confused.
  EXEC SQL set_ingres( session = 2 );
  on_err_lockmode();

  fprintf(stderr,"%s first job %ld-",queue_id,first_entry_id);
  while( ( loop_count++ < 100 && entry_id )|| (no_job_count < 5)) {
    if( !queue_running) {
      fprintf(stderr,"%s queue stoppping by request from main process\n",queue_id);
      break;
    }

    if(entry_id) {
      job_processor(entry_id, command_line);
    }
    
    entry_id = 0; // So a select with no rows will stop (and forget the last job)
    EXEC SQL 
      SELECT entry_id,  command_line,  batch_time
      INTO  :entry_id, :command_line, :batch_time
      FROM  queue q1 join queue_entry qe1
         ON q1.queue_id      = qe1.queue_id
      WHERE qe1.queue_id     = :queue_id
      AND   q1.queue_status  = :Q_OPEN
      AND   qe1.entry_status = :QE_PENDING
      ORDER BY batch_time, entry_id

    ;
    EXEC SQL begin;
      fprintf(stderr,"%s spawn %ld-",queue_id,entry_id);
      EXEC SQL endselect;
    EXEC SQL end;
    if (catch_error("Selecting next entry")) { 
      return -1;
    }
    commit(); // allow the main job to select the queue_entry table
    if (entry_id) {
      no_job_count=0;
    } else {
      // fprintf(stderr,"No job in %s, counter increasing from %d\n",queue_id,no_job_count);
      no_job_count++;
     	sleep(2); //Allow a little time between no job rechecks.
    }
  }

  EXEC SQL disconnect session 2;
  return 0;
}

void sqr_cmd_line_mod(char *command_line) {
  char for_replacement[251]="\0", pattern[5]=" -XL ";
  int starts_at=0, looking_at=0, cmd_len=strlen(command_line), upto=0, pattern_len=strlen(pattern), found=0;
  
  strcpy(for_replacement,command_line);
  fprintf(stderr,"%s:SQR -XL Check:",for_replacement);

  while (looking_at < cmd_len ) {
    if (for_replacement[looking_at] == pattern[upto]) { // Found the next pattern char
      //fprintf(stderr,"+");
      if (++upto == pattern_len) { // Found the whole pattern
        found =1;
        break;
      } 
    } else {
      looking_at -= upto;
      upto=0;
    }
    looking_at++;
  }

  if( found ) {
    strcpy(&command_line[looking_at-upto+1],&for_replacement[looking_at]);
    fprintf(stderr,"parameter removed\n");
    // fprintf(stderr,"Found -XL in the command line\nNew line is ~%s~\n",command_line);
  } else {
    fprintf(stderr,"returning unaltered.\n");
  }
}

char *timestamp() {
  static char timestamp[20];
  time_t time_now;
  struct tm *time_tm;

  time(&time_now);
  time_tm = localtime(&time_now);
  strftime(timestamp,20,"%d.%m.%Y %H:%M:%S",time_tm);

  return timestamp;
}

int job_processor(int entry_id, char *command_line) {
  char logfilename[200]="\0", failed_job_exec[2000]="\0";
  int job_ret=0;
  int logfile=0;
  int oldstdout=0,stdout_fh=fileno(stdout);
  int oldstderr=0,stderr_fh=fileno(stderr);

  if (!strlen(command_line)) { // There is NO command to run
    update_queue_entry( entry_id, QE_NOLAUNCH);
    return -1;
  }

  if( *sqr_rewrite) {
    sqr_cmd_line_mod(command_line);
  }
  update_queue_entry( entry_id, QE_ACTIVE);
  
  fflush(stdout); // Flush in preparation for redirection
  fflush(stderr);

  sprintf(logfilename,"%s/%ld.log",getenv("LOG"),entry_id);
  logfile=open(logfilename,O_WRONLY|O_CREAT|O_APPEND,0660); //This will become stdout
  if(logfile<0) { // If the log file cannot be opened, don't start the job.
    fprintf(stderr,"open log %s failed for %ld\n",logfilename,entry_id);
    update_queue_entry( entry_id, QE_NOLAUNCH );
    return -1;
  }

  oldstdout=dup(stdout_fh); // Remember where stdout and stderr went to.
  oldstderr=dup(stderr_fh);

  fprintf(stderr,"Processor for %ld to run %s\n", entry_id, command_line);

  dup2(logfile,stdout_fh); // Redirect stdout and stderr to the log file.
  dup2(logfile,stderr_fh);

  close(logfile);  // No need for the logfile handle

  fprintf(stderr,"Queue manager commenced this job at %s\n",timestamp());
  job_ret = system(command_line);  
  fprintf(stderr,"Queue manager completed this job at %s\n",timestamp());

  dup2(oldstdout,stdout_fh); // Revert stdout and stderr to their normal destinations
  dup2(oldstderr,stderr_fh);
  
  close(oldstdout);  // stdout is now to console
  close(oldstderr);  // stderr is now to console

  fflush(stdout);
  fflush(stderr);
  update_queue_entry( entry_id, job_ret?QE_ERROR:QE_SUCCESS);
  
  if(job_ret && strlen(failed_job_hook)) {
    sprintf(failed_job_exec,"%s %ld",failed_job_hook,entry_id);
    fprintf(stderr,"Failed job for %ld, running %s\n",entry_id,failed_job_exec);
    system(failed_job_exec);
  }
  
  // For printing, return status $? = 1 , bad file or bad printer, $?=0 for spooled ok.
  // Normal binaries/scripts, they return 0 just like lp does for OK.
  
  return 0; // OK
}


/*
 * The main loop:  The QCT daemon periodically polls in turn
 * the database, the LP system and the control file to see if
 * there is anything to do.
 */
void main_loop() {
  int loop_count=0;
  char msg[20]="\0";
  
  timed_message(stderr,"main_loop");

  trs_getsysparm("FAILED_JOB_HOOK",failed_job_hook);
  trs_getsysparm("SQR_REWRITE",sqr_rewrite);

  while ((loop_count <= loops) && queue_running ) {
    if (check_control_file()) {
      break;
    }
     
    if(submitted2pending()) { //     Update queue_entry
      EXEC SQL
        rollback;
      continue;  // If sub2pend  gives an error, loop around again.
    }
    timed_message(stderr,"Checked queue for submitted");
    pending2active();
    // timed_message(stderr,"SLEEPING");
    EXEC SQL get dbevent with wait = :QUEUE_PERIOD;// sleep(QUEUE_PERIOD);
    while (sqlca.sqlcode == 710) {
      EXEC SQL get dbevent with nowait;
    }
    // timed_message(stderr,"SLEPT");
    // timed_message(stderr,"Checked queue for executables");

    if (loops) {
      loop_count++;
      fprintf(stderr,"Main loop, count = %d\n",loop_count);
    }
  } /* end-while */

  notify_children();
  remove_control_file();
  wait_for_children_to_exit();
  
} /* end main_loop */



