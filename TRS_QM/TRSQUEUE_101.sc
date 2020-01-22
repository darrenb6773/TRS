static char *stdid_TRSQUEUE_10101 ="IDHFR TRSQUEUE_101 D.1 20.07.2007";
static char *VERSION = "D.1 20.07.2007"; /* -*- c -*- */

/* Modification History
 * 20.07.2007 D.1 DJB Created for common modules relating to the queue manager   
 *
 */  

/* EXEC SQL include 'TRSQUEUE_101.sh'; */
#include <stdio.h>
#include <typedef.h>

EXEC SQL include sqlca;       /* Standard ingres connection libs */

int next_entry_id() {
EXEC SQL BEGIN DECLARE SECTION;
  long new_entry_id=0;
  char errmsg[200]="\0";
EXEC SQL END DECLARE SECTION;
  // Can't commit(); as it would allow partial list of auto-reprocess jobs to be inserted.

  EXEC SQL
    EXECUTE PROCEDURE gs_sel_proc( seq_name = 'QENTRY' )
    INTO :new_entry_id
  ;

  if( sqlca.sqlcode < 0 || new_entry_id < 0 ) {
    fprintf( stderr,
             "ERROR selecting QUEUE_ENTRY id: sqlca.sqlcode = %d, return code = %d\n",
   sqlca.sqlcode,
   new_entry_id );

    if( sqlca.sqlcode < 0 )
    {
      EXEC SQL inquire_sql( :errmsg = errortext );
      fprintf( stderr, "%s\n", errmsg );
    }

    return -1;
  }

  // fprintf(stderr,"Next entry is %d\n",new_entry_id);  
  // Can't commit(); as it would allow partial list of auto-reprocess jobs to be inserted.
  return new_entry_id;
} // next_entry_id

int insert_queue_entry(queue_entry_ *qe) { // Callers responsiblity to fill in all values 
#ifdef __ESQL_ONLY__
  EXEC SQL begin declare section;
  struct {
    int   entry_id;           /* THIS IS ONLY A PARTIAL DEFINITION FOR ESQL ONLY, FULLY DEFINED IN "TYPEDEF.H"*/
    char  queue_id[13];
    char  user_id[11]; 
    int   entry_status;
    char  batch_time[26];
    char  auto_process_id[9];
    char  auto_print_result[2];
    char  print_time[26]; 
    char  paper_type[13];         
    char  print_queue_id[13]; 
    char  filename[129];
    char  description[81]; 
    char  request_time[26];
    char  command_line[251];
    char  lst_mod_by[11];    /* If default is desired, caller must pass empty string. */
    } *qe;   
  EXEC SQL end declare section;
#endif;
  EXEC SQL begin declare section;
  int row_count=0;
  EXEC SQL end declare section;
  
  if(qe->entry_id <= 0) {
    qe->entry_id=next_entry_id();
    if(qe->entry_id <= 0) {
      fprintf (stderr, "Unable to get an entry_id for %s\n",qe->command_line); 
      return -1;
    }
  }
  
  if(!*qe->lst_mod_by) {
    EXEC SQL
      select dbmsinfo('username')
      into :qe->lst_mod_by
    ;
    if (catch_error("Selecting the default username for qe insert")) {
      return -1;
    }
    if(!*qe->lst_mod_by) {
      fprintf(stderr,"Last mod by must be filled in\n");
      return -1;
    }
  }

  //   fprintf(stderr,"Insert entry_id\n");
  //   fprintf(stderr,"\tid %d,\n",qe->entry_id);
  //   fprintf(stderr,"\tqueue_id %s,\n",qe->queue_id );
  //   fprintf(stderr,"\tuser_id%s,\n", qe->user_id);
  //   fprintf(stderr,"\tentry_status%d,\n", qe->entry_status);
  //   fprintf(stderr,"\tbatch_time%s,\n",qe->batch_time);
  //   fprintf(stderr,"\tauto_process_id%s,\n", qe->auto_process_id);
  //   fprintf(stderr,"\tauto_print_result%s,\n",qe->auto_print_result );
  //   fprintf(stderr,"\tprint_time%s,\n",qe->print_time);
  //   fprintf(stderr,"\tpaper_type%s,\n", qe->paper_type);
  //   fprintf(stderr,"\tprint_queue_id%s,\n",qe->print_queue_id );
  //   fprintf(stderr,"\tfilename%s,\n", qe->filename);
  //   fprintf(stderr,"\tdescription%s,\n",qe->description);
  //   fprintf(stderr,"\trequest_time%s,\n",qe->request_time );
  //   fprintf(stderr,"\tcommand_line%s,\n",qe->command_line);
  //   fprintf(stderr,"\tlst_mod_date%s,\n",  "now");
  //   fprintf(stderr,"\tlst_mod_by %s\n", qe->lst_mod_by);
  
  EXEC SQL
    INSERT INTO queue_entry(
        entry_id,          queue_id,             user_id,                entry_status,
        batch_time,        auto_process_id,      auto_print_result,      print_time,
        paper_type,        print_queue_id,       filename,               description,
        request_time,      command_line,         lst_mod_date,           lst_mod_by  )  
    VALUES (
      :qe->entry_id,     :qe->queue_id,        :qe->user_id,           :qe->entry_status,
      :qe->batch_time,   :qe->auto_process_id, :qe->auto_print_result, :qe->print_time,
      :qe->paper_type,   :qe->print_queue_id,  :qe->filename,          :qe->description,
      :qe->request_time, :qe->command_line,    date('now'),            :qe->lst_mod_by)
    ;
  
  if(catch_error("inserting queue_entry")) {
    return -1;
  }
  
  EXEC SQL inquire_sql( :row_count = rowcount );
  
  if (row_count != 1) {
    fprintf(stderr,"Row count assertion failure, count was %d should be 1\n",row_count);
    return -1;
  }
  return qe->entry_id;
}


int rowcount() {
  EXEC SQL begin declare section;
  int row_count=0;
  EXEC SQL end declare section;
  
  EXEC SQL inquire_sql( :row_count = rowcount );
  
  return row_count;
}


int commit(){
  EXEC SQL 
    commit;
  
  if(catch_error("Commiting")) {
   return -1;
  }
  return 0;
}

