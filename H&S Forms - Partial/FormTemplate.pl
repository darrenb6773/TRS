#!/usr/bin/perl
# -*- cperl -*-
use strict;
use warnings;

use HTML::FormTemplate;
use HTML::EasyTags;

use YAML;
use Perl6::Slurp;
my $stream = slurp(\*DATA);
my ($defs) = YAML::Load($stream);
my @definitions;

for my $entry (@$defs) {
  if(ref($entry->{values})) { # Force a default first option
    unshift @{$entry->{values}}, 'Please Select';
  }
  
  my $required = ($entry->{visible_title} =~ s/\*//g); # Transfer required tag from text to attribute
  if ($required) {
    push @definitions, {%$entry, is_required=>1};
    next;
  }
  push @definitions, {%$entry};
}

push @definitions, { type => 'submit' }; # Always put a submit last.
## @definitions

my $query_string = '';
read( STDIN, $query_string, $ENV{'CONTENT_LENGTH'} );
chomp( $query_string );
 
my $form = HTML::FormTemplate->new();
$form->form_submit_url( 
        'http://'.($ENV{'HTTP_HOST'} || '127.0.0.1').$ENV{'SCRIPT_NAME'} );
$form->field_definitions( \@definitions );
$form->user_input( $query_string );
 
my ($mail_worked, $mail_failed);
unless( $form->new_form() ) {
        if( open( MAIL, "|/usr/lib/sendmail -t") ) {
                print MAIL "To: helpdesk\@hannahs.co.nz\n";
                print MAIL "From: helpdesk\@hannahs.co.nz\n";
                print MAIL "Subject: A Simple Example HTML::FormTemplate Submission\n";
                print MAIL "\n";
                print MAIL $form->make_text_input_echo()."\n";
                close ( MAIL );
                $mail_worked = 1;
        } else {
                $mail_failed = 1;
        }
}
 
my $tagmaker = HTML::EasyTags->new();
 
print        "Status: 200 OK\n",
        "Content-type: text/html\n\n",
        $tagmaker->start_html( 'A Simple Example' ),
        $tagmaker->h1( 'A Simple Example' ),
        $form->make_html_input_form( 1 ),
        $tagmaker->hr,
        $form->new_form() ? '' : $form->make_html_input_echo( 1 ),
        $mail_worked ? "<p>Your favorites were emailed.</p>\n" : '',
        $mail_failed ? "<p>Error emailing your favorites.</p>\n" : '',
        $tagmaker->end_html;
__DATA__
- { visible_title: Brand, type: popup_menu, name: brand, values: [RH, NOS, NG] }
- visible_title: "Location of person reporting"
  type: popup_menu
  name: location
  values:
    - Please select
    - 001 Hannahs DC
    - 010 Whangarei Hannahs
    - 017 Albany Hannahs
    - 018 Albany Hush Puppies
    - 021 Queen St Hannahs
    - 025 West City Hannahs
    - 028 West City Hush Puppies
    - 030 Northwest Hannahs
    - 035 Lynn Mall Hannahs
    - 037 St Lukes Hannahs
    - Takapuna Support Hub
    - Distribution Centre
    - Regional Manager
- { visible_title: "Full name of person reporting*", type: textfield, name : reporter}
- { visible_title: "Position of person reporting", type: popup_menu, name: position, values: [Assistant Manager, Support Manager, Keyholder, Senior Team Member, Team member, Support Hub, Distribution Centre, Regional Manager]}
- { visible_title: "Details of Person Injured*", name: injured_details }
- { visible_title: "Date of injury*", name: injury_date }
- { visible_title: "Time of injury*", name: injury_time }
- visible_title: "Position of injured person "
  type: popup_menu
  name: injured_posn
  values:
   - Store Manager
   - Assistant Manager
   - Support Manager
   - Keyholder
   - Senior Team Member
   - Team member
   - Support Hub
   - Distribution Centre
   - Regional Manager
   - Customer
- { visible_title: "Injured person's status", type: popup_menu, name: injured_status, values: [Full time, Part time, fixed term, casual, contractor, customer] }
- { visible_title: "Length of service", type: popup_menu, name: los, values: ['1st month', '2-6 months', '6-12 months', '1-2 years', '3-5 years', '5-10 years', 'over 10 years'] }
- { visible_title: "Full name of injured person *" }
- { visible_title: "Contact details of injured person" }
- { visible_title: "Details of Injury" }
- { visible_title: "What was the activity being completed at the time of injury? *" }
- { visible_title: "Where in the workplace did the injury occur? *" }
- { visible_title: "Describe exactly how the injury occurred *" }
- { visible_title: "Which body part(s) sustained the injury? *" }
- { visible_title: "Description of injury", type: popup_menu, name: injury_descr, values: ['Bruise/contusion', 'Broken bone', 'Concussion / head injury', 'Cut/open wound', 'Abrasion/graze', 'Joint dislocation', 'Puncture wound', 'Overuse injury', 'Stress fracture', 'Sprain/strain', 'Foreign body', 'Dental', 'Poisoning / toxic effect', 'Uncertain', 'Other'] }
- { visible_title: "What initial treatment was received? *" }
- { visible_title: "People whom you have reported this injury to *   " }
- { visible_title: "Was this injury reported immediately? Yes / No- provide further details *" }
- { visible_title: "Full name of witness to the injury " }
- { visible_title: "Duty/Store Manager at time of injury *" }
- { visible_title: "Additional Information " }
- { visible_title: "Cause of injury*", type: popup_menu, name: cause, values: [behavioural, training, environmental, equipment failure,  manual handlings] }
- { visible_title: "Any other additional comments " }
- { visible_title: "Corrective actions taken*" }
- { visible_title: "Follow up training completed / required?" }
