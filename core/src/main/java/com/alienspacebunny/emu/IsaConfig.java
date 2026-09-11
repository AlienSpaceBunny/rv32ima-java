package com.alienspacebunny.emu;

/**
 * Immutable RISC-V extension configuration for a {@link RV32IMACore}.
 *
 * <p>The base configuration — RV32I, M (integer multiplication), A (atomics), and Zicsr — is
 * always present regardless of these flags. {@code RV32IMACore}'s zero-argument constructor uses
 * {@link #RV32IMA_ZICSR}, which reproduces the exact {@link #misa()} value the core hardcoded
 * before this type existed; this is behaviorally identical to the core before {@code IsaConfig}
 * existed. The flags below toggle optional extensions layered on top, added for the V-32 AP/IOP
 * multi-hart feature work (see {@code docs/FEATURE_REQUEST_PLAN.md}).
 *
 * <p><b>Decode support.</b> {@link #hasZba}, {@link #hasZbb}, and {@link #hasZabha} are decoded as
 * of the Phase 3 work: {@link #hasZba} unlocks {@code SH1ADD}/{@code SH2ADD}/{@code SH3ADD}; {@link
 * #hasZbb} unlocks the ~18 basic bit-manipulation instructions; {@link #hasZabha} unlocks
 * byte/halfword AMOs (the RV32A opcode's {@code funct3} field admitting {@code 0}/{@code 1} in
 * addition to {@code 2}). {@link #hasC} and {@link #hasF} are not decoded yet: setting one only
 * changes the {@link #misa()} value the guest observes, and the corresponding instructions still
 * raise an illegal-instruction trap until the phase that implements them lands (C: Phase 4; F:
 * Phase 5). Enabling a flag ahead of its phase does not unlock any instructions early — it only
 * changes what the guest reads back from {@code misa}.
 *
 * <p><b>{@code misa}'s U-mode bit.</b> The value {@code RV32IMACore} hardcoded before this type
 * existed ({@code 0x40401101}) does not set the standard "U" bit (bit 20) that advertises
 * user-mode support — even though the core does implement user mode. It instead leaves an
 * inherited bit 22 set, which has no standard single-letter meaning; this is presumed to be a
 * quirk carried over from the original mini-rv32ima C implementation, not an intentional
 * statement about privilege support. {@link #hasU} controls which of the two a config reports:
 * {@code false} (the default, {@link #RV32IMA_ZICSR}) reproduces the original value bit-for-bit,
 * including the quirk; {@code true} reports the standard U bit instead and drops the quirk bit.
 * Configs whose guest code actually runs in user mode — such as the V-32 presets below — should
 * use {@code true}.
 *
 * @param hasC RVC compressed (16-bit) instruction support.
 * @param hasF single-precision floating-point (F extension) support.
 * @param hasZba address-generation bit-manipulation ({@code SH1ADD}/{@code SH2ADD}/{@code
 *     SH3ADD}).
 * @param hasZbb basic bit-manipulation extension.
 * @param hasZabha byte/halfword atomic memory operations.
 * @param hasU whether {@link #misa()} advertises standard U-mode support (bit 20) instead of
 *     reproducing the original hardcoded value's non-standard bit 22. See the class Javadoc.
 */
public record IsaConfig(boolean hasC, boolean hasF, boolean hasZba, boolean hasZbb, boolean hasZabha, boolean hasU) {

    private static final int MISA_MXL32 = 0x40000000;
    private static final int MISA_A = 1; // bit 0
    private static final int MISA_C = 1 << 2;
    private static final int MISA_F = 1 << 5;
    private static final int MISA_I = 1 << 8;
    private static final int MISA_M = 1 << 12;
    private static final int MISA_U = 1 << 20;

    /**
     * The non-standard bit the original hardcoded {@code misa} value set instead of the standard
     * U bit. Corresponds to the letter "W" in the standard encoding, which is not an architected
     * RISC-V extension. Preserved only when {@link #hasU} is {@code false}, for bit-for-bit
     * compatibility with the value {@code RV32IMACore} hardcoded before this type existed.
     */
    private static final int MISA_LEGACY_NON_U_BIT = 1 << 22;

    /** Bits present in {@link #misa()} regardless of configuration or {@link #hasU}. */
    private static final int MISA_BASE = MISA_MXL32 | MISA_I | MISA_M | MISA_A;

    /**
     * Creates an {@code IsaConfig} with {@link #hasU} defaulted to {@code false} (reproduce the
     * original hardcoded {@code misa} value's non-standard bit 22 rather than the standard U bit)
     * — the behavior every config had before {@link #hasU} was added.
     *
     * @param hasC see {@link #hasC}.
     * @param hasF see {@link #hasF}.
     * @param hasZba see {@link #hasZba}.
     * @param hasZbb see {@link #hasZbb}.
     * @param hasZabha see {@link #hasZabha}.
     */
    public IsaConfig(boolean hasC, boolean hasF, boolean hasZba, boolean hasZbb, boolean hasZabha) {
        this(hasC, hasF, hasZba, hasZbb, hasZabha, false);
    }

    /**
     * Base configuration: no optional extensions, and {@link #misa()} reproduces the exact value
     * {@code RV32IMACore} hardcoded before this type existed (including its non-standard bit 22 —
     * see the class Javadoc). Used by {@code RV32IMACore}'s zero-argument constructor.
     */
    public static final IsaConfig RV32IMA_ZICSR = new IsaConfig(false, false, false, false, false, false);

    /**
     * V-32 AP (application processor) target from the feature request: {@code RV32IMFC_Zba_Zbb}.
     * {@code hasU} is {@code true}: the AP runs guest code in user mode, so {@code misa} should
     * advertise that correctly rather than reproducing the legacy non-U bit.
     */
    public static final IsaConfig RV32IMFC_ZBA_ZBB_ZICSR = new IsaConfig(true, true, true, true, false, true);

    /**
     * V-32 IOP (I/O processor) target from the feature request: {@code RV32IMC_Zbb}. {@code hasU}
     * is {@code true} for the same reason as {@link #RV32IMFC_ZBA_ZBB_ZICSR}.
     */
    public static final IsaConfig RV32IMC_ZBB_ZICSR = new IsaConfig(true, false, false, true, false, true);

    /**
     * Computes the {@code misa} CSR value for this configuration.
     *
     * <p>{@link #hasZba}, {@link #hasZbb}, and {@link #hasZabha} do not affect this value: {@code
     * misa} only encodes the single-letter standard extensions, and Zba/Zbb/Zabha are
     * multi-letter "Z" sub-extensions with no bit of their own in the register.
     *
     * @return the RV32 {@code misa} value: the always-present base bits, plus either the standard
     *     U bit or the legacy non-U bit depending on {@link #hasU} (see the class Javadoc), plus C
     *     and/or F when enabled.
     */
    public int misa() {
        int value = MISA_BASE | (hasU ? MISA_U : MISA_LEGACY_NON_U_BIT);
        if (hasC) {
            value |= MISA_C;
        }
        if (hasF) {
            value |= MISA_F;
        }
        return value;
    }
}
