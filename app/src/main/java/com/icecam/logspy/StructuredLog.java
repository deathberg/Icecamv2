package com.icecam.logspy;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts raw logcat threadtime lines into AI-friendly structured records.
 * File: NDJSON (one JSON object per line). UI: short [LEVEL][CAT] prefix.
 */
public final class StructuredLog {

    private static final Pattern THREADTIME = Pattern.compile(
            "^(\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+(\\d+)\\s+(\\d+)\\s+([VDIWEF])\\s+([^:]+):\\s(.*)");

    private StructuredLog() {
    }

    public static Parsed parse(String rawLine) {
        if (rawLine == null || rawLine.isEmpty()) {
            return null;
        }
        Matcher m = THREADTIME.matcher(rawLine);
        if (!m.matches()) {
            return null;
        }
        String ts = m.group(1);
        int pid = parseInt(m.group(2));
        int tid = parseInt(m.group(3));
        String level = mapLevel(m.group(4).charAt(0));
        String tag = m.group(5).trim();
        String msg = m.group(6);
        String cat = detectCategory(tag, msg);
        return new Parsed(ts, level, cat, pid, tid, tag, msg);
    }

    /** NDJSON line for persistent log file. */
    public static String toJsonLine(Parsed p) {
        return "{\"type\":\"log\""
                + ",\"ts\":\"" + jsonEscape(p.ts) + "\""
                + ",\"level\":\"" + p.level + "\""
                + ",\"cat\":\"" + p.cat + "\""
                + ",\"pid\":" + p.pid
                + ",\"tid\":" + p.tid
                + ",\"tag\":\"" + jsonEscape(p.tag) + "\""
                + ",\"msg\":\"" + jsonEscape(p.msg) + "\"}";
    }

    /** Compact prefix line for on-screen ScrollView. */
    public static String toUiLine(Parsed p) {
        return "[" + p.level + "][" + p.cat + "] " + p.tag + ": " + p.msg;
    }

    public static String sessionEventJson(String event) {
        return "{\"type\":\"session\",\"event\":\"" + jsonEscape(event) + "\""
                + ",\"ts\":" + System.currentTimeMillis() + "}";
    }

    private static String mapLevel(char c) {
        switch (c) {
            case 'E':
            case 'F':
                return "ERROR";
            case 'W':
                return "WARN";
            case 'I':
                return "INFO";
            case 'D':
                return "DEBUG";
            case 'V':
                return "VERBOSE";
            default:
                return "INFO";
        }
    }

    private static String detectCategory(String tag, String msg) {
        String blob = (tag + " " + msg).toLowerCase();
        if (containsAny(blob, "jni", "native", "ndk", "libvc", ".so")) {
            return "JNI";
        }
        if (containsAny(blob, "binder", "ibinder", "transact", "parcel", "deadobject")) {
            return "BINDER";
        }
        if (containsAny(blob, "exception", "crash", "fatal", "died", "sigsegv", "abort")) {
            return "ERROR";
        }
        return "INFO";
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) {
                return true;
            }
        }
        return false;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static String jsonEscape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\':
                    out.append("\\\\");
                    break;
                case '"':
                    out.append("\\\"");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }

    public static final class Parsed {
        public final String ts;
        public final String level;
        public final String cat;
        public final int pid;
        public final int tid;
        public final String tag;
        public final String msg;

        Parsed(String ts, String level, String cat, int pid, int tid, String tag, String msg) {
            this.ts = ts;
            this.level = level;
            this.cat = cat;
            this.pid = pid;
            this.tid = tid;
            this.tag = tag;
            this.msg = msg;
        }
    }
}
