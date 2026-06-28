package dev.icecam.app.runtime;

import android.content.Context;
import android.content.SharedPreferences;
import dev.icecam.app.AppLogger;
import dev.icecam.app.BackendApplyQueue;
import dev.icecam.app.IceCamLog;
import dev.icecam.app.MediaPaths;
import dev.icecam.app.MediaTransformer;
import dev.icecam.app.NativeControlRouter;
import dev.icecam.app.RootBootstrap;
import dev.icecam.app.TransformState;
import dev.icecam.app.VliveBinderClient;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SideEffectRunner {
    private final Context context;
    private final AppLogger log;
    private final RootBootstrap root;
    private final VliveBinderClient binder;
    private final NativeControlRouter controls;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> new Thread(r, "icecam-runtime-effects"));

    public SideEffectRunner(Context context, AppLogger log) {
        this.context = context.getApplicationContext();
        this.log = log;
        this.root = new RootBootstrap(this.context, log);
        this.binder = new VliveBinderClient(log);
        this.binder.setPreferredService(RootBootstrap.FIXED_SERVICE_NAME);
        this.controls = new NativeControlRouter(this.context, log, binder);
    }

    public void run(RuntimeCommand c, AppState state, CommandBus bus) {
        SharedPreferences prefs = context.getSharedPreferences("app_config", Context.MODE_PRIVATE);
        switch (c.type) {
            case SET_LOOP:
                prefs.edit().putBoolean("PlayisLoop", c.boolValue).apply();
                if (binder.connected() && prefs.getBoolean("ReplacementActive", false)) {
                    int r = binder.sendBoolCode(VliveBinderClient.TX_ZERO_17, c.boolValue);
                    if (log != null) log.log("runtime", "TX17 loop=" + c.boolValue + " -> " + r);
                }
                break;
            case MUTATE_TRANSFORM:
                controls.dispatchLiveOp(c.op, state.transform, prefs);
                break;
            case COMMIT:
                io.execute(() -> {
                    String opId = "commit-" + c.id;
                    boolean ok = false;
                    try {
                        IceCamLog.marker(log, "COMMIT", "source=" + c.source.name());
                        controls.sendPlaybackSettings(state.transform, prefs);
                        controls.sendColorCorrectionIfEnabled(prefs);
                        String path = resolveApplyPath(state);
                        if (path.length() > 0) BackendApplyQueue.get(context).enqueue(path, "runtime-commit-" + c.source.name().toLowerCase(), true);
                        ok = true;
                    } catch (Throwable t) { if (log != null) log.log("runtime", "commit side effect failed #" + c.id + ": " + t); }
                    bus.dispatch(RuntimeCommand.opFinished(opId, ok));
                });
                break;
            case START_REPLACEMENT:
                io.execute(() -> {
                    String opId = "start-" + c.id;
                    boolean ok = false;
                    try {
                        IceCamLog.marker(log, "START_STREAM", "source=" + c.source.name());
                        String path = resolveApplyPath(state);
                        if (path.length() > 0) {
                            BackendApplyQueue.get(context).enqueue(path, "runtime-start-" + c.source.name().toLowerCase(), true);
                            ok = true;
                        } else {
                            IceCamLog.w(log, "runtime", "start skipped: no media path");
                        }
                    } catch (Throwable t) { IceCamLog.e(log, "runtime", "start failed #" + c.id + ": " + t); }
                    bus.dispatch(RuntimeCommand.opFinished(opId, ok));
                });
                break;
            case RESTORE_CAMERA:
                io.execute(() -> {
                    String opId = "restore-" + c.id;
                    boolean ok = false;
                    try {
                        IceCamLog.marker(log, "RESTORE_CAMERA", "source=" + c.source.name());
                        root.restoreCamera();
                        binder.clearCache();
                        prefs.edit()
                                .putBoolean("ReplacementActive", false)
                                .putString("IceCamState", "RESTORED")
                                .apply();
                        bus.reloadFromPrefs();
                        ok = true;
                    } catch (Throwable t) { IceCamLog.e(log, "runtime", "restore failed #" + c.id + ": " + t); }
                    bus.dispatch(RuntimeCommand.opFinished(opId, ok));
                });
                break;
            default: break;
        }
    }

    private String resolveApplyPath(AppState state) {
        String path = state.media.originalPath.length() > 0 ? state.media.originalPath : state.media.playPath;
        if (path.length() == 0) return "";
        if (MediaTransformer.isImagePath(path)) {
            String baked = MediaTransformer.bakeImage(context, path, state.transform, log);
            if (baked != null && baked.length() > 0) {
                context.getSharedPreferences("app_config", Context.MODE_PRIVATE).edit()
                        .putString("PlayFileMp4", baked)
                        .putInt("PlayFileType", MediaPaths.TYPE_LOCAL_FILE)
                        .apply();
                return baked;
            }
        }
        return path;
    }
}
