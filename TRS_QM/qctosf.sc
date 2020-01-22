static char *stdid_stdid_qctosf_sc ="IDHFR qctosf D.1 20.07.2007";
static char *VERSION = "D.1 20.07.2007";

//  QCT-OSF
//  Queue Management Operating System File entity handler 
// 
//  Utility to maintain the OSF entity from the Queue Management system.
//  Called by standard TRS sqr tasks from within $TOP/trs_qm/qct directory.
// 
//  Syntax:
//   qctosf cmdtype username fname 
//  followed by various from
//     entry_id fstatus pr.queue pr.option fdesc taskname
// 
//   20/07/07  DJB  D.1 Recoded into C from SQR.

#include <typedef.h>
EXEC SQL include sqlca;       /* Standard ingres connection libs */
EXEC SQL include 'SESSION'; 
#include <stdio.h>            // stderr
//#include "trs_report.h"     // timed_message, operating_system_file tools

EXEC SQL include 'TRSQUEUE_101.sh';

void stdid() {
  fprintf(stderr, "IDHFR is %s\n\n", stdid_stdid_qctosf_sc);
}

const int OK_STATUS=0, FATAL=-1, NONFATAL=2;

char print_option[21]="\0";
 
int debug=0;
// ----------------------  PROTOTYPES ----------------------  
int init_session();
void discern_osf(int QCTOSF_action, char *user_id, char *filename, int argc, char **argv);
void parse_p_spec1(char *spec, char *paper_type, char *print_queue_id);
int  osf_print(char *user_id, char *fname);
int  osf_last_print_date(char *fname, char *print_queue_id, char *paper_type);
int  osf_update_status(char *user_id, char *fname, int fstatus, char *paper_type, char *print_queue_id );
int  osf_print_submit(char *user_id, const char *queue_id, char *fname, char *fdesc, 
		                  const char *print_time, char *paper_type );
int  osf_submit_job( const char *queue_id, char *user_id, char *paper_type, char *cmd, 
		                 char *fname, char *descr, const char *batch_time);
int osf_submit_job1( const char *queue_id, char *user_id, char *paper_type, 
                     char *cmd, char *fname, char *descr, const char *batch_time);
int  osf_insert(char *user_id, char *fname, char *fdesc, char *taskname, int fstatus, 
		char *paper_type, char *print_queue_id );
void osf_chmod(char *user_id, char *fname);
void osf_std_set(char *user_id, char *fname, char *fdesc, char *taskname);
int  osf_std_end(char *user_id, char *fname, int entry_id, char *p_spec);
int  osf_error(char *user_id, char *fname, int entry_id, char *p_spec);
int  osf_printqe (char *user_id, char *fname, int entry_id, char *p_spec);
int  osf_delete(char *user_id, char *fname);
int  osf_justclose(char *user_id, char *fname, int entry_id, char *p_spec);
int  osf_openclose(char *user_id, char *fname, char *fdesc, char *taskname, int entry_id, char *p_spec);


// ----------------------     MAIN    ----------------------
main(int argc, char **argv) {
  if( argc < 4 ) { // Cmd +3 Params
    fprintf(stderr, "Incorrect usage\n Usage: %s cmdtype username fname\n", argv[0]);
    exit(1);
  }
  
  if(init_session()) {
    fprintf(stderr, "Could not connect to Ingres, aborting\n");
    exit(1);
  }
  
  discern_osf(atoi(argv[1]), argv[2], argv[3], argc, argv);
}


int init_session(){
  logon(0); // DB connect control session
  
  commit();

  EXEC SQL
    set lockmode session where readlock = nolock;

  if(catch_error("session lockmode to nolock")) {
   return -1;
  }
  return 0;
} // init_session

int sufficient_params(int action, int got, int need) {
  if( need > got ) { 
    fprintf(stderr, "Insufficient parameters for acton # %d, got %d, needed %d\n", 
                    action, got-1, need-1);
    return 1;
  } 
  return 0;
}

void depreciated(int action) {
  fprintf(stderr, "OSF Action %d depreciated\n", action);
}

