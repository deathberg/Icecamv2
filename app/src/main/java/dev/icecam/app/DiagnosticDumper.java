package dev.icecam.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

public final class DiagnosticDumper {
    private DiagnosticDumper() {}

    public static String build(Context ctx, AppLogger log, VliveBinderClient binder) {
        StringBuilder sb = new StringBuilder(12000);
        SharedPreferences prefs = ctx.getSharedPreferences("app_config", Context.MODE_PRIVATE);
        sb.append("===== ICECAM DIAGNOSTIC SNAPSHOT =====\n");
        sb.append("time=").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date())).append('\n');
        sb.append("package=").append(ctx.getPackageName()).append('\n');
        sb.append("icecamBuild=").append(BuildInfo.BUILD_LABEL).append(' ').append(BuildInfo.VERSION_NAME).append(" code=").append(BuildInfo.VERSION_CODE).append('\n');
        sb.append("build=SDK ").append(Build.VERSION.SDK_INT).append(" device=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" abi=");
        if (Build.SUPPORTED_ABIS != null) for (String a : Build.SUPPORTED_ABIS) sb.append(a).append(' ');
        sb.append("\n");
        Runtime rt = Runtime.getRuntime();
        sb.append("javaHeapKB used=").append((rt.totalMemory() - rt.freeMemory()) / 1024L)
                .append(" total=").append(rt.totalMemory() / 1024L)
                .append(" max=").append(rt.maxMemory() / 1024L).append('\n');
        sb.append("logFile=").append(log != null && log.file() != null ? log.file().getAbsolutePath() : "<none>").append('\n');

        sb.append("\n--- state ---\n");
        sb.append("ReplacementActive=").append(prefs.getBoolean("ReplacementActive", false)).append('\n');
        sb.append("IceCamState=").append(prefs.getString("IceCamState", "IDLE")).append('\n');
        sb.append("PollCounters=").append(prefs.getString("PollCounters", "")).append('\n');
        sb.append("PollTx15=").append(prefs.getInt("PollTx15", -1)).append('\n');
        sb.append("PlayFileMp4=").append(prefs.getString("PlayFileMp4", "")).append('\n');
        sb.append("ActiveSlot=").append(prefs.getInt("ActiveSlot", 1)).append('\n');

        sb.append("\n--- prefs (core) ---\n");
        String[] keys = {"ServerName", "TransformMode", "PlayisLoop", "PlayMirror", "PlayAngle", "EnableTx24Color", "LastTransformReason"};
        for (String k : keys) {
            if (prefs.contains(k)) sb.append(k).append('=').append(prefs.getAll().get(k)).append('\n');
        }

        sb.append("\n--- files ---\n");
        appendDir(sb, ctx.getFilesDir(), "files", 2);
        appendDir(sb, ctx.getExternalFilesDir(null), "externalFiles", 3);

        sb.append("\n--- binder-java ---\n");
        try { sb.append(binder != null ? binder.diagnostics() : "binder=null\n"); } catch (Throwable t) { sb.append("binder diagnostics failed: ").append(t).append('\n'); }

        sb.append("\n--- root-native ---\n");
        try {
            Shell.Result r = Shell.su("" +
                    "echo ---id---; id; " +
                    "echo ---getenforce---; getenforce 2>/dev/null; " +
                    "echo ---service-check---; service check privsam_service 2>&1; " +
                    "echo ---process---; ps -A | grep -iE 'vcplax|cameraserver' 2>/dev/null; " +
                    "echo ---lib-sizes---; wc -c /data/libvc.so /data/libvc++.so /data/camera/libvc.so /data/camera/vcplax 2>&1; " +
                    "echo ---data-camera---; ls -l /data/camera 2>&1; " +
                    "echo ---vcplax.log---; tail -80 /data/camera/vcplax.log 2>&1; " +
                    "echo ---vcplax.err---; tail -80 /data/camera/vcplax.err 2>&1; " +
                    "echo ---logcat-icecam---; logcat -d -v time -t 120 -s IceCam:* 2>/dev/null");
            sb.append(r.all()).append('\n');
        } catch (Throwable t) { sb.append("root dump failed: ").append(t).append('\n'); }
        sb.append("===== END ICECAM DIAGNOSTIC SNAPSHOT =====\n");
        return sb.toString();
    }

    private static void appendDir(StringBuilder sb, File dir, String label, int max) {
        sb.append(label).append('=').append(dir == null ? "<null>" : dir.getAbsolutePath()).append('\n');
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        int n = Math.min(files.length, max);
        for (int i = 0; i < n; i++) {
            File f = files[i];
            sb.append("  ").append(f.isDirectory() ? "d " : "f ").append(f.length()).append(' ')
                    .append(new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date(f.lastModified())))
                    .append(' ').append(f.getName()).append('\n');
        }
        if (files.length > n) sb.append("  ... total=").append(files.length).append('\n');
    }
}
