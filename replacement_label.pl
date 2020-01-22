# -*- cperl -*-
use strict;
use File::Copy;
use DBI qw(:sql_types);
use Glib qw/TRUE FALSE/;
use Gtk2 '-init';
#use Gtk2::SimpleList;
#use lib ('c:/rpts');

######################################## Main ######################################

#Remember the validated stock number
my $currentStockNumber = '';

#Connect to local database
my $DSN="Driver={SQL Server}; Server=127.0.0.1\INFINITY;Database=AKPOS;";
my $dbh;
$main::dbh ||= DBI->connect("dbi:ODBC:$DSN", 'sa','abcd1234') or die "$DBI::errstr\n";
$main::dbh->do("Set DateFormat DMY");

#Create a window
my ($MainWindow , $box1 ) = addwindow("toplevel","Replacement labels");
$MainWindow->move(120,30);

#Hide the cmd window
use Win32::GUI;
my ($DOSwind,$DOSInst) = GUI::GetPerlWindow();
GUI::Hide($DOSwind) unless -e 'c:\showguilog.txt';

#Add a entrybox for the stk number
my $stkEntry = Gtk2::Entry->new_with_max_length(7);
$stkEntry->signal_connect('focus-out-event'=>\&stkNumChanged);
$stkEntry->set_size_request(55,-1);
$stkEntry->grab_focus();

#Add a entry field for model, disable user input
my $modelEntry = Gtk2::Entry->new_with_max_length(40);
$modelEntry->set_editable(FALSE);

#Add a combo box, with the text 'size', creating a variable to track the number of items in the comboBox
my $sizeListLength = 1;  
my $sizeComboBox = Gtk2::ComboBox->new_text();
my @sizeValues;
$sizeComboBox->append_text('SIZE');
$sizeComboBox->set_active(0);
$sizeComboBox->signal_connect(changed=> \&printone);

#when the enter key is pressed move the focus, TODO expand comboBox Alt + Down/Up expands
$stkEntry->signal_connect("key_press_event" => sub {
        use Gtk2::Gdk::Keysyms;
        my $key = {reverse %Gtk2::Gdk::Keysyms}->{$_[1]->keyval};
        if($key =~ m/^(KP_Enter)|(Return)$/) {
						$modelEntry->grab_focus();			
			  }
      } );

#Add a new horizontal box containg stkEntry modelEntry SizeCB qtyEntry
my $HEntries = Gtk2::HBox->new(FALSE, 0);
$box1->pack_start ($HEntries,TRUE,TRUE,10);
$HEntries->pack_start($stkEntry,TRUE,TRUE,5);
$HEntries->pack_start($modelEntry,TRUE,TRUE,5);
$HEntries->pack_start($sizeComboBox,TRUE,TRUE,5);
#$HEntries->pack_start($qtyEntry,TRUE,TRUE,5);

#Add a horizontal box to contains buttons
my $HButtonStrip = Gtk2::HBox->new(FALSE, 0);
$box1->pack_end ($HButtonStrip,TRUE,TRUE,5);

AddTemplateButton('quit');

#Show all items
$MainWindow->show_all;
Gtk2->main;
0;

######################################## Functions ######################################

#The stock entry field has been changed, update the model entry field
sub stkNumChanged() {
	#Get the text from the stkEntry and ensure it is uppercase
	my $stkNum = $stkEntry->get_text();
	$stkNum = uc ($stkNum);
  $stkEntry->set_text($stkNum);
	
	#warn "stkNumChanged called\n";
	
	#For all items but default, remove from size comboBox
	for(my $i = $sizeListLength; $i >= 1; $i--){
		$sizeComboBox->remove_text($i);
    pop(@sizeValues);               # Remove value from sizeValues cache also
		$sizeListLength--;
	}
	
	#If stkNum looks like a stock number
	if($stkNum =~ m/^([A-Z]{2,3}\d{4})$/){
		#warn "Stock number entry changed to $stkNum\n";
		
		#Get all skus and descriptions for the stkNum
		my $skus =  $main::dbh->selectall_arrayref(<<SQL);
		select sku, description, max(upc) as checkdigit
		from items
		where SKU like '${stkNum}%'
    group by sku, description
		order by 1
SQL

		#If there is a Sku, set the modelEntry field, if not say so an clear entrys
		if ($skus and @$skus){ $modelEntry->set_text($skus->[0][1]); }
		else { 
			clearEntry();
			$modelEntry->set_text("Invalid Stock Number Selected");
			return 0;
		}
		
		#Remember the stock number for when add is pressed to avoid unvalidated stock numbers
    $currentStockNumber = $skus->[0][2];
    ($currentStockNumber,undef) = split (' ',$currentStockNumber);
    
		#For all SKU's starting with stockNum add all sizes to the size comboBox
		for my $SKU (@$skus){
			#Split into StkNum, $size
			my ($stkNum, $size) = split(' ',$SKU->[0]);
			#Size is UNS if no size
			$size = 'UNS' if $size eq '';

			#Add the size to the sizeComboBox
			$sizeComboBox->append_text($size);
			
			#Add the suze to the sizeValues array
			push @sizeValues, $size;

			#Increment the sizeListLength
			$sizeListLength++;
			
			#if the size added matches the current selection, set it active
		}
		
		$sizeComboBox->set_active(0); 

		#If there is only 1 item in the sizeComboBox, set it active, else set the default active
		if (scalar(@$skus) == 1){ $sizeComboBox->set_active(1); }
	}
	#Else
	else{
		#Clear all input items
		clearEntry();
		if($stkNum) { $modelEntry->set_text("Invalid Stock Number Selected"); }
		else { $modelEntry->set_text("No Stock Number Selected"); }
	}
		
	return 0; # What does the return do? it is required or the focus out crashes
}

