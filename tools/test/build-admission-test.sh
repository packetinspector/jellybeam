#!/bin/bash
# Tests tools/build-admission.sh with a fake docker on PATH (docs/06 "Build admission").
# Runs under /bin/bash to hold build.sh's bash 3.2 floor. Exit 0 when every case passes.
set -u
cd "$(dirname "$0")/../.." || exit 2
ROOT=$PWD
T=$(mktemp -d "${TMPDIR:-/tmp}/admission-test.XXXXXX")
trap 'pkill -P $$ 2>/dev/null; rm -rf "$T"' EXIT
mkdir -p "$T/bin" "$T/stop"

# Fake docker: `compose up` succeeds, `compose exec ... cat /proc/meminfo` reports $T/free_mb,
# a daemon stop is logged with the slot count at that moment, `stats` lists two containers.
cat > "$T/bin/docker" <<EOF
#!/bin/bash
case "\$*" in
  *meminfo*) echo "MemAvailable: \$(( \$(cat "$T/free_mb") * 1024 )) kB" ;;
  *gradlew\ --stop*otlinCompileDaemon*) echo "stop slots=\$(ls -d "$T/slots"/slot.* 2>/dev/null | wc -l | tr -d ' ')" >> "$T/docker.log" ;;
  stats*) printf '%s\n' '112MiB / 7.8GiB small-wt-android-build-arm64-1' '3.8GiB / 7.8GiB big-wt-android-build-arm64-1' ;;
esac
exit 0
EOF
chmod +x "$T/bin/docker"
export PATH="$T/bin:$PATH" SERVICE=svc JELLYBEAM_BUILD_SLOTS_DIR="$T/slots"
export JELLYBEAM_BUILD_POLL_S=0.2 JELLYBEAM_BUILD_REPORT_S=1 JELLYBEAM_BUILD_BUDGET_MB=4096
unset JELLYBEAM_BUILD_SLOT JELLYBEAM_BUILD_CAPPED JELLYBEAM_BUILD_QUEUE JELLYBEAM_MAX_BUILDS

fails=0
check() { # name, expected, actual
    if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1: expected '$2', got '$3'"; fails=$((fails + 1)); fi
}
free() { echo "$1" > "$T/free_mb"; }

# holder <name> [max_wait_min]: a separate process (its own $$) that acquires, logs
# "<name> capped=<0|1>" or "<name> rc=<n>", then holds its slot until $T/stop/<name> exists.
holder() {
    JELLYBEAM_BUILD_MAX_WAIT_MIN="${2:-1}" /bin/bash -c '
        . tools/build-admission.sh
        admission_acquire "$1"; rc=$?
        if [ $rc -ne 0 ]; then echo "$1 rc=$rc" >> "$2/log"; exit $rc; fi
        echo "$1 capped=$JELLYBEAM_BUILD_CAPPED" >> "$2/log"
        while [ ! -e "$2/stop/$1" ]; do sleep 0.1; done' _ "$1" "$T" 2>>"$T/err" &
}
wait_log() { # line, timeout s
    local i=0
    until grep -qx "$1" "$T/log" 2>/dev/null; do i=$((i + 1)); [ $i -gt $(($2 * 10)) ] && return 1; sleep 0.1; done
}
release() { touch "$T/stop/$1"; }
slots() { ls -d "$T/slots"/slot.* 2>/dev/null | wc -l | tr -d ' '; }

. tools/build-admission.sh

# The pure decision.
check "decide idle, solo budget"     admit  "$(admission_decide 7000 0 6144 4096 2)"
check "decide idle, capped budget"   capped "$(admission_decide 5000 0 6144 4096 2)"
check "decide idle, low"             wait   "$(admission_decide 1000 0 6144 4096 2)"
check "decide one running, enough"   capped "$(admission_decide 9000 1 6144 4096 2)"
check "decide one running, low"      wait   "$(admission_decide 1000 1 6144 4096 2)"
check "decide max reached"           wait   "$(admission_decide 99999 2 6144 4096 2)"
check "decide max 1"                 wait   "$(admission_decide 99999 1 6144 4096 1)"

# Idle but low memory (an un-queued build elsewhere): even the first build waits.
free 1000
holder z 0; wait_log "z rc=75" 5; check "idle, low memory waits" 0 $?
grep -q "gave up on z .*: VM free 1000 MB < 4096 MB capped budget" "$T/err"; check "idle wait line names memory" 0 $?

# Idle with the capped budget only: admitted capped.
free 5000
holder y; wait_log "y capped=1" 5; check "idle, capped budget admits capped" 0 $?
release y; sleep 0.5

# Idle with the solo budget: admitted uncapped.
free 7000
holder a; wait_log "a capped=0" 5; check "idle admits uncapped" 0 $?

# One running + enough memory: admitted capped.
free 6000
holder b; wait_log "b capped=1" 5; check "second admits capped with memory" 0 $?

# Max reached (2 running): a third waits, then gives up with rc 75.
holder c 0; wait_log "c rc=75" 5; check "max reached waits then fails" 0 $?
grep -q 'gave up on c .*2/2 builds running' "$T/err"; check "give-up line names the holders" 0 $?
release b; sleep 0.5

# One running + low memory: waits, printing what it waits on.
free 1000
holder d 0; wait_log "d rc=75" 5; check "second waits on low memory" 0 $?
grep -q "running: .* a (0m); VM free 1000 MB < 4096 MB capped budget" "$T/err"; check "wait line names holder + memory" 0 $?