// -------------------------------------------------------------------------------------------------
void discern_osf(int QCTOSF_action, char *user_id, char *fname, int argc, char **argv){
  int QCTOSF_action_id=-1, status= 0;
    
  int entry_id=0;
  char fdesc[81]="\0", taskname[41]="\0", p_spec[41]="\0", cmd_line[251]="\0";
  const char queue_print1[13]="PRINTDEF", queue_batch1[13]="BATCHDEF";
  
  //fprintf(stderr, "Called with osf=%s, user=%s, fname=%s.\n", QCTOSF_action, user_id, fname);
  //QCTOSF_action_id= atoi(QCTOSF_action);
  //fprintf(stderr, "Number form of action is %d.\n", QCTOSF_action_id);
  
  switch(QCTOSF_action) {
    case QCTOSF_OPEN:                    // STD_OPEN
      if(sufficient_params(QCTOSF_action_id, argc, 6)) { break; }
      strcpy(fdesc, argv[4]);             //     input $fdesc 'Description'
      strcpy(taskname, argv[5]);          //     input $taskname 'Taskname'
      osf_std_set(user_id, fname, fdesc, taskname);
      break;

   case QCTOSF_STDCLOSE:                // STD_COMPLETE
      if(sufficient_params(QCTOSF_action_id, argc, 6)) { break; }
      entry_id = atoi(argv[4]);         //     input $entry_id 'Entry id', move $entry_id to #entry_id
      strcpy(p_spec, argv[5]);          //     input $p_spec 'Paper/print options'
      status = osf_std_end(user_id, fname, entry_id, p_spec);
      break;
      
   case QCTOSF_ERROR:                   // STD_ERROR
      if(sufficient_params(QCTOSF_action_id, argc, 6)) { break; }
      entry_id = atoi(argv[4]);         //     input $entry_id 'Entry id', move $entry_id to #entry_id
      strcpy(p_spec, argv[5]);          //     input $p_spec 'Paper/print options'
      osf_error(user_id, fname, entry_id, p_spec);
      break;
      
   case QCTOSF_DELETE:                 // STD_DELETE
      osf_delete(user_id, fname);
      break;
      
   case QCTOSF_PRINTQE:                 // REPRINT IF AUTOPRINT
      if(sufficient_params(QCTOSF_action_id, argc, 6)) { break; }
      entry_id = atoi(argv[4]);         //     input $entry_id 'Entry id', move $entry_id to #entry_id
      strcpy(p_spec, argv[5]);          //     input $p_spec 'Paper/print options'
      status = osf_printqe(user_id, fname, entry_id, p_spec);
      break;
      
   case QCTOSF_JUSTCLOSE:               // NON-STD-CLOSE
      if(sufficient_params(QCTOSF_action_id, argc, 6)) { break; }
      entry_id = atoi(argv[4]);         //     input $entry_id 'Entry id', move $entry_id to #entry_id
      strcpy(p_spec, argv[5]);          //     input $p_spec 'Paper/print options'
      osf_justclose(user_id, fname, entry_id, p_spec);
      break;
      
   case QCTOSF_REPRINT:                 // REPRINT
      osf_print(user_id, fname);
      break;
      
   case QCTOSF_CHMOD:                    // STANDARD CHMOD - Depreciated
      depreciated(QCTOSF_action_id);     // osf_chmod(user_id, fname, '');
      break;
      
   case QCTOSF_OPENCLOSE:                // OPEN AND CLOSE
      if(sufficient_params(QCTOSF_action_id, argc, 8)) { break; }
      strcpy(fdesc, argv[4]);            //     input $fdesc 'Description'
      strcpy(taskname, argv[5]);         //     input $taskname 'Taskname'
      entry_id = atoi(argv[6]);         //     input $entry_id 'Entry id', move $entry_id to #entry_id
      strcpy(p_spec, argv[7]);           //     input $p_spec 'Paper/print options'
      osf_openclose(user_id, fname, fdesc, taskname, entry_id, p_spec);
      break;
                              // ! the other options are experimental and depreciated
  case QCTOSF_LP:  // lp
      //      osf_print_submit(user_id, queue_print1 , fname, '', "date('now')", '' ); // return ignored
      //      break;
   case QCTOSF_PRINTED:  // lp from OSF (for test purposes only)
      //       osf_last_print_date(fname, '', '');
      //       break;
   case QCTOSF_SUBMIT:  // unix/no expected output, errors to system log
      //     if(sufficient_params(QCTOSF_SUBMIT, argc, 5)){ break; }
      //     strcpy(cmd_line, argv[4]);           //     input $cmd_line 'Command line'
      //     next_entry_id(#new_entry_id)
      //     if #new_entry_id = 0
      //       do debug_log('OSF: QCTOSF_submit:  no next entry_id', '')
      //     else
      //       if length($fname)=0 
      //         let $fname = '$REPORT/'||ltrim(edit(#new_entry_id, '99999999'), ' ')
      //       end-if
      //       let $cmd_line = $cmd_line || ' >>'||$fname||' 2>&1'
      // 
      //       do submit_job1( '{queue_batch1:, $user_id, #new_entry_id, 
      //                     '', $cmd_line, $fname, 'Test job', 
      //                     'date(''now'')', #status)
      //     end-if
      depreciated(QCTOSF_action_id);
      exit(-1);
   default:       //   when-other
      fprintf(stderr, "qctosf:  unknown command: %d\n", QCTOSF_action_id );
      exit(-1);
      break;
  }  //   end-evaluate
} // discern_osf

