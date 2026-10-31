package com.sputnikboi.slopmatica.schematic;

/** Test helper: the inverse of {@link PackedIntArray}, packing the way Litematica writes files. */
public final class PackedIntArrayAccess {
    private PackedIntArrayAccess() {
    }

    public static long[] pack(int[] values, int bits) {
        long[] out = new long[(int) (((long) values.length * bits + 63) / 64)];
        for (int i = 0; i < values.length; i++) {
            long bitIndex = (long) i * bits;
            int start = (int) (bitIndex >>> 6);
            int offset = (int) (bitIndex & 63);
            out[start] |= (long) values[i] << offset;
            int end = (int) ((bitIndex + bits - 1) >>> 6);
            if (end != start) {
                out[end] |= (long) values[i] >>> (64 - offset);
            }
        }
        return out;
    }
}
