#!/usr/bin/env bash
# docs/05 Coroutines: a bare `runCatching` swallows CancellationException. Each Kotlin file's bare-runCatching
# lines (trimmed text, comment lines ignored) must equal its tools/cancellation-allowlist.txt entries
# (`<path> | <trimmed line>`) as a multiset, so a swapped site fails and line shifts do not.
set -euo pipefail
cd "$(dirname "$0")/.."

exec perl -e '
    use strict; use warnings; use File::Find;
    my ($src, $list) = @ARGV;
    my (%found, %allowed);
    find({ no_chdir => 1, wanted => sub {
        return unless /\.kt$/;
        my $path = $File::Find::name;
        open my $fh, "<", $_ or die "$_: $!";
        while (<$fh>) {
            next if /^\s*(\/\/|\*|\/\*)/;
            next unless /\brunCatching\b(?!Cancellable)/;
            my $line = $_;
            $line =~ s/^\s+|\s+$//g;
            $found{$path}{$line}++;
        }
    } }, $src);
    open my $al, "<", $list or die "$list: $!";
    while (<$al>) {
        next if /^\s*(#|$)/;
        s/\s+$//;
        my ($path, $line) = split / \| /, $_, 2;
        die "$list:$.: expected `<path> | <line>`\n" unless defined $line && length $line;
        $allowed{$path}{$line}++;
    }
    my $status = 0;
    for my $path (sort keys %found) {
        for my $line (sort keys %{ $found{$path} }) {
            my $extra = $found{$path}{$line} - ($allowed{$path}{$line} // 0);
            next unless $extra > 0;
            print STDERR "check-cancellation: $path: bare runCatching not allowlisted: $line -- use runCatchingCancellable in coroutine code (docs/05)\n" for 1 .. $extra;
            $status = 1;
        }
    }
    for my $path (sort keys %allowed) {
        for my $line (sort keys %{ $allowed{$path} }) {
            my $extra = $allowed{$path}{$line} - ($found{$path}{$line} // 0);
            next unless $extra > 0;
            print STDERR "check-cancellation: allowlist entry no longer matches: $path | $line\n" for 1 .. $extra;
            $status = 1;
        }
    }
    exit $status;
' app/src/main/kotlin tools/cancellation-allowlist.txt
