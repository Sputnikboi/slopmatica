package com.sputnikboi.slopmatica.update;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UpdatesTest {
    @Test
    void comparesDottedVersionsNumerically() {
        assertTrue(Updates.newer("0.10.0", "0.9.2"));
        assertTrue(Updates.newer("0.6.1", "0.6.0"));
        assertTrue(Updates.newer("1.0", "0.99.99"));
        assertFalse(Updates.newer("0.6.0", "0.6.0"));
        assertFalse(Updates.newer("0.6.0", "0.6.1"));
        assertFalse(Updates.newer("0.6", "0.6.0"));
        assertTrue(Updates.newer("0.6.0.1", "0.6.0"));
    }
}