#Add stock to the list
sub add(){
	#Get the stkNum, model, size and quantity
	my $stkNum = $stkEntry->get_text();
	$stkNum = uc ($stkNum);
	
	#if the text in the stkEntry field hasn't been validated do so
	if($currentStockNumber ne $stkNum){ stkNumChanged(); }
	
	my $model = $modelEntry->get_text();
	my $size = $sizeValues[$sizeComboBox->get_active - 1];		#TODO size selected if no size picked, acounted for however on line 282 and 315
	my $added = FALSE;
	
	warn "add called\n";


	#Store sql result in a variable, Max existing ID + 1
	
  my $qty = 1;
	
	#If the entry is a valid model, size was picked and there is a quantity
	if($model && $sizeComboBox->get_active != 0 ){
		my $sku = $stkNum . " ";						#TODO extra space on the end for unsized???
		if($size ne 'UNS'){ $sku = $sku . $size; }
		
		#For qty add a row to the database
		for(my $i = 0; $i < $qty; $i++){
			#Add the item to the list
			#push @{$slist->{data}}, [$id, $stkNum, substr($model, 0, 27), $size, 1];

		}
		
		#Reset remembered stock number
		$currentStockNumber = "";
		
		#update table
		getStock();
		
		$added = TRUE;
	}
	
	#Clear all input items
	clearEntry();
	
	#If invalid inputs tell the user
	if(!$stkNum){ $modelEntry->set_text("No Stock Number Selected");}
	elsif($model eq '' or $model eq 'Invalid Stock Number Selected'){ $modelEntry->set_text("Invalid Stock Number Selected");}
	elsif($qty < 0 || $qty > 50){ $modelEntry->set_text("Invalid Quantity");}
	elsif($qty eq ''){ $modelEntry->set_text("No Quantity Selected");}
	elsif($sizeComboBox->get_active == 0){ $modelEntry->set_text("No Size Selected");}	#After clearEntry will be true
	
	#if items added tell the user
	if($added){ $modelEntry->set_text("Item Added");}
}

#Clear all input items
sub clearEntry(){
	$stkEntry->set_text("");
	$modelEntry->set_text("");
	
	#For all items but default, remove from size comboBox
	for(my $i = $sizeListLength; $i >= 1; $i--){
		$sizeComboBox->remove_text($i);
		
		#Remove value from sizeValues
		pop(@sizeValues);
	}
	
	#Set the default value active
	$sizeComboBox->set_active(0);
	
	#Reset comboBox length
	$sizeListLength = 1;
}




#Quit the GTK application
sub quit() {
	Gtk2->main_quit;
}

#Add a window
sub addwindow($$){
	my $wintype = shift;
	my $winname = shift;
	my $NewWindow = Gtk2::Window->new($wintype);
	$NewWindow->set_title($winname);
	$NewWindow->signal_connect(delete_event => \&delete_event,$NewWindow);
	$NewWindow->signal_connect(destroy      => sub { Gtk2->main_quit; });
	my $box1 = Gtk2::VBox->new(FALSE, 0);
	$box1->show();
	$NewWindow->add($box1);
	$NewWindow->set_border_width(10);
	return ($NewWindow ,$box1);
}


#Add a button using a GTK template
sub AddTemplateButton($) {
	my $btn = shift;
	
	#Add a new button from a GTK template
	my $this_btn = Gtk2::Button->new_from_stock ('gtk-'.$btn);
	$this_btn->signal_connect(clicked => \&$btn);
	$this_btn->set_size_request(80, 50);
	$this_btn->set_focus_on_click(0);
	$HButtonStrip->pack_start($this_btn, TRUE,TRUE,5);
	$this_btn->show;
}

sub printone() {
  return if $sizeComboBox->get_active == 0;
  my $chr29   = chr(29);
  my $NorSize = chr(27)."@";
  my $DubSize = $chr29.chr(33).chr(17);
  my $TrpSize = $chr29.chr(33).chr(34);
  my $MyCut   = $chr29.chr(86).chr(66).chr(20);

  my $size = $sizeValues[$sizeComboBox->get_active - 1];
  my $barcode  = $currentStockNumber;
  $barcode .= ' '.$size unless $size eq 'UNS';
  my $barcodeLength = length($barcode );
  my $temp = 'c:\rpts\printme.txt';
  my $jnl = 'c:\rpts\printme_JNL.txt';
  my $Model = $modelEntry->get_text();
  $Model =~ s/\).*$/\)/;
  open (my $PRN, '>', $temp) or die $!;
  binmode($PRN);
  print $PRN $NorSize."\n";
  print $PRN $chr29,"H".chr(0).$chr29."kI". chr($barcodeLength + 2). "{A". $barcode.chr(0)."\n";
  print $PRN $TrpSize;
  print $PRN $barcode."\n";
  print $PRN $DubSize;
  print $PRN "\n".$Model."\n";
  print $PRN $NorSize."\n";
  print $PRN $MyCut;
  close $PRN;
  copy($temp,'LPT1');
  open (my $JNL, '>>', $jnl) or die $!;
  print $JNL "$currentStockNumber,$size,$barcode\n";
  close $JNL;
	$stkEntry->grab_focus();
  }
1;
