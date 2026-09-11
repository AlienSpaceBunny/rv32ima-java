package com.alienspacebunny.emu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class IsaConfigTest {

    @Test
    public void baseConfigHasNoExtensionFlagsSet() {
        IsaConfig config = IsaConfig.RV32IMA_ZICSR;

        assertFalse(config.hasC());
        assertFalse(config.hasF());
        assertFalse(config.hasZba());
        assertFalse(config.hasZbb());
        assertFalse(config.hasZabha());
        assertFalse(config.hasU());
    }

    @Test
    public void baseConfigMisaMatchesPreviouslyHardcodedValue() {
        assertEquals(0x40401101, IsaConfig.RV32IMA_ZICSR.misa());
    }

    @Test
    public void fiveArgConstructorDefaultsToLegacyNonUMisaBit() {
        // The compatibility constructor (no hasU) must behave exactly as every config did before
        // hasU was added.
        IsaConfig config = new IsaConfig(false, false, true, true, true);

        assertFalse(config.hasU());
        assertEquals(0x40401101, config.misa());
    }

    @Test
    public void hasUTrueAdvertisesStandardUBitInsteadOfLegacyBit() {
        IsaConfig config = new IsaConfig(false, false, false, false, false, true);

        assertEquals(0x40101101, config.misa());
        assertEquals(0, config.misa() & (1 << 22)); // legacy non-U bit dropped
        assertEquals(1 << 20, config.misa() & (1 << 20)); // standard U bit set
    }

    @Test
    public void apTargetConfigSetsExpectedFlags() {
        IsaConfig config = IsaConfig.RV32IMFC_ZBA_ZBB_ZICSR;

        assertTrue(config.hasC());
        assertTrue(config.hasF());
        assertTrue(config.hasZba());
        assertTrue(config.hasZbb());
        assertFalse(config.hasZabha());
        assertTrue(config.hasU());
    }

    @Test
    public void apTargetConfigMisaAddsCAndFBitsAndStandardUBit() {
        // base (MXL|I|M|A) | U (bit 20, not the legacy bit 22) | C (bit 2) | F (bit 5)
        assertEquals(0x40001101 | (1 << 20) | (1 << 2) | (1 << 5), IsaConfig.RV32IMFC_ZBA_ZBB_ZICSR.misa());
    }

    @Test
    public void iopTargetConfigSetsExpectedFlags() {
        IsaConfig config = IsaConfig.RV32IMC_ZBB_ZICSR;

        assertTrue(config.hasC());
        assertFalse(config.hasF());
        assertFalse(config.hasZba());
        assertTrue(config.hasZbb());
        assertFalse(config.hasZabha());
        assertTrue(config.hasU());
    }

    @Test
    public void iopTargetConfigMisaAddsOnlyCBitAndStandardUBit() {
        assertEquals(0x40001101 | (1 << 20) | (1 << 2), IsaConfig.RV32IMC_ZBB_ZICSR.misa());
    }

    @Test
    public void zbaZbbZabhaDoNotAffectMisa() {
        // None of Zba/Zbb/Zabha have a bit of their own in misa.
        IsaConfig config = new IsaConfig(false, false, true, true, true, false);

        assertEquals(0x40401101, config.misa());
    }
}