// ------------------------------------------------------------------------
//  Check is this report was supposed to be auto-printed after
//  creation.  If so submit a print entry.
int auto_print(char *user_id, char *fname, 
                int old_entry_id, char *paper_type, char *print_queue_id) {
  // $user_id, $fname, #old_entry_id, $paper_type, $print_queue_id
  int found=0, status=0;
  char fdesc[81]="\0";
  
  EXEC SQL begin declare section;
  char  qe_auto_print_result[2]="N",  qe_print_time[26]="\0", 
        qe_print_queue_id[13]="\0",   qe_paper_type[13]="\0",  qe_fdesc[81]="\0", 
       osf_print_queue_id[13]="\0",  osf_paper_type[13]="\0", osf_fdesc[81]="\0";
  EXEC SQL end declare section;

#ifdef FOR_ESQL_ONLY /* Required for Ingres II, commmenting is insufficient */
  EXEC SQL begin declare section;
  int old_entry_id;
  char *user_id, *fname;
  EXEC SQL end declare section;
#endif

  // Get job details from queue_entry
  EXEC SQL
    SELECT   auto_print_result,    print_queue_id,      ifnull(paper_type, 'LINEFLOW'), 
             print_time,           ifnull(description, '')
    INTO :qe_auto_print_result, :qe_print_queue_id,  :qe_paper_type, 
         :qe_print_time,        :qe_fdesc
    FROM queue_entry
    WHERE entry_id = :old_entry_id
    ;

  if (catch_error("Selecting matching queue_entry") || commit()) {
   return -1;
  }

  fprintf(stderr,"OSF qe_auto_print_result(%s), qe_print_queue_id(%s), qe_paper_type(%s),\n",
                      qe_auto_print_result,     qe_print_queue_id,     qe_paper_type);//DJB03.11.2017

  if(qe_auto_print_result[0] !='Y') { return 0;} 
   
  // use defaults(&var) unless overridden($var)
  if(!*paper_type) {
    strcpy(paper_type, qe_paper_type);         //   move &paper_type to $paper_type
  }
  if (!*print_queue_id) {
    strcpy(print_queue_id, qe_print_queue_id); //   move &print_queue_id to $print_queue_id
  }

  // Get file details from OSF
  EXEC SQL
    SELECT ifnull(print_queue_id, ''), ifnull(paper_type, ''), ifnull(file_desc, '')
    INTO     :osf_print_queue_id,        :osf_paper_type,        :osf_fdesc
    FROM  operating_system_file
    WHERE filename = :fname
    AND   user_id  = :user_id  ;
  EXEC SQL begin;
    found++;
  EXEC SQL end;
 
  if (catch_error("Selecting autoprint osf") || commit()) {
    return -1;
  }
  
  if (!found) {
    fprintf(stderr, "Autoprint: no OSF entry for filename %s by %s.\n", fname, user_id);
    return -1;
  }

  //  use defaults(&var) unless overridden($var)
  if (!*paper_type) {                   //  if length($paper_type)=0
    strcpy(paper_type, osf_paper_type);  //   move &QCTOSF_paper_type to $paper_type
  }

  if(!*print_queue_id) {
    strcpy(print_queue_id, osf_print_queue_id);  // move &QCTOSF_print_queue_id to $print_queue_id
  }

  // Use the more specific description if available
  strcpy(fdesc, (*osf_fdesc) ? osf_fdesc : qe_fdesc);    //    move &OSF_fdesc to $fdesc,  move &fdesc to $fdesc

  if(!*print_queue_id){ 
    fprintf(stderr, "Autoprint:  no print queue id");
  } else {
    if (osf_print_submit(user_id, print_queue_id, fname, fdesc, qe_print_time, paper_type ) ||
        osf_last_print_date( fname, print_queue_id, paper_type )) {
      fprintf(stderr, "Autoprint: error in print submit\n");
    }
  }
} // auto_print


