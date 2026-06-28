package com.icecam.logspy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Foreground service — log capture with explicit START/STOP control.
 * Writes NDJSON lines via {@link StructuredLog}; broadcasts state for life indicator.
 */
public class LogCaptureService extends Service
        implements MainActivity.LogcatReader.Listener, MainActivity.LogcatReader.Lifecycle {

    public static final String ACTION_CMD = "com.icecam.logspy.CMD";
    public static final String EXTRA_COMMAND = "command";
    public static final String CMD_START = "start";
    public static final String CMD_STOP = "stop";
    public static final String CMD_CLEAR = "clear";
    public static final String CMD_STATUS = "status";

    public static final String ACTION_UI_CLEAR = "com.icecam.logspy.UI_CLEAR";

    public static final String ACTION_LOG_LINE = "com.icecam.logspy.LOG_LINE";
    public static final String EXTRA_LINE = "line";

    public static final String ACTION_STATE = "com.icecam.logspy.STATE";
    public static final String EXTRA_RECORDING = "recording";
    public static final String EXTRA_READER_ALIVE = "reader_alive";
    public static final String EXTRA_STATUS_MSG = "status_msg";
    public static final String EXTRA_LOG_PATH = "log_path";

    private static final String TAG = "IceCamLogSpy";
    private static final String CHANNEL_ID = "log_capture";
    private static final int NOTIFICATION_ID = 1;

    private final AtomicBoolean capturing = new AtomicBoolean(false);
    private final AtomicBoolean readerAlive = new AtomicBoolean(false);
    private MainActivity.LogcatReader reader;
    private Thread readerThread;
    private BufferedWriter fileWriter;
    private PowerManager.WakeLock wakeLock;
    private String statusMsg = "Idle";
    private File logFile;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        logFile = LogPaths.getLogFile(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CMD.equals(intent.getAction())) {
            String cmd = intent.getStringExtra(EXTRA_COMMAND);
            if (CMD_START.equals(cmd)) {
                handleStart();
            } else if (CMD_STOP.equals(cmd)) {
                handleStop();
            } else if (CMD_CLEAR.equals(cmd)) {
                handleClear();
            } else if (CMD_STATUS.equals(cmd)) {
                broadcastState();
            }
        } else {
            broadcastState();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopCaptureInternal();
        releaseWakeLock();
        closeLogFile();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onLogLine(String rawLine) {
        StructuredLog.Parsed parsed = StructuredLog.parse(rawLine);
        if (parsed == null) {
            return;
        }
        String jsonLine = StructuredLog.toJsonLine(parsed);
        String uiLine = StructuredLog.toUiLine(parsed);
        writeToFile(jsonLine);
        broadcastLine(uiLine);
    }

    @Override
    public void onReaderStarted() {
        readerAlive.set(true);
        statusMsg = "Recording";
        broadcastState();
    }

    @Override
    public void onReaderStopped() {
        readerAlive.set(false);
        if (capturing.get()) {
            statusMsg = "Logcat stalled — grant READ_LOGS via adb";
        }
        broadcastState();
    }

    @Override
    public void onReaderError(String message) {
        readerAlive.set(false);
        statusMsg = message;
        Log.e(TAG, message);
        broadcastState();
    }

    private void handleStart() {
        // Must enter foreground immediately (Android 8+ timeout, Android 14+ type).
        try {
            enterForeground(true);
        } catch (Exception e) {
            statusMsg = "FGS error: " + e.getMessage();
            Log.e(TAG, statusMsg, e);
            broadcastState();
            return;
        }

        acquireWakeLock();
        logFile = LogPaths.getLogFile(this);

        if (!openLogFile()) {
            statusMsg = "File write failed — logs on screen only";
            Log.w(TAG, statusMsg);
        } else {
            statusMsg = "Recording → " + logFile.getAbsolutePath();
        }

        // Always start logcat even if file open failed (UI capture still works).
        startCaptureInternal();
        broadcastState();
    }

    private void handleStop() {
        stopCaptureInternal();
        releaseWakeLock();
        writeSessionEvent("stop");
        closeLogFile();
        exitForeground();
        statusMsg = "Stopped";
        broadcastState();
    }

    private void handleClear() {
        closeLogFile();
        LogPaths.reset();
        logFile = LogPaths.getLogFile(this);
        if (logFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            logFile.delete();
        }
        clearScreenBroadcast();
        if (capturing.get()) {
            openLogFile();
            writeSessionEvent("clear");
        }
        statusMsg = capturing.get() ? "Recording (cleared)" : "Cleared";
        broadcastState();
    }

    private void startCaptureInternal() {
        if (capturing.get()) {
            // Already running — restart reader if it died.
            if (readerThread == null || !readerThread.isAlive()) {
                readerAlive.set(false);
                reader = new MainActivity.LogcatReader(this, this);
                readerThread = new Thread(reader, "logcat-reader");
                readerThread.setDaemon(true);
                readerThread.start();
            }
            return;
        }
        capturing.set(true);
        writeSessionEvent("start");
        reader = new MainActivity.LogcatReader(this, this);
        readerThread = new Thread(reader, "logcat-reader");
        readerThread.setDaemon(true);
        readerThread.start();
        // Self-test line — tag contains "IceCam", confirms pipeline end-to-end.
        Log.i("IceCamLogSpy", "Capture started → " + logFile.getAbsolutePath());
    }

    private void stopCaptureInternal() {
        capturing.set(false);
        readerAlive.set(false);
        if (reader != null) {
            reader.stop();
        }
        if (readerThread != null) {
            readerThread.interrupt();
            try {
                readerThread.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            readerThread = null;
        }
        reader = null;
    }

    private boolean openLogFile() {
        closeLogFile();
        try {
            File parent = logFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            fileWriter = new BufferedWriter(new FileWriter(logFile, true), 8192);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Cannot open " + logFile + ": " + e.getMessage());
            fileWriter = null;
            return false;
        }
    }

    private void closeLogFile() {
        if (fileWriter != null) {
            try {
                fileWriter.flush();
                fileWriter.close();
            } catch (IOException ignored) {
            }
            fileWriter = null;
        }
    }

    private void writeSessionEvent(String event) {
        writeToFile(StructuredLog.sessionEventJson(event));
    }

    private void writeToFile(String line) {
        if (fileWriter == null) {
            return;
        }
        try {
            fileWriter.write(line);
            fileWriter.write('\n');
            fileWriter.flush();
        } catch (IOException e) {
            Log.e(TAG, "File write failed: " + e.getMessage());
            statusMsg = "File write error";
        }
    }

    private void broadcastLine(String uiLine) {
        Intent intent = new Intent(ACTION_LOG_LINE);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_LINE, uiLine);
        sendBroadcast(intent);
    }

    private void clearScreenBroadcast() {
        Intent intent = new Intent(ACTION_UI_CLEAR);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    void broadcastState() {
        Intent intent = new Intent(ACTION_STATE);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_RECORDING, capturing.get());
        intent.putExtra(EXTRA_READER_ALIVE, readerAlive.get());
        intent.putExtra(EXTRA_STATUS_MSG, statusMsg);
        if (logFile != null) {
            intent.putExtra(EXTRA_LOG_PATH, logFile.getAbsolutePath());
        }
        sendBroadcast(intent);
    }

    private void enterForeground(boolean recording) {
        Notification notification = buildNotification(recording);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void exitForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH);
        } else {
            stopForeground(false);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_title),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(boolean recording) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        String text = recording
                ? getString(R.string.notification_text_recording)
                : getString(R.string.notification_text_idle);

        return builder
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentIntent(pi)
                .setOngoing(recording)
                .build();
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            return;
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "icecam:logspy");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }
}
