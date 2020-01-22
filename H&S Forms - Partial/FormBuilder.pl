#!/usr/bin/perl

use CGI::FormBuilder;

my $form = CGI::FormBuilder->new(
                source => 'myform.conf'   # form and field options
           );

# Check to see if we're submitted and valid
if ($form->submitted && $form->validate) {
    # Get form fields as hashref
    my $field = $form->fields;
 
    # Do something to update your data (you would write this)
    do_data_update($field->{lname}, $field->{fname},
                   $field->{email}, $field->{phone},
                   $field->{gender});
 
    # Show confirmation screen
    print $form->confirm(header => 1);
} else {
    # Print out the form
    print $form->render(header => 1);
}
