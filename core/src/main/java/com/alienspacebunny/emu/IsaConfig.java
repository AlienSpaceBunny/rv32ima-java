package com.alienspacebunny.emu;

/**
 * Immutable RISC-V extension configuration for a {@link RV32IMACore}.
 *
 * <p>The base configuration — RV32I, M (integer multiplication), A (atomics), and Zicsr — is
 * always present regardless of these flags. {@code RV32IMACore}'s zero-argument constructor uses
 * {@link #RV32IMA_ZICSR}, whose flags are all {@code false}; this is behaviorally identical to
 * the core before this type existed. The flags below toggle optional extensions layered on top,
 * added for the V-32 AP/IOP multi-hart feature work (see {@code docs/FEATURE_REQUEST_PLAN.md}).
 *
 * <p><b>Decode support.</b> As of the Phase 1 foundation work, none of these extensions are
 * decoded yet: setting a flag only changes the {@link #misa()} value the guest observes. The
 * corresponding instructions still raise an illegal-instruction trap until the phase that
 * implements them lands (Zba/Zbb/Zabha: Phase 3; C: Phase 4; F: Phase 5). Enabling a flag ahead
 * of its phase does not unlock any instructions early — it only changes what the guest reads
 * back from {@code misa}.
 *
 * @param hasC RVC compressed (16-bit) instruction support.
 * @param hasF single-precision floating-point (F extension) support.
 * @param hasZba address-generation bit-manipulation ({@code SH1ADD}/{@code SH2ADD}/{@code
 *     SH3ADD}).
 * @param hasZbb basic bit-manipulation extension.
 * @param hasZabha byte/halfword atomic memory operations.
 */
public record IsaConfig(boolean hasC, boolean hasF, boolean hasZba, boolean hasZbb, boolean hasZabha) {

    private static final int MISA_C = 1 << 2;
    private static final int MISA_F = 1 << 5;

    /**
     * Bits present in {@link #misa()} regardless of configuration, equal to the value {@code
     * RV32IMACore} hardcoded before this type existed: MXL=32 (bit 30), the I/M/A base extensions
     * (bits 8, 12, 0), and bit 22. Bit 22 corresponds to the letter "W" in the standard {@code
     * misa} encoding, which is not an architected RISC-V extension; it is preserved unchanged
     * from the original constant rather than reinterpreted, since changing it would change the
     * {@code misa} value the base configuration reports.
     */
    private static final int MISA_BASE = 0x40401101;

    /**
     * Base configuration: no optional extensions. Used by {@link RV32IMACore}'s zero-argument
     * constructor.
     */
    public static final IsaConfig RV32IMA_ZICSR = new IsaConfig(false, false, false, false, false);

    /** V-32 AP (application processor) target from the feature request: {@code RV32IMFC_Zba_Zbb}. */
    public static final IsaConfig RV32IMFC_ZBA_ZBB_ZICSR = new IsaConfig(true, true, true, true, false);

    /** V-32 IOP (I/O processor) target from the feature request: {@code RV32IMC_Zbb}. */
    public static final IsaConfig RV32IMC_ZBB_ZICSR = new IsaConfig(true, false, false, true, false);

    /**
     * Computes the {@code misa} CSR value for this configuration.
     *
     * <p>{@link #hasZba}, {@link #hasZbb}, and {@link #hasZabha} do not affect this value: {@code
     * misa} only encodes the single-letter standard extensions, and Zba/Zbb/Zabha are
     * multi-letter "Z" sub-extensions with no bit of their own in the register.
     *
     * @return the RV32 {@code misa} value: the always-present base bits ({@code 0x40401101}),
     *     plus C and/or F when enabled.
     */
    public int misa() {
        int value = MISA_BASE;
        if (hasC) {
            value |= MISA_C;
        }
        if (hasF) {
            value |= MISA_F;
        }
        return value;
    }
}
