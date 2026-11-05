package com.sputnikboi.slopmatica.telemetry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Takes what a bug report's game log doesn't need out of it: chat lines (other players' too), the
 * player's name and id, the computer's user name and home folder (in paths), server and network
 * addresses, world names and where the player joined. Errors and stack traces stay as they are.
 */
public final class LogRedactor {
    private static final Pattern IPV4 = Pattern.compile("(?<![\\d.])(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?![\\d.])");
    /** Vanilla's "Connecting to host, port" when joining a server. */
    private static final Pattern CONNECTING = Pattern.compile("Connecting to \\S+, \\d+");
    /** "Saving chunks for level 'ServerLevel[My World]'/minecraft:overworld" and the like. */
    private static final Pattern WORLD = Pattern.compile("ServerLevel\\[[^\\]]*]");
    /** The integrated server's "Name[local:E:1a2b] logged in with entity id 1 at (x, y, z)". */
    private static final Pattern JOINED_AT = Pattern.compile("(logged in with entity id \\d+) at \\([^)]*\\)");

    private LogRedactor() {
    }

    /**
     * @param secrets text to replace wherever it appears, with what to show instead (paths first:
     *     longer values are replaced before shorter ones, and names only as whole words)
     */
    public static String redact(String log, Map<String, String> secrets) {
        List<Map.Entry<String, String>> ordered = new ArrayList<>(secrets.entrySet());
        ordered.removeIf(e -> e.getKey() == null || e.getKey().isBlank() || e.getKey().length() < 2);
        ordered.sort(Comparator.comparingInt((Map.Entry<String, String> e) -> e.getKey().length()).reversed());
        List<Map.Entry<Pattern, String>> patterns = new ArrayList<>();
        for (var e : ordered) {
            // Whole words, ignoring case: "Dev" is a name in "Dev joined" but not in "DevHarness".
            patterns.add(Map.entry(Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(e.getKey()) + "(?![A-Za-z0-9_])",
                Pattern.CASE_INSENSITIVE), Matcher.quoteReplacement(e.getValue())));
        }
        StringBuilder out = new StringBuilder(log.length());
        for (String line : log.split("\n", -1)) {
            if (line.contains("[CHAT]")) {
                continue;
            }
            for (var p : patterns) {
                line = p.getKey().matcher(line).replaceAll(p.getValue());
            }
            line = CONNECTING.matcher(line).replaceAll("Connecting to <server>");
            line = IPV4.matcher(line).replaceAll("<address>");
            line = WORLD.matcher(line).replaceAll("ServerLevel[<world>]");
            line = JOINED_AT.matcher(line).replaceAll("$1");
            out.append(line).append('\n');
        }
        out.setLength(Math.max(0, out.length() - 1)); // the split kept the last line's (absent or present) end
        return out.toString();
    }

    /** What to take out of this computer's logs: home folder, user names, the player's name and id. */
    public static Map<String, String> secrets(String home, String osUser, String player, String playerId) {
        Map<String, String> s = new LinkedHashMap<>();
        s.put(home, "<home>");
        s.put(home.replace('\\', '/'), "<home>");
        s.put(osUser, "<user>");
        s.put(player, "<player>");
        s.put(playerId, "<player-id>");
        s.put(playerId.replace("-", ""), "<player-id>");
        return s;
    }
}
