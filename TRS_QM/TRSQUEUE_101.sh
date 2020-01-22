
static char *stdid_TRSQUEUE_10101_sh = "IDHFR TRSQUEUE_10101.sh D.1 20.07.2007";


/* Modification History
 * 20.07.2007 D.1 DJB Created for common modules relating to the queue manager   
 *
 */  

/* Queue(Q) Queue_type */
#define QT_PRINTER     'P'   /* Queue only holds print jobs                                 */
#define QT_BATCH       'B'   /* Queue only holds batch jobs                                 */

EXEC SQL begin declare section;

/* qctosf action id's */
#define QCTOSF_OPEN       1   /* add OSF entry                                               */
#define QCTOSF_STDCLOSE   2   /* Mark file as complete check if auto print                  */
#define QCTOSF_REPRINT    3   /* reprint an OSF entry                                        */
#define QCTOSF_SUBMIT     4   /* submit to batch queue                                       */
#define QCTOSF_LP         5   /* submit to print queue                                       */
#define QCTOSF_PRINTED    6   /* mark in OSF as printed                                      */
#define QCTOSF_PRINTQE    7   /* reprint queue entry if auto-print                           */
#define QCTOSF_DELETE     8   /* Mark file as Deleted  delete it                            */
#define QCTOSF_ERROR      9   /* Mark file as Dubious (Error) don't print                   */
#define QCTOSF_CHMOD     10   /* Chmod file without OSF_close                                */
#define QCTOSF_OPENCLOSE 11   /* open & close file at once                                   */
#define QCTOSF_JUSTCLOSE 12   /* Mark file as Complete but don't print                      */

/* Operating_system_file(OSF) file_status */
#define OSF_FS_INCOMPLETE   1 /* file being created                                          */
#define OSF_FS_COMPLETE     2 /* file sucessfully written                                    */
#define OSF_FS_ERROR        3 /* file exists but contents dubious                            */
#define OSF_FS_DELETED      4 /* file not found in Unix directory                            */
#define OSF_FS_MISSING      5 /* file not found in Unix directory                            */

/* Queue(Q) Queue_status */
#define Q_OPEN           1   /* accept entries, activate normally                           */
#define Q_CLOSED         2   /* do not accept entries                                       */
#define Q_ERROR          3   /* accept entries? but do not activate get operator            */
#define Q_HOLD           4   /* accept entries but do not activate                          */


/* Queue_entry(QE) Entry_status */
#define QE_NOLAUNCH      1   /* Failed to Launch                                            */
#define QE_CANCELLED     2   /* Was cancelled, either before launch or marks failed as seen */
#define QE_SUBMITTED     3   /* Submitted to the queue and requested for future             */
#define QE_HOLD          4   /* Reserved                                                    */
#define QE_PENDING       5   /* Request time has passed, but not started yet                */
#define QE_QUEUED        6   /* Reserved                                                    */
#define QE_ACTIVE        7   /* Currently being processed                                   */
#define QE_SUCCESS       8   /* Completed successfully                                      */
#define QE_ERROR         9   /* Completed with Error                                        */
#define QE_FASTSUBMIT   23   /* Submitted to FAST queue database triggers execution         */
EXEC SQL end declare section;

int next_entry_id();
int commit();
int rowcount();

int insert_queue_entry();//struct queue_entry_ *qe

