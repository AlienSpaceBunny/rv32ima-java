package com.alienspacebunny.emu;

/**
 * The outcome of one {@link RV32IMACore#stepAttempts} call.
 *
 * <p>There is no retirement count: take the delta of {@link RV32IMAState#getInstret()} (or of
 * {@link RV32IMAState#getCycle()} for instructions attempted), never of the guest-writable {@link
 * RV32IMAState#getMinstret()}.
 *
 * @param coreResult the last attempt's result: {@code 1} if the hart is stalled in {@code WFI},
 *     otherwise {@code 0}.
 * @param attemptsConsumed the attempts run, at least 1. This counts attempts, not instructions:
 *     an interrupt-only entry and a {@code WFI} stall each consume one.
 * @param controllerStopped whether {@link AttemptController#afterAttempt} ended the call.
 */
public record AttemptResult(int coreResult, int attemptsConsumed, boolean controllerStopped) {
    /** Why a {@link RV32IMACore#stepAttempts} call ended. */
    public enum ExitReason {
        /** {@link AttemptController#afterAttempt} returned {@code true}. */
        CONTROLLER_STOP,
        /** The last attempt returned a nonzero result, and the controller did not stop. */
        CORE_RESULT,
        /** The budget ran out. */
        BUDGET
    }

    /**
     * Returns why the call ended. A controller stop takes precedence over a nonzero result, which
     * takes precedence over the budget; {@link #controllerStopped()} and {@link #coreResult()}
     * still record both.
     *
     * @return the exit reason.
     */
    public ExitReason exitReason() {
        if (controllerStopped) {
            return ExitReason.CONTROLLER_STOP;
        }
        return coreResult != 0 ? ExitReason.CORE_RESULT : ExitReason.BUDGET;
    }
}
