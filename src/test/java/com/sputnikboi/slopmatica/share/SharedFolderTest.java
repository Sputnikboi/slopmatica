package com.sputnikboi.slopmatica.share;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sputnikboi.slopmatica.config.SlopmaticaConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SharedFolderTest {
    @TempDir
    Path dir;

    /** One instance shares; another takes the settings but keeps its own sharing choices. */
    @Test
    void settingsTravelButInstanceChoicesStay() throws Exception {
        SlopmaticaConfig a = new SlopmaticaConfig();
        a.sharedFolder = dir.toString();
        a.shareSettings = true;
        a.ghostOpacity = 0.3F;
        a.easyPlace = true;
        a.welcomeShown = true;
        SharedFolder.push(a);
        String written = Files.readString(dir.resolve("settings.json"));
        assertFalse(written.contains("sharedFolder"), "instance-only settings aren't shared");
        assertFalse(written.contains("welcomeShown"));

        SlopmaticaConfig b = new SlopmaticaConfig();
        b.sharedFolder = dir.toString();
        b.shareSettings = true;
        b.shareSchematics = true;
        assertTrue(SharedFolder.pull(b));
        assertEquals(0.3F, b.ghostOpacity);
        assertTrue(b.easyPlace);
        assertTrue(b.shareSchematics, "b's own choice stays");
        assertFalse(b.welcomeShown);
        assertEquals(dir.toString(), b.sharedFolder);
    }

    @Test
    void nothingSharedYet() {
        SlopmaticaConfig c = new SlopmaticaConfig();
        c.sharedFolder = dir.resolve("empty").toString();
        assertFalse(SharedFolder.pull(c));
    }
}