// Handle any print options specified (to be extended...)
// 
// Note: At the moment (June 93) OSF doesn't store print_option (PO) 
// so reprints don't use it.  Hence paper_type (PT) is used for all 
// lp-forms type control.
int parse_p_spec(char *pspec, char *paper_type, char *print_queue_id){ // $pspec, :$paper_type, :$print_queue_id
  int  comma_at =0, offset=0, matched=1;
  char single_pspec[41]="\0";
  
  while( matched && pspec[offset]) {
    matched=sscanf (&pspec[offset], "%[^,]%n, ", single_pspec, &comma_at);
    // fprintf(stderr, "P spec, \t%d\t%d\t%s\t%s\t%d\n", offset, matched, single_pspec, pspec, comma_at);
    offset += comma_at +1;
    parse_p_spec1(single_pspec, paper_type, print_queue_id);
  }
  // fprintf(stderr, "Print spec is Type(%s), Queue(%s), Option(%s)\n", paper_type, print_queue_id, print_option);
  return 0;
} // parse_p_spec

void parse_p_spec1(char *single_spec, char *paper_type, char *print_queue_id){ // $spec, :$paper_type, :$print_queue_id
  int equals_at=0, matched=0;
  char type[41]="\0", value[41]="\0";
    
  matched=sscanf (single_spec, "%[^=]=%[^\n]", type , value);
  // fprintf(stderr, "pspec1 %s~%s\n", type, value);
  
  if (!*type || !*value) {
    return;
  }

  if(!strcmp(type, "PT")) {
    strcpy(paper_type, value);
  } else if(!strcmp(type, "QU")) {
    strcpy(print_queue_id, value);
  } else if(!strcmp(type, "PO")) {
    strcat(print_option, value);
    strcat(print_option, " ");
  } else if(!strcmp(type, "UG")) {
    fprintf(stderr, "UG P_Spec depreciated\n");
  } else {
    fprintf(stderr, "qctosf: invalid print option:%s=%s\n", type, value);
  }
  
// 'PT'  move $value to $paper_type
// 'QU'  move $value to $print_queue_id
// 'PO'  concat $value ' ' with $_print_option  ! Allow multiple PO to build-up
// 'UG'  Depreciated-  move $value to $_group_id  ! Set Unix group of file
// when-other   debug_log('qctosf: invalid print option:', $spec)

} // parse_p_spec1


