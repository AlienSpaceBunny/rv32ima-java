package com.alienspacebunny.bench;

/** Shared fault construction for the bench buses, kept out of their hot paths. */
final class Faults {
    private Faults() {}

    static IndexOutOfBoundsException outOfRange(int address) {
        return new IndexOutOfBoundsException("Address out of range: " + Integer.toUnsignedString(address));
    }
}
