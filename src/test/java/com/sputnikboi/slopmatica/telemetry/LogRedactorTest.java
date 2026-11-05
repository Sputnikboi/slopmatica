package com.sputnikboi.slopmatica.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LogRedactorTest {
    private static final String ID = "4e703283-8eb6-44cd-831c-3400cf2d8a9e";

    private static String redact(String log, String home, String osUser) {
        return LogRedactor.redact(log, LogRedactor.secrets(home, osUser, "Sputnikboi", ID));
    }

    @Test
    void takesOutWhatReportsDontNeed() {
        String log = String.join("\n",
            "[12:00:00] [Render thread/INFO]: Setting user: Sputnikboi",
            "[12:00:01] [Render thread/INFO]: Loading C:\\Users\\Elias\\AppData\\Roaming\\.minecraft\\mods\\slopmatica-0.31.0.jar",
            "[12:00:02] [Render thread/INFO]: Connecting to play.example.com, 25565",
            "[12:00:03] [Render thread/INFO]: [System] [CHAT] <Bob> meet at my base",
            "[12:00:04] [Server thread/INFO]: Sputnikboi[local:E:1a2b] logged in with entity id 7 at (120.5, 64.0, -33.5)",
            "[12:00:05] [Server thread/INFO]: Saving chunks for level 'ServerLevel[Elias's world]'/minecraft:overworld",
            "[12:00:06] [Render thread/WARN]: from 192.168.1.20 for " + ID.replace("-", "") + " and " + ID,
            "[12:00:07] [Render thread/ERROR]: Crash in DevHarness at SlopmaticaClient.java:42 on LWJGL 3.3.3",
            "");
        String want = String.join("\n",
            "[12:00:00] [Render thread/INFO]: Setting user: <player>",
            "[12:00:01] [Render thread/INFO]: Loading <home>\\AppData\\Roaming\\.minecraft\\mods\\slopmatica-0.31.0.jar",
            "[12:00:02] [Render thread/INFO]: Connecting to <server>",
            "[12:00:04] [Server thread/INFO]: <player>[local:E:1a2b] logged in with entity id 7",
            "[12:00:05] [Server thread/INFO]: Saving chunks for level 'ServerLevel[<world>]'/minecraft:overworld",
            "[12:00:06] [Render thread/WARN]: from <address> for <player-id> and <player-id>",
            "[12:00:07] [Render thread/ERROR]: Crash in DevHarness at SlopmaticaClient.java:42 on LWJGL 3.3.3",
            "");
        assertEquals(want, redact(log, "C:\\Users\\Elias", "Elias"));
    }

    @Test
    void userNamesOnlyAsWholeWordsAndPathsEitherWay() {
        String log = "/home/dev/.minecraft and C:/Users/Dev too; DevHarness stays, dev's name doesn't";
        assertEquals("<home>/.minecraft and C:/Users/<user> too; DevHarness stays, <user>'s name doesn't",
            redact(log, "/home/dev", "dev"));
        assertEquals("C:/Users/<home>", redact("C:/Users/<home>", "", ""));
        assertEquals("<home>/x", redact("C:/Users/Elias/x", "C:\\Users\\Elias", "Elias"));
    }
}
