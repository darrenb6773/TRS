#!/usr/bin/perl

use strict;
use CGI qw/:standard/;
use Perl6::Slurp;

my $dictionary = '/usr/share/myspell/en_NZ.dic';
open(my $DICT,'<' , $dictionary);
my @words = slurp ($DICT);
close $DICT;

my $word_count = $#words;
my $retry_limit = 500;
my ($retries,@password_list);

print header(), start_html,
    h1('Random pasword generator'),
    h2('Selection of dictionary words of length 4-9');

for my $options (1..10) {
    my @selection;
    for my $repeat (1..4) {
	my $ind = int(rand($word_count));
	my ($word,$suffix) = split('/',$words[$ind]);
	chomp($word);
	redo unless $suffix or $retries++ > $retry_limit;
	redo unless $word =~ m/^[a-zA-Z]{4,9}$/ or $retries++ > $retry_limit;
	$word = uc($word) if int (rand(1.7)); #slightly prefer lower case  10:7 ratio
	push @selection,$word;
    }	
    push @password_list, join('',@selection);
}
print ul(li(\@password_list)),end_html;

