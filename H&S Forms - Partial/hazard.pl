#!/usr/bin/perl
# -*- cperl -*-
#use Smart::Comments;
#use DBI;
use lib '.';



use strict;
use health_and_safety;
my %opts = health_and_safety::connection_opts();
my $dbh  ;#= health_and_safety::connect(%opts);


use SQL::Abstract;
my $sql = SQL::Abstract->new;
my $table = 'hs_hazard';

use YAML;
my ( $field_list, $setup, $admin) = YAML::LoadFile('./hazard.yaml');

my $form = health_and_safety::new_form($field_list, $dbh, %$setup );

# Check to see if we're submitted and valid
if ($form->submitted && $form->validate) {
    # Get form fields as hashref
    my $field = $form->fields;
    
    # Show confirmation screen
    print $form->confirm(header => 1);
} else {
    # Print out the form
    print $form->render(header => 1, sticky => 0);
}