// ! ------------------------------------------------------------------------
// !  Reprint a file stored in OSF
int osf_print(char *user_id, char *fname){ // $user_id, $fname
  EXEC SQL begin declare section;
   char print_queue_id[13]="\0", paper_type[13]="\0", fdesc[81]="\0";
  EXEC SQL end declare section;
  
  int retval = FATAL;             //   move {FATAL} to #status

  EXEC SQL
    SELECT print_queue_id,  paper_type,  file_desc
    INTO  :print_queue_id, :paper_type, :fdesc
    FROM  operating_system_file
    WHERE user_id = :user_id
    AND   filename = :fname
  ;
  EXEC SQL begin;
    retval = 0;     //   move 0 to #status
  EXEC SQL end;
  
  if (catch_error("Selecting matching osf") || commit()) {
    return -1;
  }
  //fprintf(stderr, "OSF had %s %s %s\n", print_queue_id, paper_type, fdesc);
  
  if (!retval) { // if #status = 0
    retval = osf_print_submit(user_id, print_queue_id, fname, fdesc, "now", paper_type) ||
             osf_last_print_date(fname, print_queue_id, paper_type); // osf_last_print_date( $fname, '', '', #status )
  } 
  
  return retval;
} // QCTOSF_print


// Set date-time when last printed for given filename
// - print queue and paper type are optional; if present they replace any existing value.
int osf_last_print_date(char *fname, char *print_queue_id, char *paper_type){ //$fname, $pqueue, $paper_type, :#status 

  int row_count=0;
  EXEC SQL begin declare section;
  short print_queue_id_ind=(!print_queue_id || !*print_queue_id), 
        paper_type_ind=(!paper_type || !*paper_type); 
  char  osf_print_queue_id[13]="\0", osf_paper_type[13]="\0";
  EXEC SQL end declare section;
  int status=FATAL;
  
  if(!print_queue_id_ind) {
    strcpy(osf_print_queue_id, print_queue_id);
  }
  
  if(!paper_type_ind) {
    strcpy(osf_paper_type, paper_type);
  }

  //fprintf(stderr, "Last Print:%s, %d, %s, %d, %s.\n", osf_print_queue_id, 
  //        print_queue_id_ind, osf_paper_type, paper_type_ind, fname);
  EXEC SQL
    UPDATE Operating_System_File
    SET last_printed_date = date('now')
      , lst_mod_date      = date('now')
      , lst_mod_by        = dbmsinfo('username')
      , print_queue_id    = ifnull(:osf_print_queue_id:print_queue_id_ind, print_queue_id)
      , paper_type        = ifnull(:osf_paper_type:paper_type_ind,         paper_type)
    WHERE filename        = :fname
    ;
  
  if(catch_error("Updating osf printed date") ||
     (row_count =rowcount(), commit())) {
    return -1;
  }
  
  if( row_count == 1 ) { //   if #_sql-count = 1
    status = 0;          //     move 0 to #status
  } else {
    fprintf(stderr, "qctosf: error in QCTOSF_last_print_date: filename %s has %d entries\n", fname, row_count );
    return -1;
  }

  return 0;
} // QCTOSF_last_print_date


// Update status of filename in OSF, at same time record (if known) paper_type and print_queue
int osf_update_status(char *user_id, char *fname, int fstatus, 
                      char *paper_type, char *print_queue_id ){ // $fname, #fstatus, $paper_type, $print_queue_id 

  int row_count=0;
  EXEC SQL begin declare section;
  short queue_ind=(!print_queue_id || !*print_queue_id), 
        type_ind=(!paper_type || !*paper_type); /* Null strings indicators as C can't understand 'undef' */
  char  osf_print_queue_id[13]="\0", osf_paper_type[13]="\0";
  EXEC SQL end declare section;

#ifdef FOR_ESQL_ONLY /* Required for Ingres II, commmenting is insufficient */
  EXEC SQL begin declare section;
  int fstatus;
  char *user_id, *fname, *paper_type, *print_queue_id;  
  EXEC SQL end declare section;
#endif

  if(!queue_ind) {
    strcpy(osf_print_queue_id, print_queue_id);
  }
  
  if(!type_ind) {
    strcpy(osf_paper_type, paper_type);
  }

  fprintf(stderr, "OSF Update status:paper_type(%s), type_ind(%d), print_queue_id(%s), queue_ind(%d), fname(%s)\n",
                                     paper_type,     type_ind,     print_queue_id,     queue_ind,     fname);//DJB03.11.2017
  EXEC SQL
    UPDATE Operating_System_File
    SET file_status    = :fstatus
      , lst_mod_date   = date('now')
      , lst_mod_by     = :user_id
      , print_queue_id = ifnull(:osf_print_queue_id:queue_ind, print_queue_id)
      , paper_type     = ifnull(:osf_paper_type:type_ind, paper_type)
    WHERE filename = :fname
    and user_id = :user_id
  ;
  
  if( catch_error("Updating osf in osf_update_status") ||
      (row_count =rowcount(), commit())) {
    return -1;
  }

  if(row_count != 1) {
    fprintf(stderr, "qctosf: error in QCTOSF_update_status; for %s row count is %d\n", 
            fname, row_count);
    return -1; //  move {FATAL} to #_status
  }
  return 0;
}  // QCTOSF_update_status

