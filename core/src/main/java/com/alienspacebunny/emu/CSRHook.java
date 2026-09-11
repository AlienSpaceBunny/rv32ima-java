package com.alienspacebunny.emu;

/**
 * Extension point for custom or platform-specific CSRs not handled by {@link RV32IMACore}.
 *
 * <p><b>Built-in CSRs.</b> The following CSR numbers are handled directly by the core and are
 * never routed to a {@code CSRHook}:
 *
 * <ul>
 *   <li>{@code 0x300} — {@code mstatus}
 *   <li>{@code 0x301} — {@code misa} (read-only; value depends on the {@link IsaConfig} the core
 *       was constructed with — see {@link IsaConfig#misa()})
 *   <li>{@code 0x304} — {@code mie}
 *   <li>{@code 0x305} — {@code mtvec}
 *   <li>{@code 0x340} — {@code mscratch}
 *   <li>{@code 0x341} — {@code mepc}
 *   <li>{@code 0x342} — {@code mcause}
 *   <li>{@code 0x343} — {@code mtval}
 *   <li>{@code 0x344} — {@code mip}
 *   <li>{@code 0xC00} — {@code cycle} (low 32 bits of the cycle counter)
 *   <li>{@code 0xF11} — {@code mvendorid} (read-only, fixed at {@code 0xFF0FF0FF})
 * </ul>
 *
 * <p><b>Side-effect rules.</b> This hook is invoked only when CSR instruction semantics require
 * the read or write to occur:
 *
 * <ul>
 *   <li>{@code CSRRW rd=x0}: the CSR is written but not read; {@link #handleRead} is not called.
 *   <li>{@code CSRRS rs1=x0} and {@code CSRRC rs1=x0}: the CSR is read but not written; {@link
 *       #handleWrite} is not called.
 *   <li>Immediate variants ({@code CSRRSI uimm=0}, {@code CSRRCI uimm=0}): the CSR is not
 *       written; {@link #handleWrite} is not called.
 * </ul>
 *
 * <p><b>Privilege gating.</b> Before any CSR read or write reaches the core's built-in handling or
 * this hook, {@code RV32IMACore} checks the CSR number's minimum-privilege field (bits 9–8 of the
 * 12-bit CSR number, per the standard RISC-V CSR address encoding) against the hart's current
 * privilege. If the hart's privilege is lower, the access raises an illegal-instruction trap and
 * this hook is never called. This applies uniformly, including to hook-routed CSR numbers that
 * don't follow the standard convention on purpose (for example, {@code 0x136}–{@code 0x140}-style
 * custom console CSRs have a minimum-privilege field of {@code 1} by coincidence of their address,
 * not by design) — a custom CSR intended to be callable from user-mode guest code must use an
 * address whose bits 9–8 are {@code 0b00}.
 *
 * <p><b>Unrecognised CSR numbers.</b> {@link #handleRead} should return {@code 0} for any CSR
 * number the hook does not implement. {@link #handleWrite} should silently ignore writes to
 * unrecognised CSR numbers.
 *
 * <p><b>Thread safety.</b> Hook methods are called from the emulator step loop with no additional
 * synchronization. Thread safety is the responsibility of the caller.
 */
public interface CSRHook {
    /**
     * Handles a custom CSR read.
     *
     * @param csrNo the 12-bit CSR number.
     * @return the CSR value, or {@code 0} if the CSR number is not recognised by this hook.
     */
    int handleRead(int csrNo);

    /**
     * Handles a custom CSR write.
     *
     * @param csrNo the 12-bit CSR number.
     * @param value the 32-bit value to write.
     */
    void handleWrite(int csrNo, int value);
}
