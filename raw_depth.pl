use strict;
use warnings;
my $f = shift @ARGV;
open my $fh, '<:encoding(UTF-8)', $f or die $!;
my @lines = <$fh>;
my $depth = 0;
for my $i (0 .. $#lines) {
    my $line = $lines[$i];
    my $n = $i + 1;
    for my $ch (split //, $line) {
        if ($ch eq '(') { $depth++ }
        elsif ($ch eq ')') {
            $depth--;
            if ($depth < 0) {
                print "NEGATIVE at original line $n: $line";
                $depth = 0;
            }
        }
    }
}
print "FINAL depth: $depth\n";
