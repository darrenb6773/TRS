static char *stdid_stdid_qctqe_sc ="IDHFR qctqe D.1 19.08.2007";
static char *VERSION = "D.1 19.08.2007";

// SQR-QCT-SUBMIT
// prototype Queue Management Queue Entry Submission process       
// 
// Utility to submit a queue-entry from a batch process.
// Called by standard TRS sqr and 'C' tasks.
// 
// Syntax:
//     qctqe report_type report_id user_id current_entry_id description 
//        parameter_string paper_type print_queue_id auto_print
//        [ batch_time [ print_time [ entry_status ]]]
// 
// where:
//     report_type is one of: 'SQR','RPT','OUT','C','UTL'
//     report_id is the program name to be invoked
//     user_id is the user_id who has invoked the calling process
//     current_entry_id is the entry_id of the calling process
//     description is the fdesc to be printed on the report header
//     parameter_string is the parameters to be passed to the called report
//     paper_type is the paper type for the generated report - defaults to the 
//                paper type of the calling process
//     print_queue_id is the queue id for the generated report - defaults to 
//                the print queue id of the calling process
//     auto_print is a Y/N indicator that controls whether the output from 
//                the called process is automatically printed - default to the
//                setting of the calling process
//                
// and where the following are optional or may be passed in as ''
//     batch_time is the earliest to start, default 'now'
//     print_time is the earliest to print, default batch_time
//     entry_status is initial status, default 3=go; alternaitve 4=hold
// 
// 19/08/2007 DJB D.1 Recoded into C(Yay) from SQR(Yuck).

#include <typedef.h>

EXEC SQL include sqlca;       /* Standard ingres connection libs */

EXEC SQL include 'SESSION'; 

#include <stdlib.h>
#include <stdio.h>            // stderr

//#include "trs_report.h"     // timed_message, operating_system_file tools

EXEC SQL include 'TRSQUEUE_101.sh';
char *report_dir=0;
char batch_time[26]          ="\0", batch_queue_id[13]="\0", user_id[11]="\0";
char auto_print_result[2]    ="\0", print_time[26]="\0", paper_type[13]="\0";
char print_queue_id[13]      ="\0", report_filename[129]="\0";
char fdesc[81]="\0", cmd[251]="\0", para_string[251]="\0";
char report_type[5]="\0", report_id[50]="\0";

void quote_str( char *s1, char *s2 );

void stdid() {
  fprintf(stderr,"IDHFR is %s\n\n",stdid_stdid_qctqe_sc);
}

// ----------------------     MAIN    ----------------------
main(int argc, char **argv) {

  int old_entry_id=0,entry_status=QE_SUBMITTED;
  
  if( argc < 5 ) { // Cmd +9 Params
    fprintf(stderr,"Incorrect usage (%d Params given)\n Usage:\n"
      "%s report_type report_id user_id current_entry_id description\n" 
      "\tparameter_string paper_type print_queue_id auto_print\n" 
      "\t[ batch_time [ print_time [ entry_status ]]]\n",argc,argv[0]);
    exit(1);
  }
  
  strcpy(report_type       ,argv[1]);   // Report Type
  strcpy(report_id         ,argv[2]);   // Report Id
  strcpy(user_id           ,argv[3]);   // User id
  old_entry_id       = atoi(argv[4]);   // Current Entry Id
  if (argc > 5) { 
    strcpy(fdesc	             ,argv[5]);   // Report Description
    if (argc > 6) 	{ 
      strcpy(para_string   ,argv[6]);   // Parameter String
      if (argc > 7) { 
	strcpy(paper_type        ,argv[7]);   // Paper Type
	if (argc > 8) { 
	  strcpy(print_queue_id    ,argv[8]);   // Print Queue Id
	  if (argc > 9) { 
	    strcpy(auto_print_result ,argv[9]);   // Automatic Printing
	  }
	}
      }
    }
  }
  
  if (argc>10) { // optional parameters
    strcpy(batch_time, argv[10]);       // Batch Time
    if (argc>11) {
      strcpy(print_time, argv[11]);     // Print Time
      if (argc>12) {
        entry_status= atoi(argv[12]); // Entry Status
  }}}

  if (!*batch_time){                    // Batch Time Default
    strcpy(batch_time,"now");
  }
  
  if (!*print_time){                    // Print Time Default
    strcpy(print_time,batch_time);
  }
  
  if((entry_status<1 )|| (entry_status > 23)) { // Entry status bound check
    entry_status= QE_SUBMITTED;
  }

  logon(0); // DB connect control session
  queue_process_lookup(old_entry_id,report_id,batch_queue_id,print_queue_id);
  if ((report_dir = getenv("REPORT")) == NULL) {
    fprintf(stderr,"REPORT not set\n");
  }


  //  report_dir=getenv( "REPORT" );
return !build_and_submit(entry_status,old_entry_id);
}

