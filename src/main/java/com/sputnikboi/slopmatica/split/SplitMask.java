package com.sputnikboi.slopmatica.split;

import java.util.BitSet;

/** Which blocks of a schematic are shown (schematic coordinates): a box and a bit per block in it. */
public record SplitMask(int minX, int minY, int minZ, int sx, int sy, int sz, BitSet bits) {
    public boolean contains(int x, int y, int z) {
        int rx = x - minX, ry = y - minY, rz = z - minZ;
        return rx >= 0 && ry >= 0 && rz >= 0 && rx < sx && ry < sy && rz < sz && bits.get((ry * sz + rz) * sx + rx);
    }
}
