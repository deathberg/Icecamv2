package dev.icecam.app;

import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class VliveBinderClient {
    public static final String DESCRIPTOR = "com.xiaomi.vlive.IMyBinderService";
    public static final int TX_PLAY_SOURCE = 11, TX_STATUS = 12, TX_INT_ARRAY = 13, TX_MODE_STRING = 14,
            TX_GET_INT = 15, TX_ZERO_16 = 16, TX_ZERO_17 = 17, TX_INT_18 = 18, TX_ZERO_19 = 19,
            TX_RANGE = 22, TX_TRANSFORM = 24, TX_25 = 25;

    /** Native success codes from RE (TX14/TX11 may return 1 or 4 depending on build). */
    public static final int OK_SET_MODE = 4;
    public static final int OK_PLAY = 1;
    public static final int OK_PLAY_ALT = 4;
    public static final int STATUS_PLAYING = 5;

    private final AppLogger log;
    private String preferredService = RootBootstrap.FIXED_SERVICE_NAME;
    private String lastError = "not connected";
    private IBinder cachedBinder = null;
    private String cachedName = null;
    private String lastTxSummary = "none";

    private final List<String> candidates = new ArrayList<>(Arrays.asList(
            RootBootstrap.FIXED_SERVICE_NAME,
            "com.xiaomi.vlive.IMyBinderService",
            "vlive",
            "vlive_service",
            "vcplax",
            "MyBinderService"));

    public VliveBinderClient(AppLogger logger) { log = logger; }
    public void setPreferredService(String s) {
        if (s != null && s.trim().length() > 0) {
            String n = s.trim();
            if (!n.equals(preferredService)) { cachedBinder = null; cachedName = null; }
            preferredService = n;
        }
    }
    public String preferredService() { return preferredService; }
    public String lastError() { return lastError; }
    public String lastTxSummary() { return lastTxSummary; }
    public void clearCache() { cachedBinder = null; cachedName = null; lastError = "cache cleared"; }
    public void release() { clearCache(); }

    public String[] listServices() {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method m = sm.getDeclaredMethod("listServices");
            String[] arr = (String[]) m.invoke(null);
            if (arr != null) return arr;
        } catch (Throwable t) { lastError = "listServices: " + t; }
        return new String[0];
    }

    private IBinder getServiceByName(String name) {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method m = sm.getDeclaredMethod("getService", String.class);
            IBinder b = (IBinder) m.invoke(null, name);
            if (b != null && b.isBinderAlive()) return b;
        } catch (Throwable t) { lastError = "getService(" + name + "): " + t; }
        return null;
    }

    public IBinder service() {
        if (cachedBinder != null && cachedBinder.isBinderAlive()) {
            preferredService = cachedName != null ? cachedName : preferredService;
            lastError = "connected cached service=" + preferredService;
            return cachedBinder;
        }

        ArrayList<String> names = new ArrayList<>();
        names.add(preferredService);
        for (String c : candidates) if (!names.contains(c)) names.add(c);
        for (String name : names) {
            IBinder b = getServiceByName(name);
            if (b == null) continue;

            if (RootBootstrap.FIXED_SERVICE_NAME.equals(name) || "vcplax".equals(name) || name.equals(preferredService)) {
                cachedBinder = b;
                cachedName = name;
                preferredService = name;
                lastError = "connected raw service=" + name;
                IceCamLog.i(log, "binder", lastError);
                return b;
            }

            if (probeDescriptor(b, name)) {
                cachedBinder = b;
                cachedName = name;
                preferredService = name;
                lastError = "connected probed service=" + name;
                IceCamLog.i(log, "binder", lastError);
                return b;
            }
            IceCamLog.w(log, "binder", "reject service=" + name + " descriptor/probe mismatch");
        }
        lastError = "VLive binder not found. service=" + preferredService + " not available";
        return null;
    }

    private boolean probeDescriptor(IBinder b, String name) {
        try {
            String d = b.getInterfaceDescriptor();
            if (DESCRIPTOR.equals(d)) return true;
            if (d != null && d.length() > 0 && !d.equals(DESCRIPTOR)) {
                lastError = "service " + name + " has descriptor " + d;
                return false;
            }
        } catch (Throwable ignored) {}
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            boolean ok = b.transact(TX_STATUS, data, reply, 0);
            if (!ok) return false;
            reply.readException();
            return true;
        } catch (Throwable t) {
            lastError = "probe(" + name + "): " + t.getClass().getSimpleName() + ": " + t.getMessage();
            return false;
        } finally { data.recycle(); reply.recycle(); }
    }

    public boolean connected() { IBinder b = service(); return b != null && b.isBinderAlive(); }

    private int transactInt(int code, Parcel data, String sendSummary) {
        Parcel reply = Parcel.obtain();
        long t0 = SystemClock.elapsedRealtime();
        try {
            IBinder b = service();
            if (b == null) {
                lastTxSummary = "TX" + code + " skipped: " + lastError;
                IceCamLog.w(log, "tx", lastTxSummary);
                return -999;
            }
            boolean ok = b.transact(code, data, reply, 0);
            if (!ok) {
                lastError = "transact returned false code=" + code;
                lastTxSummary = "TX" + code + " failed transact=false";
                IceCamLog.w(log, "tx", lastTxSummary);
                return -997;
            }
            reply.readException();
            int value = reply.dataAvail() >= 4 ? reply.readInt() : 0;
            long ms = SystemClock.elapsedRealtime() - t0;
            lastTxSummary = String.format(Locale.US, "TX%d -> %d via %s", code, value, preferredService);
            IceCamLog.tx(log, code, IceCamLog.txName(code), sendSummary, value, ms);
            return value;
        } catch (Throwable t) {
            lastError = "TX" + code + ": " + t.getClass().getSimpleName() + ": " + t.getMessage();
            lastTxSummary = lastError;
            IceCamLog.e(log, "tx", lastError);
            return -998;
        } finally { reply.recycle(); data.recycle(); }
    }

    public static boolean isPlayOk(int r) { return r == OK_PLAY || r == OK_PLAY_ALT; }
    public static boolean isSetModeOk(int r) { return r == OK_SET_MODE; }

    public int playSource(String path, boolean mirrorFlagIgnoredByOriginal, boolean loopFlag) {
        Parcel p = Parcel.obtain();
        p.writeInterfaceToken(DESCRIPTOR);
        p.writeString(path);
        p.writeInt(0);
        p.writeInt(loopFlag ? 1 : 0);
        return transactInt(TX_PLAY_SOURCE, p,
                "path=" + path + " loop=" + loopFlag);
    }

    public int setModeString(int mode, String value) {
        Parcel p = Parcel.obtain();
        p.writeInterfaceToken(DESCRIPTOR);
        p.writeInt(mode);
        p.writeString(value);
        return transactInt(TX_MODE_STRING, p, "mode=" + mode + " path=" + value);
    }

    public int statusCode() {
        Parcel p = Parcel.obtain();
        p.writeInterfaceToken(DESCRIPTOR);
        return transactInt(TX_STATUS, p, "");
    }

    public int getInt15() {
        Parcel p = Parcel.obtain();
        p.writeInterfaceToken(DESCRIPTOR);
        return transactInt(TX_GET_INT, p, "");
    }

    public int setRange(long startMs, long endMs) {
        Parcel p = Parcel.obtain();
        p.writeInterfaceToken(DESCRIPTOR);
        p.writeLong(startMs);
        p.writeLong(endMs);
        return transactInt(TX_RANGE, p, "seekMs=" + startMs + ".." + endMs);
    }

    public int setTransform(int mode, float panX, float panY, float zoomX, float zoomY, int flags) {
        Parcel p = Parcel.obtain();
        p.writeInterfaceToken(DESCRIPTOR);
        p.writeInt(mode);
        p.writeFloat(panX);
        p.writeFloat(panY);
        p.writeFloat(zoomX);
        p.writeFloat(zoomY);
        p.writeInt(flags);
        return transactInt(TX_TRANSFORM, p, String.format(Locale.US,
                "LEGACY_GEOM mode=%d pan=(%.2f,%.2f) zoom=(%.2f,%.2f) flags=0x%08X", mode, panX, panY, zoomX, zoomY, flags));
    }

    /** Native TX24 «三色» — mode, x, y, intensity, diameter, colorArgb (NOT zoom/pan). */
    public int setColorCorrection(ColorCorrectionState c) {
        if (c == null) return -999;
        Parcel p = Parcel.obtain();
        p.writeInterfaceToken(DESCRIPTOR);
        p.writeInt(c.mode);
        p.writeFloat(c.x);
        p.writeFloat(c.y);
        p.writeFloat(c.intensity);
        p.writeFloat(c.diameter);
        p.writeInt(c.colorArgb);
        return transactInt(TX_TRANSFORM, p, String.format(Locale.US,
                "color mode=%d xy=(%.2f,%.2f) int=%.2f dia=%.2f argb=0x%08X",
                c.mode, c.x, c.y, c.intensity, c.diameter, c.colorArgb));
    }

    public int setTransform(TransformState s) {
        return setTransform(s.mode, s.panX, s.panY, s.zoomX, s.zoomY, s.flags);
    }

    /** Apply native playback display settings per RE (TX16–TX19). */
    public int applyPlaybackSettings(TransformState s, boolean loop) {
        int tx16 = sendBoolCode(TX_ZERO_16, s.autoRotate());
        int tx17 = sendBoolCode(TX_ZERO_17, loop);
        int tx18 = sendIntCode(TX_INT_18, s.rotationQuadrant() * 90);
        int tx19 = sendBoolCode(TX_ZERO_19, s.mirrorH());
        IceCamLog.i(log, "tx", String.format(java.util.Locale.US,
                "playback settings TX16=%d TX17=%d TX18=%d TX19=%d loop=%s rot=%d mir=%s",
                tx16, tx17, tx18, tx19, loop, s.rotationQuadrant() * 90, s.mirrorH()));
        return (tx16 >= 0 && tx17 >= 0 && tx18 >= 0 && tx19 >= 0) ? 0 : -1;
    }

    public int sendBoolCode(int code, boolean v) { Parcel p = Parcel.obtain(); p.writeInterfaceToken(DESCRIPTOR); p.writeInt(v ? 1 : 0); return transactInt(code, p, "bool=" + v); }
    public int sendIntCode(int code, int v) { Parcel p = Parcel.obtain(); p.writeInterfaceToken(DESCRIPTOR); p.writeInt(v); return transactInt(code, p, "int=" + v); }
    public int simple(int code) { Parcel p = Parcel.obtain(); p.writeInterfaceToken(DESCRIPTOR); return transactInt(code, p, ""); }

    public int[] pollState() {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        long t0 = SystemClock.elapsedRealtime();
        try {
            IBinder b = service();
            if (b == null) {
                IceCamLog.w(log, "poll", "TX13 skipped: " + lastError);
                return new int[0];
            }
            data.writeInterfaceToken(DESCRIPTOR);
            boolean ok = b.transact(TX_INT_ARRAY, data, reply, 0);
            if (!ok) {
                lastError = "TX13 transact returned false";
                return new int[0];
            }
            reply.readException();
            int[] counters = new int[5];
            for (int i = 0; i < counters.length; i++) {
                counters[i] = reply.dataAvail() >= 4 ? reply.readInt() : 0;
            }
            long ms = SystemClock.elapsedRealtime() - t0;
            IceCamLog.tx(log, TX_INT_ARRAY, "POLL_STATE", "poll", counters.length > 0 ? counters[0] : 0, ms);
            return counters;
        } catch (Throwable t) {
            lastError = "TX13: " + t.getClass().getSimpleName() + ": " + t.getMessage();
            IceCamLog.e(log, "poll", lastError);
            return new int[0];
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    public String diagnostics() {
        StringBuilder sb = new StringBuilder();
        sb.append("preferred=").append(preferredService).append('\n');
        sb.append("connected=").append(connected()).append('\n');
        sb.append("lastError=").append(lastError).append('\n');
        sb.append("lastTx=").append(lastTxSummary).append('\n');
        sb.append("expected descriptor=").append(DESCRIPTOR).append('\n');
        for (String c : candidates) {
            IBinder b = getServiceByName(c);
            sb.append("probe ").append(c).append('=').append(b != null && b.isBinderAlive() ? "alive" : "missing").append('\n');
        }
        return sb.toString();
    }
}