int queue_process_lookup(int old_entry_id, char *report_id, char *batch_queue_id, char *print_queue_id) {
  EXEC SQL begin declare section;
  char queue_type[2]="\0", option_type[2]="\0", queue_id[13]="\0";
  EXEC SQL end declare section;

#ifdef FOR_ESQL_ONLY
  EXEC SQL begin declare section;
  char *report_id;
  EXEC SQL end declare section;
#endif
 
  EXEC SQL
    SELECT queue_type,  option_type,  queue_id
    INTO  :queue_type, :option_type, :queue_id
    FROM  queue_process
    WHERE process_id = :report_id
      AND queue_type in ('B','P')
      AND option_type in ('M','D')
    ORDER BY queue_type, option_type desc, queue_id
    ;
  EXEC SQL begin;
    if( *queue_type == 'B') {
      strcpy(batch_queue_id,queue_id);
    }  
    if (*queue_type == 'P') {
      strcpy(print_queue_id,queue_id);
    }
  EXEC SQL end;
  
  if (catch_error("Selecting from queue_process") || commit()) {
    return -1;
  }

  return 0;
}

int build_and_submit(int entry_status, int old_entry_id) {
  int found=0;
  queue_entry_ qe;
  int status=0, new_entry_id = next_entry_id();
  char fdesc_quoted[81]="\0";
 // char *report_dir;

#ifdef FOR_ESQL_ONLY
  EXEC SQL begin declare section;
  int old_entry_id;
  char *user_id;
  EXEC SQL end declare section;
#endif

  EXEC SQL begin declare section;
  int   kind_found=0;
  char qry_auto_print_result[2]="\0", qry_print_queue_id[13]="\0", qry_batch_queue_id[13]="\0",
       qry_paper_type[13]="\0",       qry_print_time[26]="\0",     request_time[26]="\0";   
  EXEC SQL end declare section;
  fprintf(stderr,"IDHFR is %s\n\n",stdid_stdid_qctqe_sc);

  EXEC SQL
    SELECT 1, auto_print_result, ifnull(print_queue_id,''), ifnull(paper_type,''), ifnull(print_time,'now'), queue_id
    INTO :kind_found,     :qry_auto_print_result, :qry_print_queue_id,
         :qry_paper_type, :qry_print_time,        :qry_batch_queue_id
    FROM  queue_entry
    WHERE entry_id = :old_entry_id
    UNION 
    SELECT 2, 'N', def_print_queue, 'LINEFLOW', date('now'), def_batch_queue
    FROM  trsuser
    WHERE user_id = :user_id 
    UNION 
    SELECT 3, 'N', 'PRINTDEF', 'LINEFLOW', date('now'), 'BATCHDEF'
    FROM  dual
    ORDER BY 1 desc
  ;
  EXEC SQL begin;
    found++; // 1 desc, leaves the lower marked row in the row cache.
  EXEC SQL end;

  if(catch_error("selecting the print queues") || commit() ) {
    return -1;
  }

  if (!found || ( kind_found > 1)) {
    fprintf(stderr,"WARNING: no parent queue_entry #%d\n",old_entry_id);
  }
  if (kind_found > 2 ) {
    fprintf(stderr,"WARNING: no user named %s\n",user_id);
  }


  EXEC SQL
    SELECT date('now')
    INTO :request_time;
  
  if(catch_error("Unable to find the request_time")|| commit()) {
    return -1;
  }
//  ! use defaults(&var) unless overridden($var)
  if (!*auto_print_result) {                          //   if length($auto_print_result)=0
    strcpy(auto_print_result,qry_auto_print_result) ; //     move &auto_print_result to $auto_print_result
  } 

  if(!*paper_type) {                                  //   if length($paper_type)=0
    strcpy(paper_type,qry_paper_type);                //     move &paper_type to $paper_type
  }

  if(!*print_queue_id) {                              //   if length($print_queue_id)=0
    strcpy(print_queue_id,qry_print_queue_id);        //     move &print_queue_id to $print_queue_id
  }

  //  print_time is defaulted to batch_time in the get_param section
  if(!batch_queue_id) {                               //   if length($queue_id)=0
    strcpy(batch_queue_id,qry_batch_queue_id);        //     move &batch_queue_id to $queue_id 
  }
  
  if(!*auto_print_result) {                           //   if length($auto_print_result)=0
    strcpy(auto_print_result,"N");                    //     move 'N' to $auto_print_result
  }
 
  if(!*paper_type) {                                  //   if length($paper_type)=0
    strcpy(paper_type,    "LINEFLOW");                //     move 'LINEFLOW' to $paper_type
  }
 
  if(!*print_queue_id) {                              //   if length($print_queue_id)=0
    strcpy(print_queue_id,"PRINTDEF");                //     move 'PRINTDEF' to $print_queue_id
  }
 
  if(!*batch_queue_id) {                              //   if length($queue_id)=0
    strcpy(batch_queue_id,"BATCHDEF");                //     move 'BATCHDEF' to $queue_id
  } 
 
  // --- parameters for queue_entry ---

  sprintf(report_filename,"%s/%d",report_dir,new_entry_id); //  let $report_filename = '$REPORT/' || $entry_id
  quote_str( fdesc, fdesc_quoted );

  // Build the command line 
  if(!strcmp(report_type,"SQR")) { //   if $report_type = 'SQR' ! sqr reports with std parameters 
    sprintf(cmd,"$SQRBIN/sqrt $BIN/%s $DB_NAME %s %d '%s' %s '%s' %s -F%s",
            report_id, user_id, new_entry_id, request_time,
            report_filename, fdesc_quoted, para_string, report_filename);
            //  $SQRBIN/sqrt $BIN/' || $report_id || ' $DB_NAME ' ||
            //             $user_id || ' ' || $entry_id || ' ''' || &request_time || ''' ' ||
            //             $report_filename || ' ' || $fdesc_quoted || ' ' || $para_string || 
            //             ' -F' || $report_filename
  } else if(!strcmp(report_type,"RPT")) { //     if $report_type = 'RPT' ! Unix reports with std parameters 
    sprintf(cmd,"$BIN/%s %s %d '%s' %s '%s' %s",
            report_id, user_id, new_entry_id, request_time,
            report_filename, fdesc_quoted, para_string);
            //       let $cmd = '$BIN/' || $report_id || ' ' || $user_id || ' ' || $entry_id 
            //             || ' ''' || &request_time || ''' ' ||
            //             $report_filename || ' ' || $fdesc_quoted || ' ' || $para_string
  } else if(!strcmp(report_type,"OUT")) {//       if $report_type = 'OUT' ! single processes with output Reported 
    sprintf(cmd,"$BIN/%s %s %d '%s' %s '%s' %s > %s", 
            report_id, user_id, new_entry_id, request_time, report_filename,
            fdesc_quoted, para_string, report_filename );
            //         let $cmd = '{ $BIN/' || $report_id || ' ' || $user_id || ' ' 
            //             || $entry_id || ' ''' || &request_time || ''' ' 
            //             || $report_filename || ' ' ||  $fdesc_quoted || ' ' || $para_string
            //             || ' ; } > ' || $report_filename
  } else if(!strcmp(report_type,"C") || !strcmp(report_type,"UTL") ) {
    //  if $report_type = 'C' or 'UTL' ! Batch processes, only errors logged 
    sprintf(cmd,"$BIN/%s %s", report_id, para_string);
            //           let $cmd = '$BIN/' || $report_id || ' ' || $para_string
  } else {
    fprintf(stderr,"Error - Unknown report type.\n");
    fprintf(stderr,"Contact your system support person.\n");
  }

  memset (&qe,0,sizeof(queue_entry_) ); // Zero all ints, and blank all strings.

   if(!*batch_time) {
      strcpy(batch_time,"now");
   }                                                 // Column Name       Type      
  qe.entry_id = new_entry_id;                        // entry_id          integer   
  strcpy(qe.queue_id           , batch_queue_id);    // queue_id          varchar   
  strcpy(qe.user_id           , user_id);            // user_id           varchar   
  qe.entry_status             = entry_status;        // entry_status      integer   
                                                     // entry_priority    integer   
  strcpy(qe.batch_time        , batch_time);         // batch_time        date      
                                                     // menu_id           varchar   
                                                     // menu_item_id      varchar   
  qe.auto_process_id[0]=0;                           // auto_process_id   varchar   
  strcpy(qe.auto_print_result , auto_print_result);  // auto_print_result varchar   
  strcpy(qe.print_time        , print_time);         // print_time        date      
  strcpy(qe.paper_type        , paper_type);         // paper_type        varchar   
  strcpy(qe.print_queue_id    , print_queue_id);     // print_queue_id    varchar   
                                                     // pid               varchar   
                                                     // lp_name           varchar   
  strcpy(qe.filename          , report_filename);    // filename          varchar   
  strcpy(qe.description       , fdesc);              // description       varchar   
  strcpy(qe.request_time      ,"now");               // request_time      date      
  strcpy(qe.command_line      , cmd);                // command_line      varchar   
  strcpy(qe.lst_mod_date      , "now");              // lst_mod_date      date      
  strcpy(qe.lst_mod_by        , user_id);            // lst_mod_by        varchar   
 
  status = insert_queue_entry(&qe);
   
  commit();

  if (!status) {
    fprintf(stderr,"qctosf: Submit: Failure on insert\n");
  }

  fprintf(stderr,"  ( %s entry id %d )\n",fdesc,qe.entry_id);// show '  ( ' $descr ' entry id ' $entry_id ' ) ' 

  return status;

} 


// !  Quote String / Handle Apostrophes
// !  Convert ab'cd's to 'ab'\''cd'\''s'
void quote_str( char *s1, char *s2 ){
  char *search = s1, *s2write = s2;
  while (*search) {
    if (*search=='\'') {
      *s2write++='\'';  // TODO full integration testing.  
      *s2write++='\\';
      *s2write++='\'';
    }
    *s2write++=*search++;
  }
  //   move '' to $head
  //   let #p = instr( $s1, '''', 1 )
  //   while #p > 0
  //     let $head = $head || substr( $s1, 1, #p ) || '\'''''
  //     let $s1 = substr( $s1, #p + 1, length($s1)-#p )
  //     let #p = instr( $s1, '''', 1 )
  //   end-while
  //   let $s2= '''' || $head || $s1 || ''''
} // quote_str

