use strict;
use warnings;
my @files = @ARGV;
for my $f (@files) {
    open my $fh, '<:encoding(UTF-8)', $f or do { print "MISSING $f\n"; next };
    local $/; my $s = <$fh>;
    # remove block and doc comments
    $s =~ s{/\*.*?\*/}{}gs;
    # remove line comments
    $s =~ s{//[^\n]*}{}g;
    # strip triple-quoted strings
    $s =~ s/"""[\s\S]*?"""/""/g;
    # strip double-quoted strings (with escapes)
    $s =~ s/"(?:\\[\s\S]|[^"\\])*"/""/g;
    my %c;
    $c{'{'} = () = $s =~ /\{/g;
    $c{'}'} = () = $s =~ /\}/g;
    $c{'('} = () = $s =~ /\(/g;
    $c{')'} = () = $s =~ /\)/g;
    $c{'['} = () = $s =~ /\[/g;
    $c{']'} = () = $s =~ /\]/g;
    my $ok = ($c{'{'}==$c{'}'} && $c{'('}==$c{')'} && $c{'['}==$c{']'}) ? "OK " : "BAD";
    print "$ok $f  { $c{'{'}/$c{'}'}  ( $c{'('}/$c{')'}  [ $c{'['}/$c{']'}\n";
}
