package com.sputnikboi.slopmatica.schematic;

/**
 * Fixed-width integers packed back to back into a long[], the layout Litematica uses for
 * {@code BlockStates}, and how every format's blocks are held in memory once loaded. Unlike
 * vanilla's chunk storage, an entry may straddle two longs.
 */
public final class PackedIntArray {
    private final long[] data;
    private final int bits;
    private final long mask;
    private final long size;

    /** All zeros, ready to {@link #set}. */
    public PackedIntArray(int bits, long size) {
        this(bits, size, new long[(int) ((size * bits + 63) / 64)]);
    }

    public PackedIntArray(int bits, long size, long[] data) {
        if (bits < 1 || bits > 32) {
            throw new IllegalArgumentException("bits must be in 1..32, got " + bits);
        }
        long needed = (size * bits + 63) / 64;
        if (data.length < needed) {
            throw new IllegalArgumentException("need " + needed + " longs for " + size + " entries of " + bits + " bits, got " + data.length);
        }
        this.data = data;
        this.bits = bits;
        this.mask = (1L << bits) - 1L;
        this.size = size;
    }

    /** Litematica never uses fewer than 2 bits, even for a 1- or 2-entry palette. */
    public static int bitsFor(int paletteSize) {
        return Math.max(2, Integer.SIZE - Integer.numberOfLeadingZeros(paletteSize - 1));
    }

    public int get(long index) {
        long bitIndex = index * bits;
        int start = (int) (bitIndex >>> 6);
        int end = (int) ((bitIndex + bits - 1) >>> 6);
        int offset = (int) (bitIndex & 63);
        if (start == end) {
            return (int) (data[start] >>> offset & mask);
        }
        return (int) ((data[start] >>> offset | data[end] << (64 - offset)) & mask);
    }

    public void set(long index, int value) {
        long v = value & mask;
        long bitIndex = index * bits;
        int start = (int) (bitIndex >>> 6);
        int end = (int) ((bitIndex + bits - 1) >>> 6);
        int offset = (int) (bitIndex & 63);
        data[start] = data[start] & ~(mask << offset) | v << offset;
        if (start != end) {
            int spill = 64 - offset;
            data[end] = data[end] & ~(mask >>> spill) | v >>> spill;
        }
    }

    public long[] data() {
        return data;
    }

    /** Packs values end to end, straddling long boundaries (Litematica's layout). */
    public static long[] pack(int[] values, int bits) {
        PackedIntArray out = new PackedIntArray(bits, values.length);
        for (int i = 0; i < values.length; i++) {
            out.set(i, values[i]);
        }
        return out.data;
    }

    public long size() {
        return size;
    }
}
