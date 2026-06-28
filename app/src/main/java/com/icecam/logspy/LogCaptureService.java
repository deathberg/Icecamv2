package com.icecam.logspy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
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
 * Foreground service that keeps log capture alive even if the UI or Binder stack dies.
 * Uses {@link MainActivity.LogcatReader} (ProcessBuilder) defined in MainActivity.java.
 */
public class LogCaptureService extends Service implements MainActivity.LogcatReader.Listener {

    public static final String ACTION_LOG_LINE = "com.icecam.logspy.LOG_LINE";
    public static final String EXTRA_LINE = "line";

    private static final String TAG = "IceCamLogSpy";
    private static final String CHANNEL_ID = "log_capture";
    private static final int NOTIFICATION_ID = 1;

    /** Persistent log path requested in spec. */
    public static final String LOG_FILE_PATH = "/sdcard/icecam_debug.log";

    private final AtomicBoolean running = new AtomicBoolean(false);
    private MainActivity.LogcatReader reader;
    private Thread readerThread;
    private BufferedWriter fileWriter;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        acquireWakeLock();
        openLogFile();
        startCapture();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification());
        if (!running.get()) {
            startCapture();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopCapture();
        releaseWakeLock();
        closeLogFile();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onLogLine(String line) {
        writeToFile(line);
        broadcastLine(line);
    }

    private void startCapture() {
        if (running.getAndSet(true)) {
            return;
        }
        reader = new MainActivity.LogcatReader(this);
        readerThread = new Thread(reader, "logcat-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void stopCapture() {
        running.set(false);
        if (reader != null) {
            reader.stop();
        }
        if (readerThread != null) {
            readerThread.interrupt();
            readerThread = null;
        }
    }

    private void openLogFile() {
        try {
            File file = new File(LOG_FILE_PATH);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            fileWriter = new BufferedWriter(new FileWriter(file, true), 8192);
            fileWriter.write("=== IceCam-Log-Spy session start ===\n");
            fileWriter.flush();
        } catch (IOException e) {
            Log.e(TAG, "Cannot open " + LOG_FILE_PATH + ": " + e.getMessage());
            fileWriter = null;
        }
    }

    private void closeLogFile() {
        if (fileWriter != null) {
            try {
                fileWriter.write("=== session end ===\n");
                fileWriter.flush();
                fileWriter.close();
            } catch (IOException ignored) {
            }
            fileWriter = null;
        }
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
        }
    }

    private void broadcastLine(String line) {
        Intent intent = new Intent(ACTION_LOG_LINE);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_LINE, line);
        sendBroadcast(intent);
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

    private Notification buildNotification() {
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

        return builder
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void acquireWakeLock() {
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
        wakeLock = null;
    }
}
