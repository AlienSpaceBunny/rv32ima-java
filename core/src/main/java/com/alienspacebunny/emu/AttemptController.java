package com.alienspacebunny.emu;

/**
 * Callbacks around the committed attempts of {@link RV32IMACore#stepAttempts}. See that method
 * for what an attempt is and when each callback runs.
 *
 * <p>Both callbacks run on the thread that called {@code stepAttempts} and see fully published
 * state: pc, the CSRs, privilege, {@link RV32IMAState#getCycle()} and {@link
 * RV32IMAState#getInstret()}. They may edit any of it, and may call {@link
 * RV32IMACore#flushDecodedCache()}. They must not enter execution on the same core ({@code step}
 * or {@code stepAttempts}) or reconfigure it otherwise. They must return normally: an exception
 * propagates out of {@code stepAttempts} with no rollback, and is not a way to stop.
 */
public interface AttemptController {
    /**
     * Called before each attempt, with the state at the preceding boundary and before the
     * attempt's timer update and interrupt check. Edits here apply to the attempt about to run;
     * for example, a pending bit set in {@code mip} can be taken by it. A direct {@code MTIP} edit
     * is overwritten, since the attempt recomputes {@code MTIP} from the compare.
     *
     * @param state the hart's state.
     */
    void beforeAttempt(RV32IMAState state);

    /**
     * Called after each attempt, once all of its effects are published, including a trap's entry
     * and a {@code FENCE.I}'s cache flush. Edits here apply to the following attempt, if the call
     * continues. An interrupt that arrives here does not change a returned {@code 1}.
     *
     * @param state the hart's state.
     * @param coreResult the attempt's result: {@code 1} if the hart is stalled in {@code WFI},
     *     otherwise {@code 0}.
     * @return {@code true} to end the call at this boundary.
     */
    boolean afterAttempt(RV32IMAState state, int coreResult);
}
