use strict;
use warnings;
my $f = shift @ARGV;
open my $fh, '<:encoding(UTF-8)', $f or die $!;
my $s = do { local $/; <$fh> };
$s =~ s{/\*.*?\*/}{}gs;
$s =~ s{//[^\n]*}{}g;
$s =~ s/"""[\s\S]*?"""/""/g;
$s =~ s/"(?:\\[\s\S]|[^"\\])*"/""/g;
my $depth = 0;
my $line = 1;
for my $ch (split //, $s) {
    if ($ch eq "\n") { $line++; next }
    if ($ch eq '(') { $depth++ }
    elsif ($ch eq ')') { $depth--; print "NEGATIVE depth $depth at line $line\n" if $depth < 0 }
    elsif ($ch eq '{') { $depth += 1000 }
    elsif ($ch eq '}') { $depth -= 1000 }
}
print "FINAL depth: $depth\n";
