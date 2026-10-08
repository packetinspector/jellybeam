package tv.jellybeam.player

import uniffi.jellybeam_core.CoreException

/** docs/18 §2.1: what a playback request's returned plan may do. */
internal enum class PlanOutcome {
    /** The request still owns playback: load the plan. */
    APPLY,

    /** A newer request or a stop took over: abandon the plan by its own id, publish nothing. */
    DISPOSE,

    /** The account changed under this owner: abandon the plan and close the player. */
    CLOSE,
}

/** docs/18 §2.1: what a playback request's failure may do. */
internal enum class FailureOutcome { IGNORE, CLOSE, QUIET_FINISH, REAUTHORIZE, FINISH_WITH_MESSAGE }

/** [latest]: still the view model's newest request; [open]: no account call in flight and the
 * account epoch it was minted under is still current.
 */
internal fun planOutcome(latest: Boolean, open: Boolean): PlanOutcome = when {
    !latest -> PlanOutcome.DISPOSE
    !open -> PlanOutcome.CLOSE
    else -> PlanOutcome.APPLY
}

/** As [planOutcome]; only the owner reports, and re-authorization is asked for here rather than by
 * the gateway, so an obsolete request's 401 never opens it. A stale fallback leaves a [liveSession]
 * playing (it may only have been a software decoder); a stale prepare has nothing to play.
 */
internal fun failureOutcome(latest: Boolean, open: Boolean, liveSession: Boolean, error: CoreException): FailureOutcome = when {
    !latest -> FailureOutcome.IGNORE
    !open -> FailureOutcome.CLOSE
    error is CoreException.StalePlaybackSession -> if (liveSession) FailureOutcome.IGNORE else FailureOutcome.QUIET_FINISH
    error is CoreException.Unauthorized -> FailureOutcome.REAUTHORIZE
    else -> FailureOutcome.FINISH_WITH_MESSAGE
}

/** docs/18 §2.1: only a request minted before the launch restore landed ([seq] at or below
 * [lastSeqBeforeRestore]) is re-minted when its epoch is stale; a later stale epoch is a real
 * account change, which must be refused, never carried over to the new account.
 */
internal fun remintAfterRestore(seq: ULong, lastSeqBeforeRestore: ULong, open: Boolean): Boolean =
    !open && seq <= lastSeqBeforeRestore
