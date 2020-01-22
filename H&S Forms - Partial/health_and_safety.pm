
# -*- cperl -*- 
package health_and_safety;

use strict;

#use DBI;
#use DBD::Sybase;
#use Smart::Comments;

use CGI::FormBuilder;

=pod

=head1 health_and_safety.pm module

=head2 Central modules for all H&S entry pages (Injury, Near Miss, Hazard, ...)

=head3 connection_opts() 

 Returns a hash of connection options (includes SMTP settings)

=cut


sub connection_opts() {
  return (
	  databaseHost        => "nossqlsvr.shubar.co.nz",
	  databaseName        => "NGA_Reporting",
	  userName            => "nga_reports" ,
	  password            => "y72vPT7VLK4KgnrBLnkO", 
	  smtpHost            => "nosexchangesvr.shubr.co.nz",
	  smtpSendFromAddress => "reports\@ngahuiagroup.nz"
	 );
}

=head3 connect(%) Takes the above connection options and returns a DB Handle

=cut

sub connect(%) {
  my %opts = @_;
  my $databaseHost = $opts{databaseHost};
  my $databaseName = $opts{databaseName};
  my $dbh=  DBI->connect("DBI:Sybase:server=$databaseHost;database=$databaseName",
		      $opts{userName}, $opts{password},
		      { syb_deadlock_retry    => 10,
			syb_deadlock_verbose  => 1,
			syb_no_child_con      => 1,
			AutoCommit            => 1,  # Always appears to autocommit
			PrintError            => 1   # Suppress disconnect errors
		      });
  die "Failed to connect to $databaseHost/$databaseName" unless $dbh;
  $dbh->syb_date_fmt("ISO") or die "Set DateFormat Failed";
  return $dbh;
}

sub new_form($$%) {
  my ($field_list, $dbh, %opts)= @_;

  my $form = CGI::FormBuilder->new(
				   %opts,
				   method => 'POST',
				   header => 1,
				   messages => { form_other_default => 'No:',
					       },
				   cgi_param => ['access'],
				   fieldattr => { autocomplete=>'off'},
				   stylesheet => '/health_and_safety.css', #aka html folder

			);
  my @required_fields;
  for my $afield (@$field_list) {
### $afield
    my ($name) = sort keys %$afield;
    if ($name =~ m/^_/){ # pair of '_name: label'
      $afield->{label} = $afield->{$name};
      delete $afield->{$name};
      $name =~ s/^_//g;
      $afield->{name} = $name;
    }
    
    my %extras;
    %extras = (options => 
	       sub{return location_list($dbh)}) if $afield->{name} eq 'location';
    %extras = (options =>  \&risk_list) if $afield->{name} eq 'rating';
    $form->field(%$afield,%extras);

    # not IF, just not required. Required by default
    ## req: $afield->{required}
    my $this_required = $afield->{required};
    $this_required = 1 unless defined $this_required;
    ## $this_required
    push @required_fields, $afield->{name} if $this_required
  }
  ### @required_fields
  $form->required(\@required_fields);
  return $form;
    
}

=head2 Database centric function

SQL is a handle of type SQL::Abstract

=head3 select($$$) DB,Table,ID

=head3 insert($$$$) DB, SQL, Table, Entry

=head3 update($$$$$) DB, SQL, Table, Entry, ID

Simple calling of these workers are key (eg if select is called without a valid ID, nothing returns)

=cut

sub db_select($$$) {
  my ($dbh, $table, $DBID) = @_;
  return unless defined $DBID;
  my $row_hashref = $dbh->selectrow_hashref("select * from $table where DBID = $DBID");
  return $row_hashref;
}

sub populate_form($$$){
  my ($dbh,$form,$table) = @_;
  my $db_data = db_select($dbh,$table, $form->cgi_param('DBID'));
  if ($db_data and %$db_data) {
    while (my ($key, $val) = each %$db_data ) {
      $form->field(name => $key, value=> $val);# if defined $val;
    }
    return 1;
  }
  return undef;
}

# Bind edition of execute WORKS for Sybase/MSSQL!
#use prepare/execute/finish syntax for easy changes
sub db_insert($$$$){
  my ($dbh,$sql, $table,$entry) = @_;

  my %record = %$entry;
  delete $record{photofile};

  my ($stmt,@bind ) = $sql->insert($table,\%record);
  my $insertH = $dbh->prepare($stmt);
  return undef unless $insertH;
  my $rv = $insertH->execute(@bind);
  unless ($rv) {
    print "$table insert failed $!";
    return undef;
  }
  $insertH->finish();
  return 1;
#  print "Database insert:$stmt,".join(',',@bind);
}

sub db_update($$$$$){
  my ($dbh, $sql, $table, $entry, $DBID) = @_;

  my %record = %$entry;
  delete $record{photofile};
  my ($stmt,@bind ) = $sql->update($table,\%record, {DBID => $DBID});
  my $insertH = $dbh->prepare($stmt);
  unless ($insertH) {
    print "$table update filed $!";
    return undef;
  }
  my $rv = $insertH->execute(@bind) or print "<h1>execute splat</h1>"; 
  $insertH->finish();
  return 1 
}

sub location_list($) {
  return ['001 Hannahs DC',
   	    '010 Whangarei Hannahs',
   	    '017 Albany Hannahs',
   	    'Takapuna Support Hub',
   	    'Distribution Centre',
   	    'Regional Manager'];

  my ($dbh) = @_;
  my $rows = $dbh->selectall_arrayref(<<SQL);
    SELECT branch from nga_reporting.dbo.branch
    WHERE  trading_name not in ('Not Trading','Closed')
    AND    regional_manager not in ('Closed','Other Hannahs')
    AND    branch not like '% 2'
    ORDER BY branch;
SQL
  my @options= map {$_->[0]} @$rows;
  push @options, 'Takapuna Support Hub';
  push @options, 'Regional Manager';
  return \@options;
}


sub risk_list() { # Distinct results of 1-5 x 1-5
  my @levels = ( [ 'Low Risk'                    => [1 ,2, 3]               ],
		 [ 'Moderate Risk (Significant)' => [4, 5, 6, 8, 9, 10, 12] ],
		 [ 'Unacceptable Risk'           => [15, 16, 20, 25]        ],
	       );
  my @possibles;
  for my $level (@levels) {
    my ($label, $numbers) = @$level;
    push @possibles , [$_ => $_.' => '.$label] foreach @$numbers;
  }
  return [@possibles];
}

return 1; # like all good modules

