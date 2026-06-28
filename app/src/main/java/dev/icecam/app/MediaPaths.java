package dev.icecam.app;

import java.util.Locale;

/** TX14 / prefs helpers aligned with reference APK (mode 1 = local file, 2 = RTMP). */
public final class MediaPaths {
    private MediaPaths() {}

    public static final int TYPE_LOCAL_FILE = 1;
    public static final int TYPE_RTMP = 2;

    public static boolean isRtmpUrl(String path) {
        if (path == null) return false;
        String p = path.toLowerCase(Locale.US);
        return p.startsWith("rtmp://") || p.startsWith("rtmps://");
    }

    /** TX14 mode: 1 for MP4/JPEG on disk, 2 for RTMP URL. */
    public static int tx14Mode(String path) {
        return isRtmpUrl(path) ? TYPE_RTMP : TYPE_LOCAL_FILE;
    }

    /** PlayFileType pref: 1 = local file (MP4/image), 2 = RTMP stream. */
    public static int playFileType(String path) {
        return isRtmpUrl(path) ? TYPE_RTMP : TYPE_LOCAL_FILE;
    }
}
