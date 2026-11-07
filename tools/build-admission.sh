# Build admission (docs/06 "Build admission"), sourced by build.sh and tools/gate.sh: every
# worktree's builds share one Docker VM, so each Gradle/cargo run takes a host-side slot first,
# queues FIFO behind running ones, and starts only when the VM has its memory budget.
# Bash 3.2 compatible (build.sh runs under /bin/bash on macOS). Needs SERVICE set.

ADMIT_DIR="${JELLYBEAM_BUILD_SLOTS_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/jellybeam/build-slots}"
ADMIT_TICKET="" ADMIT_SLOT=""

# The decision, pure: free VM MB, running builds, solo budget MB, capped budget MB, max builds
# -> admit|capped|wait. Only a lone build with the solo budget runs uncapped; even a first build
# probes, since builds from checkouts without admission hold no slot.
admission_decide() {
    if [ "$2" -ge "$5" ]; then echo wait
    elif [ "$2" -eq 0 ] && [ "$1" -ge "$3" ]; then echo admit
    elif [ "$1" -ge "$4" ]; then echo capped
    else echo wait
    fi
}

# MemAvailable of the VM, read inside the build container (/proc/meminfo is not namespaced):
# docker stats misses swap and kernel memory and takes ~20 s. 0 when unreadable, so an
# unknown VM never admits a build.
admission_free_mb() {
    docker compose up -d --no-recreate "$SERVICE" >/dev/null 2>&1
    docker compose exec -T "$SERVICE" cat /proc/meminfo 2>/dev/null \
        | awk '/^MemAvailable:/ { kb = $2 } END { print int(kb / 1024) }'
}

_adm_start_of() { ps -o lstart= -p "$1" 2>/dev/null; }
_adm_field() { sed -n "s/^$2=//p" "$1" 2>/dev/null; }

