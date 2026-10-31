package com.sputnikboi.slopmatica.verify;

/** How the block in the world compares to the block the schematic wants there. */
public enum BlockStatus {
    /** Nothing placed yet (air or loose fluid). Drawn as a ghost block with a blue outline. */
    MISSING(0xFF3D8BFF),
    /** Exactly the right block state. */
    MATCH(0xFF3DDC5A),
    /** Right kind of block, wrong state or variant: orientation, waxed vs unwaxed copper, etc. */
    VARIANT(0xFFFFD23D),
    /** A different block entirely. */
    WRONG(0xFFFF4040);

    private final int color;

    BlockStatus(int color) {
        this.color = color;
    }

    /** Outline colour, ARGB. */
    public int color() {
        return color;
    }
}
