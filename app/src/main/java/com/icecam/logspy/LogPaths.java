package com.icecam.logspy;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.IOException;

/** Resolves a writable path for the debug log across Android versions. */
public final class LogPaths {

    public static final String EXPORT_NAME = "icecam_export.log";

    private static volatile File logFile;
    private static volatile File exportFile;

    private LogPaths() {
    }

    public static File getLogFile(Context context) {
        if (logFile == null) {
            logFile = resolveWritableFile(context, "icecam_debug.log");
        }
        return logFile;
    }

    public static File getExportFile(Context context) {
        if (exportFile == null) {
            exportFile = resolveWritableFile(context, EXPORT_NAME);
        }
        return exportFile;
    }

    /** Force re-resolve (e.g. after CLEAR). */
    public static void reset() {
        logFile = null;
        exportFile = null;
    }

    private static File resolveWritableFile(Context context, String name) {
        File[] candidates = {
                new File(Environment.getExternalStorageDirectory(), name),
                new File("/sdcard/" + name),
                new File("/storage/emulated/0/" + name),
                new File(context.getExternalFilesDir(null), name),
                new File(context.getFilesDir(), name),
        };
        for (File file : candidates) {
            if (ensureWritable(file)) {
                return file;
            }
        }
        return new File(context.getFilesDir(), name);
    }

    private static boolean ensureWritable(File file) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return false;
            }
            if (!file.exists() && !file.createNewFile()) {
                return false;
            }
            return file.canWrite();
        } catch (IOException e) {
            return false;
        }
    }
}
