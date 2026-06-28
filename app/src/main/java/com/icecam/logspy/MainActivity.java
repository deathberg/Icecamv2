package com.icecam.logspy;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IceCam-Log-Spy — logcat monitor with AI-friendly NDJSON export and metadata dump.
 *
 * <p>READ_LOGS must be granted via adb after install:
 * <pre>adb shell pm grant com.icecam.logspy android.permission.READ_LOGS</pre>
 */
public class MainActivity extends Activity {

    private static final int MAX_UI_LINES = 800;
    private static final long UI_FLUSH_MS = 150;
    private static final int COLOR_GREEN = 0xFF00C853;
    private static final int COLOR_RED = 0xFFD50000;
    private static final int COLOR_GRAY = 0xFF757575;

    private TextView tvLog;
    private TextView tvStatus;
    private ScrollView scrollLog;
    private View indicator;
    private Button btnStart;
    private Button btnStop;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final List<String> pendingLines = new ArrayList<>();
    private final StringBuilder screenBuffer = new StringBuilder(64 * 1024);
    private int screenLineCount = 0;
    private boolean flushScheduled = false;
    private boolean recording = false;
    private boolean readerAlive = false;

    private final BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (LogCaptureService.ACTION_LOG_LINE.equals(action)) {
                String line = intent.getStringExtra(LogCaptureService.EXTRA_LINE);
                if (line != null) {
                    enqueueLine(line);
                }
            } else if (LogCaptureService.ACTION_UI_CLEAR.equals(action)) {
                clearScreen();
            }
        }
    };

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (LogCaptureService.ACTION_STATE.equals(intent.getAction())) {
                recording = intent.getBooleanExtra(LogCaptureService.EXTRA_RECORDING, false);
                readerAlive = intent.getBooleanExtra(LogCaptureService.EXTRA_READER_ALIVE, false);
                updateIndicator();
                updateButtonStates();
                updateStatus();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvLog = findViewById(R.id.tv_log);
        tvStatus = findViewById(R.id.tv_status);
        scrollLog = findViewById(R.id.scroll_log);
        indicator = findViewById(R.id.indicator);

        btnStart = findViewById(R.id.btn_start);
        btnStop = findViewById(R.id.btn_stop);
        Button btnExport = findViewById(R.id.btn_export);
        Button btnClear = findViewById(R.id.btn_clear);

        setupIndicator();
        btnStart.setOnClickListener(v -> sendCommand(LogCaptureService.CMD_START));
        btnStop.setOnClickListener(v -> sendCommand(LogCaptureService.CMD_STOP));
        btnExport.setOnClickListener(v -> exportWithMetadata());
        btnClear.setOnClickListener(v -> sendCommand(LogCaptureService.CMD_CLEAR));

        updateStatus();
        updateIndicator();
        updateButtonStates();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter logFilter = new IntentFilter();
        logFilter.addAction(LogCaptureService.ACTION_LOG_LINE);
        logFilter.addAction(LogCaptureService.ACTION_UI_CLEAR);
        IntentFilter stateFilter = new IntentFilter(LogCaptureService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, logFilter, Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(stateReceiver, stateFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(logReceiver, logFilter);
            registerReceiver(stateReceiver, stateFilter);
        }
        requestStateSync();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(logReceiver);
        unregisterReceiver(stateReceiver);
    }

    private void setupIndicator() {
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(COLOR_GRAY);
        indicator.setBackground(circle);
    }

    private void updateIndicator() {
        int color;
        if (recording && readerAlive) {
            color = COLOR_GREEN;
        } else if (recording && !readerAlive) {
            color = COLOR_RED; // logcat died while supposed to be recording
        } else {
            color = COLOR_RED;
        }
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(color);
        indicator.setBackground(circle);
    }

    private void updateButtonStates() {
        btnStart.setEnabled(!recording);
        btnStop.setEnabled(recording);
    }

    private void sendCommand(String cmd) {
        Intent intent = new Intent(this, LogCaptureService.class);
        intent.setAction(LogCaptureService.ACTION_CMD);
        intent.putExtra(LogCaptureService.EXTRA_COMMAND, cmd);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        if (LogCaptureService.CMD_CLEAR.equals(cmd)) {
            clearScreen();
            updateStatus();
        }
    }

    private void requestStateSync() {
        Intent ping = new Intent(this, LogCaptureService.class);
        ping.setAction(LogCaptureService.ACTION_CMD);
        ping.putExtra(LogCaptureService.EXTRA_COMMAND, LogCaptureService.CMD_STATUS);
        startService(ping);
    }

    private void enqueueLine(String line) {
        synchronized (pendingLines) {
            pendingLines.add(line);
        }
        scheduleFlush();
    }

    private void scheduleFlush() {
        if (flushScheduled) {
            return;
        }
        flushScheduled = true;
        uiHandler.postDelayed(this::flushPendingLines, UI_FLUSH_MS);
    }

    private void flushPendingLines() {
        flushScheduled = false;
        List<String> batch;
        synchronized (pendingLines) {
            if (pendingLines.isEmpty()) {
                return;
            }
            batch = new ArrayList<>(pendingLines);
            pendingLines.clear();
        }

        for (String line : batch) {
            screenBuffer.append(line).append('\n');
            screenLineCount++;
        }

        while (screenLineCount > MAX_UI_LINES) {
            int nl = screenBuffer.indexOf("\n");
            if (nl < 0) {
                break;
            }
            screenBuffer.delete(0, nl + 1);
            screenLineCount--;
        }

        tvLog.setText(screenBuffer.toString());
        scrollLog.post(() -> scrollLog.fullScroll(View.FOCUS_DOWN));
    }

    private void clearScreen() {
        screenBuffer.setLength(0);
        screenLineCount = 0;
        tvLog.setText("");
    }

    private void updateStatus() {
        File f = new File(LogCaptureService.LOG_FILE_PATH);
        long kb = f.exists() ? f.length() / 1024 : 0;
        String state = recording ? (readerAlive ? "REC" : "STALLED") : "STOPPED";
        tvStatus.setText("[" + state + "] " + LogCaptureService.LOG_FILE_PATH + " (" + kb + " KB)");
    }

    private String readLogFileContents() {
        File file = new File(LogCaptureService.LOG_FILE_PATH);
        if (!file.exists()) {
            return "";
        }
        StringBuilder sb = new StringBuilder((int) Math.min(file.length(), 1024 * 1024));
        try (BufferedReader br = new BufferedReader(new FileReader(file), 8192)) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (IOException e) {
            return "{\"type\":\"error\",\"msg\":\"" + StructuredLog.jsonEscape(e.getMessage()) + "\"}\n";
        }
        return sb.toString();
    }

    private void exportWithMetadata() {
        String textHeader = SystemMetadata.buildTextHeader(this);
        String jsonHeader = SystemMetadata.buildJsonHeader(this);
        String logBody = readLogFileContents();

        if (TextUtils.isEmpty(logBody)) {
            toast("Log file empty — start recording first");
            return;
        }

        String exportContent = textHeader + jsonHeader + "\n" + logBody;

        try {
            File exportFile = new File(LogCaptureService.EXPORT_FILE_PATH);
            File parent = exportFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (BufferedWriter bw = new BufferedWriter(new FileWriter(exportFile, false), 8192)) {
                bw.write(exportContent);
            }
        } catch (IOException e) {
            toast("Export file write failed: " + e.getMessage());
        }

        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "icecam_export.log");
        send.putExtra(Intent.EXTRA_TEXT, exportContent);
        startActivity(Intent.createChooser(send, getString(R.string.export_chooser)));

        updateStatus();
        toast("Exported with metadata → " + LogCaptureService.EXPORT_FILE_PATH);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // -------------------------------------------------------------------------
    // LogcatReader — ProcessBuilder-based capture (fast raw stream, no logd API)
    // -------------------------------------------------------------------------

    /**
     * Reads logcat via {@link ProcessBuilder}, filters by tag keywords, auto-restarts on crash.
     */
    public static final class LogcatReader implements Runnable {

        private static final String[] TAG_KEYWORDS = {"IceCam", "libvc", "float", "TX"};

        private static final Pattern THREADTIME = Pattern.compile(
                "^\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\s+\\d+\\s+\\d+\\s+."
                        + "\\s+([^:]+):\\s.*");

        public interface Listener {
            void onLogLine(String line);
        }

        /** Optional callbacks for reader lifecycle (life indicator). */
        public interface Lifecycle {
            void onReaderStarted();
            void onReaderStopped();
        }

        private final Listener listener;
        private final Lifecycle lifecycle;
        private volatile boolean running = true;

        public LogcatReader(Listener listener, Lifecycle lifecycle) {
            this.listener = listener;
            this.lifecycle = lifecycle;
        }

        public void stop() {
            running = false;
        }

        @Override
        public void run() {
            while (running) {
                Process process = null;
                try {
                    notifyStarted();
                    process = new ProcessBuilder("logcat", "-v", "threadtime")
                            .redirectErrorStream(true)
                            .start();

                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(process.getInputStream()), 16384)) {
                        String line;
                        while (running && (line = reader.readLine()) != null) {
                            if (matchesFilter(line)) {
                                listener.onLogLine(line);
                            }
                        }
                    }
                } catch (Exception ignored) {
                    // logcat process died — loop restarts below
                } finally {
                    notifyStopped();
                    if (process != null) {
                        process.destroy();
                    }
                }

                if (running) {
                    sleepQuiet(400);
                }
            }
            notifyStopped();
        }

        private void notifyStarted() {
            if (lifecycle != null) {
                lifecycle.onReaderStarted();
            }
        }

        private void notifyStopped() {
            if (lifecycle != null) {
                lifecycle.onReaderStopped();
            }
        }

        static boolean matchesFilter(String line) {
            if (line == null || line.isEmpty()) {
                return false;
            }
            Matcher m = THREADTIME.matcher(line);
            if (!m.matches()) {
                return false;
            }
            String tag = m.group(1);
            return tagContainsKeyword(tag);
        }

        static boolean tagContainsKeyword(String tag) {
            if (tag == null) {
                return false;
            }
            String lower = tag.toLowerCase();
            for (String kw : TAG_KEYWORDS) {
                if (kw.equals("TX")) {
                    if (tag.contains("TX")) {
                        return true;
                    }
                } else if (lower.contains(kw.toLowerCase())) {
                    return true;
                }
            }
            return false;
        }

        private static void sleepQuiet(long ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
