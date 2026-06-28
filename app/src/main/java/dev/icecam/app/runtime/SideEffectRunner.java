package dev.icecam.app.runtime;

import android.content.Context;
import dev.icecam.app.AppLogger;
import dev.icecam.app.BackendApplyQueue;
import dev.icecam.app.IceCamLog;
import dev.icecam.app.RootBootstrap;
import dev.icecam.app.MediaPaths;
import dev.icecam.app.MediaTransformer;
import dev.icecam.app.TransformState;
import dev.icecam.app.VliveBinderClient;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SideEffectRunner {
    private final Context context;
    private final AppLogger log;
    private final RootBootstrap root;
    private final VliveBinderClient binder;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> new Thread(r, "icecam-runtime-effects"));

    public SideEffectRunner(Context context, AppLogger log) {
        this.context = context.getApplicationContext();
        this.log = log;
        this.root = new RootBootstrap(this.context, log);
        this.binder = new VliveBinderClient(log);
        this.binder.setPreferredService(RootBootstrap.FIXED_SERVICE_NAME);
    }

    public void run(RuntimeCommand c, AppState state, CommandBus bus) {
        switch (c.type) {
            case MUTATE_TRANSFORM:
                // Realtime preview only. No JPEG bake, no backend replay.
                sendTransformBestEffort(state.transform);
                break;
            case COMMIT:
                io.execute(() -> {
                    String opId = "commit-" + c.id;
                    boolean ok = false;
                    try {
                        IceCamLog.marker(log, "COMMIT", "source=" + c.source.name());
                        ok = sendTransformBestEffort(state.transform);
                        String path = resolveApplyPath(state);
                        if (path.length() > 0) BackendApplyQueue.get(context).enqueue(path, "runtime-commit-" + c.source.name().toLowerCase(), true);
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
                        binder.setPreferredService(RootBootstrap.FIXED_SERVICE_NAME);
                        if (!binder.connected()) { root.bootstrap(); binder.clearCache(); sleep(350); }
                        else if (!root.hookLibsPresent()) { root.redeployHookLibs(); sleep(800); }
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
                        context.getSharedPreferences("app_config", Context.MODE_PRIVATE).edit()
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

    private boolean sendTransformBestEffort(TransformState s) {
        try {
            binder.setPreferredService(RootBootstrap.FIXED_SERVICE_NAME);
            int r = binder.setTransform(s);
            if (log != null) log.log("runtime", "TX24 transform result=" + r + " " + s.summary());
            return r >= 0;
        } catch (Throwable t) { if (log != null) log.log("runtime", "TX24 transform skipped: " + t); return false; }
    }
    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
}