// !  Submit a print job to the queue manager
// !
int osf_print_submit(char *user_id, const char *queue_id, char *fname, char *fdesc, const char *print_time, 
                     char *paper_type ){ //     $user_id, queue_id, $fname, $fdesc, $print_time, $paper_type, :#status 
  int status = FATAL;               //  default error move {FATAL} to #status
  EXEC SQL begin declare section;
  char access_command[129]="\0";
  EXEC SQL end declare section;

#ifdef FOR_ESQL_ONLY /* Required for Ingres II, commmenting is insufficient */
  EXEC SQL begin declare section;
  char *queue_id;
  EXEC SQL end declare section;
#endif

  char print_cmd[200]="\0";

  if (!strcmp(queue_id, "NONE")) {      // Program can disable printing if needed
    return 0;                          // move {NONFATAL} to #status, goto exit
  }

  EXEC SQL
    SELECT pr.access_command
    INTO     :access_command
    FROM  queue q join printer pr
      ON  q.printer_id  = pr.printer_id 
    WHERE q.queue_id    = :queue_id
    ;

  if( catch_error("Selecting printer access command") || commit()) {
    return -1;
  }

  if(!*access_command) {
    fprintf(stderr, "Print Submit:  No printer access command for queue id %s\n", queue_id);
    return -1;
  }

  // TODO test existence of $fname
  sprintf(print_cmd, "%s %s %s", access_command, print_option, fname);
  //  fprintf(stderr, "got here %d\n", __LINE__);
  status =osf_submit_job( queue_id, user_id, paper_type, print_cmd, 
                              fname, fdesc, print_time );
  commit();
  return status;
} // print_submit


