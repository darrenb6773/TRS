#!/usr/bin/perl
# -*- cperl -*-

use strict;
#use Smart::Comments;
#use DBI;
use lib '.';

use health_and_safety;
my %opts = health_and_safety::connection_opts();
my $dbh  ;#= health_and_safety::connect(%opts);


use SQL::Abstract;
my $sql = SQL::Abstract->new;
my $table = 'hs_near_miss';


use CGI::FormBuilder;
my $form = CGI::FormBuilder->new(
				 name       => 'NearMiss',
				 styleclass => 'NearMissForm',
				 source     => 'nearmiss.conf',  # form and field options
				 messages   => {
						form_other_default => 'No:',
					       },
				 cgi_param  => ['access'],
				 fieldattr  => { autocomplete => 'off' }, # No suggesting or stores will reuse the same phrases

				 enctype    => 'multipart/form-data',
				);
my @buttons = ('Submit','Cancel');
unshift @buttons , 'Update' if 0;
my $required = 'ALL';

# ################################################################################
# Form mode custom attributes
if ($form->submitted eq 'Retrieve' ){   # ##  my $field =$form->fields; 
  if (health_and_safety::populate_form($dbh,$form,$table)) {
    shift @buttons;  # Replace Submit with Update
    push @buttons, 'Update';
  } else {
    push @buttons, 'Query';
  }
  $form->field(name =>'DBID',disabled=>1);
}

if ($form->submitted eq 'Query' or $form->cgi_param('access') eq 'Query') {

  $form->field(name =>'DBID',label=>'Database ID');
#  $form->field(name =>'filename',type =>'file');#, htmlattr=>'accept="image/*"');
  $form->required(['DBID']); # If providing fields, must be an array-ref not scalar
  @buttons = ('Retrieve','Cancel');
}

push @buttons , 'Query'  if $form->cgi_param('access') eq 'admin';

# ################################################################################
# Baseline form

$form->submit(\@buttons);
#$form->required($required) unless $required eq 'ALL'; # 'unless' prevents optional items loosing their attribute

# ################################################################################
#  Submitted form checks (Submit/Update)
if ($form->submitted eq 'Submit' && $form->validate) { # Updates only
  my $rv= health_and_safety::db_insert($dbh,$sql,
			    $table,$form->fields);

  my $photofile = $form->field('photofile');
  warn __LINE__;
   if ($photofile) {
     warn __LINE__;
     # save contents in file, etc ...
     # BTW don't even bother trying to use tmp, 
     # it just resolves to a hidden clone /usr/tmp/systemd-private-*-httpd.service-* 
     # for 'safety'
     my $dir = '/data/hs_images'; 

     my $file = '1.jpg';
     warn "writable" if -d $dir;
     warn "writing to $dir/$file";
     open(my  $F, '>', "$dir/$file") or die $!;
     binmode $F;
     while ( my $block = <$photofile>) {
       warn __LINE__;
       print $F $block; 
     }
     close $F;
   }
  
  print $form->confirm(header => 1) if $rv;
  
} elsif ($form->submitted eq 'Update' && $form->validate) {
  my $rv = health_and_safety::db_update($dbh,$sql,
				     $table,$form->fields,$form->cgi_param('DBID'));
  print $form->confirm(header => 1) if $rv;
} else {
  print $form->render(header => 1, sticky => 0);  # Print out the default form
}

# ################################################################################
# Workers
sub risk_list() { # Distinct results of 1-5 x 1-5
  return health_and_safety::risk_list();
}


sub location_list() {
  return  health_and_safety::location_list($dbh);
}
