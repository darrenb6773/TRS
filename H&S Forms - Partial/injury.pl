#!/usr/bin/perl

use lib '.';

use CGI::FormBuilder;

my $form = CGI::FormBuilder->new(
    source => 'injury.conf',   # form and field options
    messages => {
	form_other_default => 'No:',
    }
    );

# Check to see if we're submitted and valid
if ($form->submitted && $form->validate) {
    # Get form fields as hashref
    my $field = $form->fields;
 
#    # Do something to update your data (you would write this)
#    do_data_update($field->{lname}, $field->{fname},
#                   $field->{email}, $field->{phone},
#                   $field->{gender});

    # Show confirmation screen
    print $form->confirm(header => 1);
} else {
    # Print out the form
    print $form->render(header => 1, sticky => 1);
}

sub location_list(){
    return ['001 Hannahs DC',
	    '010 Whangarei Hannahs',
	    '017 Albany Hannahs',
	    '018 Albany Hush Puppies',
	    '021 Queen St Hannahs',
	    '025 West City Hannahs',
	    '028 West City Hush Puppies',
	    '030 Northwest Hannahs',
	    '035 Lynn Mall Hannahs',
	    '037 St Lukes Hannahs',
	    'Takapuna Support Hub',
	    'Distribution Centre',
	    'Regional Manager'];
   
}
