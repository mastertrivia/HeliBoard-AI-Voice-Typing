#!/usr/bin/perl
use strict; use warnings;
my $in = shift @ARGV;
open my $fh, '<', $in or die "cannot open $in: $!";
my @langs;
my $pending = 0;
my @cur;
while (my $line = <$fh>) {
    if ($line =~ /new-instance\s+v\d+,\s*Lcom\/deshkeyboard\/translation\/b;/) {
        $pending = 1; @cur = ();
        next;
    }
    if ($pending && $line =~ /const-string\s+v\d+,\s*"([^"]*)"/) {
        push @cur, $1;
        next;
    }
    if ($pending && $line =~ /invoke-direct.*Lcom\/deshkeyboard\/translation\/b;-><init>/) {
        # code = 1st string, name = 2nd string
        if (@cur >= 2) {
            push @langs, [$cur[0], $cur[1]];
        }
        $pending = 0;
        next;
    }
}
close $fh;
print "count=" . scalar(@langs) . "\n";
for my $l (@langs) {
    print "$l->[0]\t$l->[1]\n";
}