// !  Submit a job to the queue manager
int osf_submit_job( const char *queue_id, char *user_id, char *paper_type, 
                      char *cmd, char *fname, char *descr, const char *batch_time){
  // $queue_id, $user_id, $paper_type, $cmd, $fname, $descr, $batch_time,   :#status 
  //   do next_entry_id(#new_entry_id) // Let osf_submit_job1 let insert_qe do it
  
  return osf_submit_job1( queue_id, user_id, paper_type, 
                            cmd, fname, descr, batch_time);
} // submit_job

 
int osf_submit_job1( const char *queue_id, char *user_id, char *paper_type, 
                 char *cmd, char *fname, char *descr, const char *batch_time){
  // $queue_id, $user_id, #new_entry_id, $paper_type,  $cmd, $fname, $descr, $batch_time, :#status  
  queue_entry_ qe;
  int status=0;
  char qe_batch_time[26]="\0";
  memset (&qe, 0, sizeof(queue_entry_) ); // Zero all ints, and blank all strings.

   if(!*batch_time) {
      strcpy(qe_batch_time, "now");
   } else {
      strcpy(qe_batch_time, batch_time);
   }

  qe.entry_id = 0;            // qe->entry_id let the insert assign one
  strcpy(qe.batch_time, "now");
  strcpy(qe.queue_id          , queue_id);
  qe.entry_status             = QE_SUBMITTED;
  strcpy(qe.batch_time        , qe_batch_time);
  strcpy(qe.user_id           , user_id);
  strcpy(qe.command_line      , cmd);
  strcpy(qe.paper_type        , paper_type);
  strcpy(qe.filename          , fname);
  strcpy(qe.description       , descr);
  strcpy(qe.auto_print_result , "N");
  strcpy(qe.lst_mod_by        , user_id);
  strcpy(qe.lst_mod_date      , "now");

  qe.auto_process_id[0]=0;   // Not wanted, but required for insert_queue_entry()
  qe.print_time[0]=0;
  qe.print_queue_id[0]=0;
  qe.request_time[0]=0;
  
  status = insert_queue_entry(&qe);
   
  commit();

  if (status<0) {
    fprintf(stderr, "qctosf: Submit: Failure on insert\n");
  } else {
    fprintf(stderr, "qctosf: Submitted as job %d\n", status);
  }
  return !status;
} // submit_job1
// 
// ! ----------------------------------------------------------------------
// !
// ! Make a new entry in operating_system_file.
// ! fstatus is usually {qe_incomplete} until report finished
// !
int osf_insert(char *user_id, char *fname, char *fdesc, char *taskname, 
     int fstatus, char *paper_type, char *print_queue_id ) {
//      $user_id, $fname, $fdesc, $taskname, #fstatus, paper_type, $print_queue_id   
#ifdef FOR_ESQL_ONLY /* Required for Ingres II, commmenting is insufficient */
  EXEC SQL begin declare section;
  char *user_id, *fname, *fdesc, *taskname, *paper_type, *print_queue_id;
  int fstatus;
  EXEC SQL end declare section;
#endif
 
  EXEC SQL
    INSERT INTO operating_system_file
     ( user_id, filename, file_desc, task_name, 
       file_creation_date, file_status, print_queue_id, paper_type, 
       lst_mod_by, lst_mod_date )
    VALUES( :user_id, :fname,  :fdesc, :taskname, 
       date('now'), :fstatus, :print_queue_id, :paper_type, 
       :user_id, date('now') )
    ;
    
  if(catch_error("qctosf: error in QCTOSF_insert") || commit()) {
    return -1;
  }

  return 0;
} // QCTOSF_insert


// ! ------------------------------------------------------------------------
// !
// !  Change Unix permissions/ownership.
// !
// !  The standard gives Ownership & Read/Write to the requestor; 
// !                     Group & Read to trs_sys (for reprint)
// !  Optional group access gives
// !                     Ownership & Read to the DBA(for reprint); 
// !                     Group & Read/Write to the requested group 
// !
void osf_chmod(char *user_id, char *fname){
// $user_id, $fname, $group_id
//   let $fname = '"'||$fname||'"'
//   if instr( ', {PSEUDO_USERS}, ' , ', '||$user_id||', ' , 1 )
//       let $parm_name = $user_id||'_SUPERVISOR'
//       do trs_getsysparm( $parm_name, '', $unix_user, #_status )
//       if length( $unix_user )
// 	move $unix_user to $user_id
//       end-if
//   end-if
// 
//   ! requested 'G'roup ownership (true TRS names are lowercase)
//   if substr( $user_id, 1, 1)='G'
//     let $group_id = substr( $user_id, 2, length($user_id) - 1 )
//   end-if
// 
//   if $group_id = ''     ! standard single owner access
//     string 'chgrp' '{group}' $fname  '; chmod 640' $fname  '; chown' $user_id $fname 
//        by ' ' into $cmd
//   else    ! group access (DBA keeps ownership for reprint)
//     string 'chgrp' $group_id $fname  '; chmod 460' $fname   by ' ' into $cmd
//   end-if
//   do call_unix($cmd, #_status)
  fprintf(stderr, "chmod depreciated\n");
  return;
} 
// 
// ! ------------------------------------------------------------------------
// !
// !  Standard action when a Report is about to be generated
// !
// !  Set it to QCTOSF_incomplete so that the user  can see that 
// !  the report has started but not yet finished.
// !
void osf_std_set(char *user_id, char *fname, char *fdesc, char *taskname){
// $user_id, $fname, $fdesc, $taskname
  osf_insert(user_id, fname, fdesc, taskname, OSF_FS_INCOMPLETE, "", "" );
}

