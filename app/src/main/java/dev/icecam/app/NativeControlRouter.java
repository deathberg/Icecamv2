package dev.icecam.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Maps UI ops to correct Binder transactions per Oldicecam RE:
 * TX16-19 playback, TX24 color only, preview geometry is local-only for video.
 */
public final class NativeControlRouter {
    private final Context context;
    private final AppLogger log;
    private final VliveBinderClient binder;

    public NativeControlRouter(Context context, AppLogger log, VliveBinderClient binder) {
        this.context = context.getApplicationContext();
        this.log = log;
        this.binder = binder;
        binder.setPreferredService(RootBootstrap.FIXED_SERVICE_NAME);
    }

    public boolean dispatchLiveOp(String op, TransformState transform, SharedPreferences prefs) {
        if (!binder.connected()) {
            if (log != null) log.log("control", op + " skipped: binder down");
            return false;
        }
        boolean active = prefs.getBoolean("ReplacementActive", false);
        switch (op) {
            case "rot+90":
            case "rotate":
                transform.rotate90();
                transform.save(prefs);
                return sendAngle(transform, prefs);
            case "rot-90":
                transform.rotateMinus90();
                transform.save(prefs);
                return sendAngle(transform, prefs);
            case "mirror":
            case "mirror-x":
                transform.toggleMirrorH();
                transform.save(prefs);
                return sendMirror(transform, prefs);
            case "mirror-y":
                transform.toggleMirrorV();
                transform.save(prefs);
                return previewOnly(op);
            case "loop-toggle":
                return sendLoop(prefs);
            case "auto-rotate-toggle":
                transform.toggleAutoRotate();
                transform.save(prefs);
                return sendAutoRotate(transform, prefs);
            case "color-int+":
            case "zoom+":
                return adjustColor(prefs, 0.12f, 0f);
            case "color-int-":
            case "zoom-":
                return adjustColor(prefs, -0.12f, 0f);
            case "color-dia+":
                return adjustColor(prefs, 0f, 0.12f);
            case "color-dia-":
                return adjustColor(prefs, 0f, -0.12f);
            case "color-reset":
                return resetColor(prefs);
            case "up":
            case "down":
            case "left":
            case "right":
            case "center":
            case "fit-fill":
            case "crop":
            case "reset":
                return previewOnly(op);
            default:
                if (log != null) log.log("control", "unknown op=" + op + " preview-only");
                return previewOnly(op);
        }
    }

    public boolean sendPlaybackSettings(TransformState transform, SharedPreferences prefs) {
        if (!binder.connected()) return false;
        return binder.applyPlaybackSettings(transform, prefs.getBoolean("PlayisLoop", true)) >= 0;
    }

    public boolean sendColorCorrection(SharedPreferences prefs) {
        if (!binder.connected()) return false;
        if (!prefs.getBoolean("ReplacementActive", false)) return false;
        ColorCorrectionState c = ColorCorrectionState.load(prefs);
        int r = binder.setColorCorrection(c);
        if (log != null) log.log("control", "TX24 color result=" + r + " " + c.summary());
        return r >= 0;
    }

    public boolean resetSeekRange() {
        if (!binder.connected()) return false;
        int r = binder.setRange(0, -1);
        if (log != null) log.log("control", "TX22 seek reset 0..-1 -> " + r);
        return r >= 0;
    }

    private boolean sendAngle(TransformState t, SharedPreferences prefs) {
        int r = binder.sendIntCode(VliveBinderClient.TX_INT_18, t.rotationQuadrant() * 90);
        if (log != null) log.log("control", "TX18 angle=" + (t.rotationQuadrant() * 90) + " -> " + r);
        return r >= 0;
    }

    private boolean sendMirror(TransformState t, SharedPreferences prefs) {
        int r = binder.sendBoolCode(VliveBinderClient.TX_ZERO_19, t.mirrorH());
        if (log != null) log.log("control", "TX19 mirror=" + t.mirrorH() + " -> " + r);
        return r >= 0;
    }

    private boolean sendLoop(SharedPreferences prefs) {
        boolean loop = !prefs.getBoolean("PlayisLoop", true);
        prefs.edit().putBoolean("PlayisLoop", loop).apply();
        int r = binder.sendBoolCode(VliveBinderClient.TX_ZERO_17, loop);
        if (log != null) log.log("control", "TX17 loop=" + loop + " -> " + r);
        return r >= 0;
    }

    private boolean sendAutoRotate(TransformState t, SharedPreferences prefs) {
        int r = binder.sendBoolCode(VliveBinderClient.TX_ZERO_16, t.autoRotate());
        if (log != null) log.log("control", "TX16 autoRotate=" + t.autoRotate() + " -> " + r);
        return r >= 0;
    }

    private boolean adjustColor(SharedPreferences prefs, float dIntensity, float dDiameter) {
        ColorCorrectionState c = ColorCorrectionState.load(prefs);
        if (dIntensity != 0f) c.adjustIntensity(dIntensity);
        if (dDiameter != 0f) c.adjustDiameter(dDiameter);
        c.save(prefs);
        if (!prefs.getBoolean("ReplacementActive", false)) {
            if (log != null) log.log("control", "color adjusted preview-only " + c.summary());
            return true;
        }
        int r = binder.setColorCorrection(c);
        if (log != null) log.log("control", "TX24 color adjust -> " + r + " " + c.summary());
        return r >= 0;
    }

    private boolean resetColor(SharedPreferences prefs) {
        ColorCorrectionState c = new ColorCorrectionState();
        c.save(prefs);
        if (!prefs.getBoolean("ReplacementActive", false)) return true;
        int r = binder.setColorCorrection(c);
        if (log != null) log.log("control", "TX24 color reset -> " + r);
        return r >= 0;
    }

    private boolean previewOnly(String op) {
        if (log != null) log.log("control", op + " preview-only (no TX24 geometry)");
        return true;
    }
}