# Stale slot: a dead pid's slot is reclaimed, the next build runs solo.
release a; sleep 0.5; free 7000
mkdir -p "$T/slots/slot.999999"
printf 'pid=999999\nstart=x\nworktree=gone-wt\ncommand=check\nsince=0\n' > "$T/slots/slot.999999/info"
holder e; wait_log "e capped=0" 5; check "stale slot reclaimed, solo admit" 0 $?
grep -q 'reclaimed stale slot of gone-wt check' "$T/err"; check "reclaim is reported" 0 $?
check "stale slot dir removed" 1 "$(slots)"

# Queue order (max 1): f then g wait behind e; e ends, f runs while g keeps waiting.
export JELLYBEAM_MAX_BUILDS=1
free 500
holder f; sleep 0.5; holder g; sleep 0.5
check "both waiters queued" 2 "$(ls "$T/slots/queue" | wc -l | tr -d ' ')"
free 7000; release e
wait_log "f capped=0" 5; check "first waiter admitted first" 0 $?
sleep 1; ! grep -q '^g ' "$T/log"; check "second waiter still waits" 0 $?
grep -q 'waiting .* to run g: .*' "$T/err"; check "second waiter reports while queued" 0 $?
release f
wait_log "g capped=0" 5; check "second waiter admitted after first" 0 $?
check "admission order" "f g" "$(grep -E '^[fg] capped' "$T/log" | cut -d' ' -f1 | tr '\n' ' ' | sed 's/ $//')"
release g; sleep 0.5
unset JELLYBEAM_MAX_BUILDS

# Release with builds queued: the holder stops its own daemons while still holding its slot.
free 7000
holder r; wait_log "r capped=0" 5
free 500; holder w; sleep 1; : > "$T/docker.log"
release r; sleep 0.5; free 7000
wait_log "w capped=0" 5; check "waiter admitted after release" 0 $?
check "release with a queue stops own daemons first" "stop slots=1" "$(head -1 "$T/docker.log")"
# Release with nobody queued keeps the warm daemon.
: > "$T/docker.log"; release w; sleep 0.5
check "release with empty queue keeps daemons" "" "$(cat "$T/docker.log")"

# No slot held, head blocked on memory: head and the waiter behind it each stop their own
# idle daemons once, and the wait line names the largest container.
free 500; : > "$T/docker.log"
holder i; sleep 0.5; holder k; sleep 2.5
check "memory-blocked waiters stop own daemons once each" "stop slots=0 stop slots=0" "$(tr '\n' ' ' < "$T/docker.log" | sed 's/ $//')"
grep -q 'waiting .* to run i: .*no build running, largest container: big-wt-android-build-arm64-1 3891 MB' "$T/err"
check "wait line names the largest container" 0 $?
free 7000; wait_log "i capped=0" 5; check "idle waiter admitted once memory frees" 0 $?
release i; wait_log "k capped=0" 5; release k; sleep 0.5

# No slot held only because the head has not polled yet (it is about to be admitted): the
# fast-polling waiter behind it must not stop its daemons.
free 7000; rm -f "$T/slots/.memory-blocked"
holder e2; wait_log "e2 capped=0" 5
export JELLYBEAM_MAX_BUILDS=1
JELLYBEAM_BUILD_POLL_S=3; export JELLYBEAM_BUILD_POLL_S; holder p; sleep 0.5
JELLYBEAM_BUILD_POLL_S=0.2; export JELLYBEAM_BUILD_POLL_S; holder q; sleep 0.5
: > "$T/docker.log"; release e2; sleep 2
check "no daemon stop while the head is merely between polls" "" "$(grep 'slots=0' "$T/docker.log")"
wait_log "p capped=0" 5; release p; wait_log "q capped=0" 5; release q; sleep 0.5
unset JELLYBEAM_MAX_BUILDS

# Nested build.sh calls run under the parent's slot; JELLYBEAM_BUILD_QUEUE=off bypasses.
( JELLYBEAM_BUILD_SLOT="$T/slots"; admission_acquire x; echo "rc=$? slots=$(slots)" ) > "$T/nested"
check "nested call reuses slot" "rc=0 slots=0" "$(cat "$T/nested")"
( JELLYBEAM_BUILD_QUEUE=off; admission_acquire x; echo "rc=$? slots=$(slots)" ) > "$T/off"
check "queue off bypasses" "rc=0 slots=0" "$(cat "$T/off")"

# Capped exec flags only when capped.
( JELLYBEAM_BUILD_CAPPED=0; admission_exec_flags; echo "${#ADMIT_EXEC[@]}" ) > "$T/f0"
check "solo run has no exec flags" 0 "$(cat "$T/f0")"
( JELLYBEAM_BUILD_CAPPED=1; admission_exec_flags; echo "${ADMIT_EXEC[1]}" ) > "$T/f1"
check "capped run caps Gradle" "GRADLE_OPTS=-Dorg.gradle.workers.max=2 -Dorg.gradle.jvmargs=-Xmx3g" "$(cat "$T/f1")"

echo
if [ $fails -eq 0 ]; then echo "all passed"; else echo "$fails failed"; echo "--- stderr:"; cat "$T/err"; fi
[ $fails -eq 0 ]