# A record is live while its pid runs with the start time it recorded, so a reused pid does
# not keep a dead build's slot.
_adm_live() {
    local pid
    pid=$(_adm_field "$1" pid)
    [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null && [ "$(_adm_start_of "$pid")" = "$(_adm_field "$1" start)" ]
}

# "<worktree> <command> (<elapsed>m)" for a slot or ticket record.
_adm_describe() {
    local since
    since=$(_adm_field "$1" since)
    echo "$(_adm_field "$1" worktree) $(_adm_field "$1" command) ($(( ($(date +%s) - ${since:-0}) / 60 ))m)"
}

_adm_write() { # $1 file, $2 command
    printf 'pid=%s\nstart=%s\nworktree=%s\ncommand=%s\nsince=%s\n' \
        "$$" "$(_adm_start_of $$)" "$(basename "$PWD")" "$2" "$(date +%s)" > "$1"
}

# Mutex around read-decide-write; a dead holder's lock is cleared, an unreadable one after ~5 s.
_adm_lock() {
    local tries=0
    until mkdir "$ADMIT_DIR/.lock" 2>/dev/null; do
        tries=$((tries + 1))
        if { [ -f "$ADMIT_DIR/.lock/info" ] && ! _adm_live "$ADMIT_DIR/.lock/info"; } || [ $tries -ge 50 ]; then
            rm -rf "$ADMIT_DIR/.lock"; tries=0
        fi
        sleep 0.1
    done
    _adm_write "$ADMIT_DIR/.lock/info" lock
}
_adm_unlock() { rm -rf "$ADMIT_DIR/.lock"; }

_adm_reclaim() {
    local r kind
    for r in "$ADMIT_DIR"/slot.*/info "$ADMIT_DIR"/queue/*; do
        [ -f "$r" ] || continue
        _adm_live "$r" && continue
        case "$r" in */queue/*) kind=ticket ;; *) kind=slot ;; esac
        echo "build-queue: reclaimed stale $kind of $(_adm_describe "$r"), pid $(_adm_field "$r" pid) gone" >&2
        if [ $kind = ticket ]; then rm -f "$r"; else rm -rf "$(dirname "$r")"; fi
    done
}

# Stops this checkout's idle Gradle and Kotlin daemons (never another container's): a warm
# daemon holds GBs after its build, which no other waiter can free.
_adm_stop_own_daemons() {
    echo "build-queue: stopping $(basename "$PWD")'s idle Gradle/Kotlin daemons ($1)" >&2
    docker compose exec -T "$SERVICE" sh -c '
        ./gradlew --stop -q >/dev/null 2>&1
        for d in /proc/[0-9]*; do
            c=$(tr "\0" " " < "$d/cmdline" 2>/dev/null) || continue
            case "$c" in *[K]otlinCompileDaemon*) kill "${d#/proc/}" 2>/dev/null ;; esac
        done' >/dev/null 2>&1
}

# Largest container by memory, for the wait line when nothing holds a slot (report only).
_adm_largest_container() {
    docker stats --no-stream --format '{{.MemUsage}} {{.Name}}' 2>/dev/null | awk '
        { v = $1 + 0; u = $1; sub(/^[0-9.]+/, "", u)
          m = (u == "GiB") ? v * 1024 : (u == "MiB") ? v : (u == "KiB") ? v / 1024 : v / 1048576
          if (m > best) { best = m; name = $NF } }
        END { if (name != "") printf "%s %d MB", name, best }'
}

# A build that held a slot stops its daemons before freeing it when others wait, so the next
# waiter measures the memory it gave back.
admission_release() {
    [ -n "$ADMIT_TICKET" ] && rm -f "$ADMIT_TICKET"
    if [ -n "$ADMIT_SLOT" ]; then
        if [ -n "$(ls "$ADMIT_DIR/queue" 2>/dev/null)" ]; then _adm_stop_own_daemons "builds are queued"; fi
        rm -rf "$ADMIT_SLOT"
    fi
    ADMIT_TICKET="" ADMIT_SLOT=""
}

# Blocks until this process may build. Exports JELLYBEAM_BUILD_SLOT (nested build.sh calls
# run under it) and JELLYBEAM_BUILD_CAPPED=1 when admitted with only the capped budget; returns 75
# after the max wait. Releases on exit.
admission_acquire() {
    local command="$1"
    [ "${JELLYBEAM_BUILD_QUEUE:-on}" = off ] && return 0
    if [ -n "${JELLYBEAM_BUILD_SLOT:-}" ] && [ -d "$JELLYBEAM_BUILD_SLOT" ]; then return 0; fi
    local budget="${JELLYBEAM_BUILD_BUDGET_MB:-4096}" max="${JELLYBEAM_MAX_BUILDS:-2}"
    local solo="${JELLYBEAM_SOLO_BUDGET_MB:-6144}"
    local max_wait=$(( ${JELLYBEAM_BUILD_MAX_WAIT_MIN:-90} * 60 ))
    local poll="${JELLYBEAM_BUILD_POLL_S:-10}" report="${JELLYBEAM_BUILD_REPORT_S:-60}"
    local began seq head running holders free d reason last=0 now s blocked=0 cleaned=0 big
    local marker=internal/perf-ab/.measuring
    began=$(date +%s)
    mkdir -p "$ADMIT_DIR/queue"
    trap admission_release EXIT

    _adm_lock
    seq=$(( $(cat "$ADMIT_DIR/.seq" 2>/dev/null || echo 0) + 1 ))
    echo "$seq" > "$ADMIT_DIR/.seq"
    ADMIT_TICKET=$(printf '%s/queue/%012d.%s' "$ADMIT_DIR" "$seq" "$$")
    _adm_write "$ADMIT_TICKET" "$command"
    _adm_unlock

    while :; do
        _adm_lock
        _adm_reclaim
        head=$(ls "$ADMIT_DIR/queue" | sort | head -1)
        running=0 holders="" free=0 d=wait
        for s in "$ADMIT_DIR"/slot.*/info; do
            [ -f "$s" ] || continue
            running=$((running + 1)); holders="${holders:+$holders, }$(_adm_describe "$s")"
        done
        # tools/perf-ab.sh holds this marker while it measures: no builds beside a measurement.
        blocked=0
        if [ -f "$marker" ] && kill -0 "$(cat "$marker")" 2>/dev/null; then
            reason="perf-ab measuring (pid $(cat "$marker"))"
        elif [ "$ADMIT_DIR/queue/$head" != "$ADMIT_TICKET" ]; then
            reason="queued behind $(_adm_describe "$ADMIT_DIR/queue/$head")${holders:+; running: $holders}"
            [ "$running" -eq 0 ] && [ -f "$ADMIT_DIR/.memory-blocked" ] && blocked=1
        else
            if [ "$running" -lt "$max" ]; then free=$(admission_free_mb); fi
            d=$(admission_decide "${free:-0}" "$running" "$solo" "$budget" "$max")
            if [ "$running" -ge "$max" ]; then reason="$running/$max builds running: $holders"
            else reason="${holders:+running: $holders; }VM free ${free:-0} MB < $budget MB capped budget"
            fi
            # Only the head measures, so only it marks the queue blocked on memory alone.
            if [ "$d" = wait ] && [ "$running" -eq 0 ]; then touch "$ADMIT_DIR/.memory-blocked"; blocked=1
            else rm -f "$ADMIT_DIR/.memory-blocked"
            fi
        fi
        if [ "$d" != wait ]; then
            ADMIT_SLOT="$ADMIT_DIR/slot.$$"
            mkdir "$ADMIT_SLOT" && _adm_write "$ADMIT_SLOT/info" "$command"
            rm -f "$ADMIT_TICKET"; ADMIT_TICKET=""
            _adm_unlock
            export JELLYBEAM_BUILD_SLOT="$ADMIT_SLOT"
            if [ "$d" = capped ]; then
                export JELLYBEAM_BUILD_CAPPED=1
                echo "build-queue: admitted $command capped (VM free $free MB, solo budget $solo MB${holders:+, beside $holders}); Gradle -Xmx3g, 2 workers" >&2
            else
                export JELLYBEAM_BUILD_CAPPED=0
                [ "$last" -gt 0 ] && echo "build-queue: admitted $command after $(( ($(date +%s) - began) / 60 ))m" >&2
            fi
            return 0
        fi
        _adm_unlock
        # Memory alone blocks the queue with no build running: free our own idle daemons once,
        # then re-check at once.
        if [ "$blocked" -eq 1 ] && [ "$cleaned" -eq 0 ]; then
            _adm_stop_own_daemons "queue blocked on memory with no build running"
            cleaned=1; continue
        fi
        now=$(date +%s)
        big=""
        if [ "$blocked" -eq 1 ] && { [ $((now - began)) -ge "$max_wait" ] || [ $((now - last)) -ge "$report" ]; }; then
            big=$(_adm_largest_container)
            [ -n "$big" ] && reason="$reason; no build running, largest container: $big"
        fi
        if [ $((now - began)) -ge "$max_wait" ]; then
            echo "build-queue: gave up on $command after $(( (now - began) / 60 ))m (JELLYBEAM_BUILD_MAX_WAIT_MIN=$((max_wait / 60))): $reason. Retry later, or JELLYBEAM_BUILD_QUEUE=off to bypass." >&2
            admission_release
            return 75
        fi
        if [ $((now - last)) -ge "$report" ]; then
            echo "build-queue: waiting $(( (now - began) / 60 ))m/$((max_wait / 60))m to run $command: $reason" >&2
            last=$now
        fi
        sleep "$poll"
    done
}

# docker compose exec flags for a capped run (heap + workers sized to the budget, fewer cargo
# jobs) into the ADMIT_EXEC array; empty for a solo run, which keeps gradle.properties' heap.
admission_exec_flags() {
    ADMIT_EXEC=()
    [ "${JELLYBEAM_BUILD_CAPPED:-0}" = 1 ] || return 0
    ADMIT_EXEC=(-e "GRADLE_OPTS=${JELLYBEAM_CAPPED_GRADLE_OPTS:--Dorg.gradle.workers.max=2 -Dorg.gradle.jvmargs=-Xmx3g}"
                -e "CARGO_BUILD_JOBS=${JELLYBEAM_CAPPED_CARGO_JOBS:-2}")
}