// !  Standard action when a Report has been generated
// !
// !  Update OSF table.  Check if should auto-print.
// !  Change Unix permissions/ownership.
// !
int osf_std_end(char *user_id, char *fname, int entry_id, char *p_spec){
//$user_id, $fname, #entry_id, $p_spec
  char paper_type[13]="\0", print_queue_id[13]="\0";
  return parse_p_spec(p_spec, paper_type, print_queue_id) ||
         osf_update_status(user_id, fname, OSF_FS_COMPLETE, paper_type, print_queue_id ) || 
         auto_print(user_id, fname, entry_id, paper_type, print_queue_id);
// depreciated osf_chmod($user_id, $fname, $_group_id)
} 

// !  Semi-Standard action when a Report has been generated, 
// !  but an error was detected
// !
// !  Update OSF table.  Do not auto-print.
// !  Change Unix permissions/ownership (so that the requestor 
// !  can view the file to see what went wrong).
// !

int osf_error(char *user_id, char *fname, int entry_id, char *p_spec){
// $user_id, $fname, #entry_id, $p_spec
  char paper_type[13]="\0", print_queue_id[13]="\0";

  return parse_p_spec(p_spec, paper_type, print_queue_id) ||
          osf_update_status(user_id, fname, OSF_FS_ERROR, paper_type, print_queue_id ); 
// depreciated osf_chmod($user_id, $fname, $_group_id)
} 

// !  Reprint from queue_entry (if auto-print)
// !
// !  Program needs multiple copies so reprint.  Of course, first
// !  check if should auto-print (otherwise user didn't ask to 
// !  print any at all).
// !
int osf_printqe (char *user_id, char *fname, int entry_id, char *p_spec){
// $user_id, $fname, #entry_id, $p_spec
  char paper_type[13]="\0", print_queue_id[13]="\0";
// ! (could here load paper_type stored in OSF if none already found)
  return parse_p_spec(p_spec, paper_type, print_queue_id) ||
         auto_print(user_id, fname, entry_id, paper_type, print_queue_id);
} 
// 
// !
// !  Action when a Report is to be deleted
// !
// !  Update OSF table.  Do not check if should auto-print.
// !  Do not chmod to requestor.
// !  Delete Unix file.
// !
int osf_delete(char *user_id, char *fname){
// $user_id, $fname
  int rm_retval=0;
  osf_update_status(user_id, fname, OSF_FS_DELETED, "", "" ); 
  rm_retval = unlink(fname);              //  string '/usr/bin/rm' $fname by ' ' into $cmd; do call_unix($cmd)
  return rm_retval; 
} 


// !
// !  Close report without auto-print
// !
// !  Update OSF table.  Do not check if should auto-print.
// !  Do chmod to requestor.
// !
int osf_justclose(char *user_id, char *fname, int entry_id, char *p_spec){
// $user_id, $fname, #entry_id, $p_spec
  char paper_type[13]="\0", print_queue_id[13]="\0";
  //fprintf(stderr, "osf_justclose(char %s, char %s, int %d, char %s.\n", 
  //           user_id, fname, entry_id, p_spec);

  return parse_p_spec(p_spec, paper_type, print_queue_id) ||
         osf_update_status(user_id, fname, OSF_FS_COMPLETE, paper_type, print_queue_id ); 
// depreciated osf_chmod($user_id, $fname, $_group_id)
} 

// !  Non-standard action when a Report has been already generated
// !
// !  Insert into OSF table.  Check if should auto-print.
// !  Change Unix permissions/ownership.
// !
int osf_openclose(char *user_id, char *fname, char *fdesc, char *taskname, int entry_id, char *p_spec){
// $user_id, $fname, $fdesc, $taskname, #entry_id, $p_spec
  char paper_type[13]="\0", print_queue_id[13]="\0";

  return parse_p_spec(p_spec, paper_type, print_queue_id) ||
         osf_insert(user_id, fname, fdesc, taskname, OSF_FS_COMPLETE, 
                    paper_type, print_queue_id ) ||
         auto_print(user_id, fname, entry_id, paper_type, print_queue_id);
//  depreciated osf_chmod($user_id, $fname, $_group_id)
} 
