#!/usr/bin/perl
use strict; use warnings;
my $in = shift @ARGV;
open my $fh, '<', $in or die "cannot open $in: $!";
my %regs;
my @pending;   # list of registers that have a pending new-instance of translation/b
my @langs;
while (my $line = <$fh>) {
    if ($line =~ /const-string\s+(v\d+),\s*"([^"]*)"/) {
        $regs{$1} = $2;
    } elsif ($line =~ /const\/4\s+(v\d+),\s*(0x[0-9a-f]+|-?\d+)/) {
        $regs{$1} = $2;
    } elsif ($line =~ /(?:move|move\/from16|move-object|move-object\/from16)\s+(v\d+),\s*(v\d+)/) {
        $regs{$1} = $regs{$2} if exists $regs{$2};
    } elsif ($line =~ /new-instance\s+(v\d+),\s*Lcom\/deshkeyboard\/translation\/b;/) {
        push @pending, $1;
    } elsif ($line =~ /invoke-direct\s+\{(.+)\},\s*Lcom\/deshkeyboard\/translation\/b;-><init>\(Ljava\/lang\/String;Ljava\/lang\/String;Ljava\/lang\/String;Z\)V/) {
        my @args = split /,\s*/, $1;
        my $recv = shift @args;
        # recv must be a pending new-instance
        if (grep { $_ eq $recv } @pending) {
            @pending = grep { $_ ne $recv } @pending;
            my ($code, $name) = (undef, undef);
            if (@args >= 2) {
                $code = $regs{$args[0]} // "";
                $name = $regs{$args[1]} // "";
            }
            push @langs, [$code, $name];
        }
    }
}
close $fh;
print "count=" . scalar(@langs) . "\n";
for my $l (@langs) {
    print "$l->[0]\t$l->[1]\n";
}
