package dev.icecam.app;

import android.content.Context;
import android.content.SharedPreferences;

public final class RootBootstrap {
    public static final String FIXED_SERVICE_NAME = "privsam_service";
    private static final Object BOOT_LOCK = new Object();

    private final Context ctx;
    private final AppLogger log;

    public RootBootstrap(Context c, AppLogger logger) {
        ctx = c.getApplicationContext();
        log = logger;
    }

    public String serverName() {
        SharedPreferences p = ctx.getSharedPreferences("app_config", Context.MODE_PRIVATE);
        String s = p.getString("ServerName", FIXED_SERVICE_NAME);
        if (s == null || s.trim().isEmpty() || !FIXED_SERVICE_NAME.equals(s.trim())) {
            s = FIXED_SERVICE_NAME;
            p.edit().putString("ServerName", s).apply();
        }
        return s;
    }

    public String resetServerName() {
        ctx.getSharedPreferences("app_config", Context.MODE_PRIVATE).edit().putString("ServerName", FIXED_SERVICE_NAME).apply();
        log.log("root", "ServerName fixed=" + FIXED_SERVICE_NAME);
        return FIXED_SERVICE_NAME;
    }

    /** Full deploy + launch vcplax. Serialized — never run in parallel. */
    public String bootstrap() {
        synchronized (BOOT_LOCK) {
            NativeExtractor.Result ex = NativeExtractor.extract(ctx, log);
            String script = deployScript(ex.dir.getAbsolutePath(), serverName(), ex.abi, true, false);
            Shell.Result r = Shell.su(script);
            String all = r.all();
            log.logBlock("root", all);
            return all;
        }
    }

    /** Deploy hook libs only — does NOT restart cameraserver or vcplax. */
    public String redeployHookLibs() {
        synchronized (BOOT_LOCK) {
            NativeExtractor.Result ex = NativeExtractor.extract(ctx, log);
            String script = deployScript(ex.dir.getAbsolutePath(), serverName(), ex.abi, false, false);
            Shell.Result r = Shell.su(script);
            String all = r.all();
            log.logBlock("hook", all);
            return all;
        }
    }

    /**
     * Ensure privsam_service is registered. Returns true when service responds.
     * Safe to call from apply queue — uses BOOT_LOCK and waits for daemon.
     */
    public boolean ensureDaemonUp() {
        synchronized (BOOT_LOCK) {
            if (serviceAlive()) {
                log.log("daemon", "already up service=" + FIXED_SERVICE_NAME);
                return true;
            }
            log.log("daemon", "service down — full bootstrap");
            NativeExtractor.Result ex = NativeExtractor.extract(ctx, log);
            String script = deployScript(ex.dir.getAbsolutePath(), serverName(), ex.abi, true, false);
            Shell.Result r = Shell.su(script);
            log.logBlock("daemon", r.all());
            boolean up = waitForService(12000);
            if (!up) {
                Shell.Result diag = Shell.su(
                        "echo ---vcplax-ps---; ps -A | grep -i vcplax || true\n" +
                        "echo ---service---; service check " + FIXED_SERVICE_NAME + " 2>&1\n" +
                        "echo ---logcat-crash---\n" +
                        "logcat -d -t 80 2>/dev/null | grep -iE 'vcplax|privsam|FATAL|DEBUG' || true\n");
                log.logBlock("daemon-fail", diag.all());
            }
            return up;
        }
    }

    /** Deploy /data/libvc.so + restart cameraserver (vcplax stays up). */
    public boolean ensureCameraHooks() {
        synchronized (BOOT_LOCK) {
            NativeExtractor.Result ex = NativeExtractor.extract(ctx, log);
            String script = deployRootHooksScript(ex.dir.getAbsolutePath()) +
                    "echo ---inject-cameraserver---\n" +
                    "killall cameraserver 2>/dev/null || true\n" +
                    "sleep 2\n" +
                    "ps -A | grep -i cameraserver || true\n" +
                    "wc -c /data/libvc.so /data/libvc++.so 2>&1\n" +
                    "test -f /data/libvc.so && test -f /data/libvc++.so && echo INJECT_OK || echo INJECT_FAIL\n";
            Shell.Result r = Shell.su(script);
            log.logBlock("inject", r.all());
            boolean ok = (r.out + r.err).contains("INJECT_OK");
            if (!ok) IceCamLog.e(log, "inject", "camera hook deploy failed");
            return ok;
        }
    }

    public boolean rootHookLibsPresent() {
        Shell.Result r = Shell.su("test -f /data/libvc.so && test -f /data/libvc++.so && echo ROOT_HOOK_OK || echo ROOT_HOOK_MISSING");
        return (r.out + r.err).contains("ROOT_HOOK_OK");
    }

    @Deprecated
    public boolean hookLibsPresent() {
        return rootHookLibsPresent() && cameraLibsPresent();
    }

    public boolean cameraLibsPresent() {
        Shell.Result r = Shell.su("test -f /data/camera/libvc.so && test -f /data/camera/libshadowhook.so && echo CAM_OK || echo CAM_MISSING");
        return (r.out + r.err).contains("CAM_OK");
    }

    public void restartCameraServer() {
        synchronized (BOOT_LOCK) {
            Shell.Result r = Shell.su(
                    "echo ---restart-cameraserver---\n" +
                    "killall cameraserver 2>/dev/null || true\n" +
                    "sleep 2\n" +
                    "ps -A | grep -i cameraserver || true\n");
            log.logBlock("inject", r.all());
        }
    }

    public boolean serviceAlive() {
        Shell.Result r = Shell.su("service check " + FIXED_SERVICE_NAME + " 2>&1");
        return (r.out + r.err).toLowerCase().contains("found");
    }

    private boolean waitForService(long timeoutMs) {
        long deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (serviceAlive()) return true;
            try { Thread.sleep(400); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        return serviceAlive();
    }

    private static String deployScript(String src, String server, String abi, boolean launchDaemon, boolean restartCameraServer) {
        StringBuilder sb = new StringBuilder();
        sb.append("set -x\n");
        sb.append("SRC=").append(Shell.q(src)).append('\n');
        sb.append("SERVER=").append(Shell.q(server)).append('\n');
        sb.append("echo selected_abi=").append(Shell.q(abi)).append(" server=$SERVER src=$SRC\n");
        sb.append("id\n");
        sb.append("getenforce 2>/dev/null || true\n");
        sb.append("setenforce 0 2>/dev/null || true\n");
        if (launchDaemon) {
            sb.append("killall vcplax 2>/dev/null || true\n");
            sb.append("pkill -f /data/vcplax 2>/dev/null || true\n");
            sb.append("pkill -f /data/camera/vcplax 2>/dev/null || true\n");
            sb.append("sleep 1\n");
            sb.append("rm -rf /data/camera /data/samera\n");
        }
        sb.append("mkdir -p /data/camera /data/local/tmp/icecam\n");
        sb.append("chattr -i /data/camera 2>/dev/null || true\n");
        sb.append("chattr -i /data/libvc.so /data/libvc++.so /data/vcplax 2>/dev/null || true\n");
        sb.append("deploy() {\n");
        sb.append("  local s=\"$1\" d=\"$2\" m=\"$3\"\n");
        sb.append("  chattr -i \"$d\" 2>/dev/null || true\n");
        sb.append("  rm -f \"$d\"\n");
        sb.append("  cat \"$s\" > \"$d\" 2>/dev/null || cp -f \"$s\" \"$d\" || return 1\n");
        sb.append("  chmod \"$m\" \"$d\" 2>/dev/null || true\n");
        sb.append("  test -f \"$d\" && test \"$(wc -c < \"$d\")\" -gt 1000\n");
        sb.append("}\n");
        sb.append("deploy \"$SRC/libvc.so\" /data/libvc.so 644 || echo DEPLOY_FAIL libvc_root\n");
        sb.append("deploy \"$SRC/libshadowhook.so\" /data/libvc++.so 644 || echo DEPLOY_FAIL shadowhook_root\n");
        sb.append("deploy \"$SRC/libshadowhook.so\" /data/camera/libshadowhook.so 644 || echo DEPLOY_FAIL shadowhook_camera\n");
        sb.append("deploy \"$SRC/libvc.so\" /data/camera/libvc.so 644 || echo DEPLOY_FAIL libvc_camera\n");
        sb.append("deploy \"$SRC/vcplax.so\" /data/camera/vcplax 700 || echo DEPLOY_FAIL vcplax_camera\n");
        sb.append("deploy \"$SRC/vcplax.so\" /data/vcplax 700 || echo DEPLOY_FAIL vcplax_root\n");
        sb.append("echo ---hook-verify---\n");
        sb.append("wc -c /data/libvc.so /data/libvc++.so /data/camera/libvc.so /data/camera/libshadowhook.so /data/vcplax /data/camera/vcplax 2>&1\n");
        if (restartCameraServer) {
            sb.append("echo ---restart-cameraserver---\n");
            sb.append("killall cameraserver 2>/dev/null || true\n");
            sb.append("sleep 2\n");
            sb.append("ps -A | grep -i cameraserver || true\n");
        }
        if (launchDaemon) {
            sb.append("rm -f /data/camera/vcplax.log /data/camera/vcplax.err\n");
            sb.append("export LD_LIBRARY_PATH=/data/camera:/data:/system/lib64:/system_ext/lib64:/vendor/lib64:/system/lib:/system_ext/lib:/vendor/lib:$LD_LIBRARY_PATH\n");
            sb.append("export ICECAM_SERVER=$SERVER\n");
            sb.append("EXEC=/data/vcplax\n");
            sb.append("[ -x \"$EXEC\" ] || EXEC=/data/camera/vcplax\n");
            sb.append("echo ---launch $EXEC $SERVER---\n");
            sb.append("nohup $EXEC $SERVER >>/data/camera/vcplax.log 2>>/data/camera/vcplax.err &\n");
            sb.append("VPID=$!\n");
            sb.append("echo spawned_pid=$VPID exec=$EXEC\n");
            sb.append("for i in 1 2 3 4 5 6 7 8 9 10 11 12; do\n");
            sb.append("  sleep 1\n");
            sb.append("  service check $SERVER 2>&1 | grep -qi found && break\n");
            sb.append("done\n");
            sb.append("echo ---process---\n");
            sb.append("ps -A | grep -i vcplax || ps | grep -i vcplax || true\n");
            sb.append("echo ---expected-service---\n");
            sb.append("service check $SERVER 2>&1 || true\n");
            sb.append("echo ---service-list-filtered---\n");
            sb.append("service list 2>/dev/null | grep -iE \"$SERVER|vcplax\" || true\n");
            sb.append("echo ---post-launch-inject---\n");
            sb.append("sleep 1\n");
            sb.append("killall cameraserver 2>/dev/null || true\n");
            sb.append("sleep 2\n");
            sb.append("ps -A | grep -i cameraserver || true\n");
        }
        sb.append("echo ---files---\n");
        sb.append("ls -l /data/camera 2>&1; ls -l /data/vcplax /data/libvc.so /data/libvc++.so 2>&1 || true\n");
        sb.append("echo ---vcplax.log---\n");
        sb.append("tail -40 /data/camera/vcplax.log 2>/dev/null || true\n");
        sb.append("echo ---vcplax.err---\n");
        sb.append("tail -40 /data/camera/vcplax.err 2>/dev/null || true\n");
        sb.append("echo ---selinux-after---\n");
        sb.append("getenforce 2>/dev/null || true\n");
        return sb.toString();
    }

    private static String deployRootHooksScript(String src) {
        return "SRC=" + Shell.q(src) + "\n" +
                "mkdir -p /data/camera /data/local/tmp/icecam\n" +
                "chattr -i /data/libvc.so /data/libvc++.so 2>/dev/null || true\n" +
                "deploy() {\n" +
                "  local s=\"$1\" d=\"$2\" m=\"$3\"\n" +
                "  chattr -i \"$d\" 2>/dev/null || true\n" +
                "  rm -f \"$d\"\n" +
                "  cat \"$s\" > \"$d\" 2>/dev/null || cp -f \"$s\" \"$d\" || return 1\n" +
                "  chmod \"$m\" \"$d\" 2>/dev/null || true\n" +
                "  test -f \"$d\" && test \"$(wc -c < \"$d\")\" -gt 1000\n" +
                "}\n" +
                "deploy \"$SRC/libvc.so\" /data/libvc.so 644 || echo DEPLOY_FAIL libvc_root\n" +
                "deploy \"$SRC/libshadowhook.so\" /data/libvc++.so 644 || echo DEPLOY_FAIL shadowhook_root\n" +
                "deploy \"$SRC/libshadowhook.so\" /data/camera/libshadowhook.so 644 || echo DEPLOY_FAIL shadowhook_camera\n" +
                "deploy \"$SRC/libvc.so\" /data/camera/libvc.so 644 || echo DEPLOY_FAIL libvc_camera\n";
    }

    public String restoreCamera() {
        String server = serverName();
        String script = "set -x\n" +
                "SERVER=" + Shell.q(server) + "\n" +
                "echo restore_server=$SERVER\n" +
                "id\n" +
                "getenforce 2>/dev/null || true\n" +
                "echo ---soft-stop-binder---\n" +
                "service check $SERVER 2>&1 || true\n" +
                "echo ---kill-daemon---\n" +
                "killall vcplax 2>/dev/null || true\n" +
                "pkill -f /data/vcplax 2>/dev/null || true\n" +
                "pkill -f /data/camera/vcplax 2>/dev/null || true\n" +
                "sleep 1\n" +
                "echo ---after-process---\n" +
                "ps -A | grep -i vcplax || ps | grep -i vcplax || true\n" +
                "echo ---after-service---\n" +
                "service check $SERVER 2>&1 || true\n" +
                "echo restore_done\n";
        Shell.Result r = Shell.su(script);
        log.logBlock("restore", r.all());
        return r.all();
    }

    public String status() {
        String server = serverName();
        String script = "SERVER=" + Shell.q(server) + "\n" +
                "id\n" +
                "echo server=$SERVER\n" +
                "echo ---selinux---\ngetenforce 2>/dev/null || true\n" +
                "echo ---process---\nps -A | grep -iE 'vcplax|cameraserver' || true\n" +
                "echo ---expected-service---\nservice check $SERVER 2>&1 || true\n" +
                "echo ---hook-check---\n" +
                "test -f /data/libvc.so && test -f /data/vcplax && echo HOOK_ROOT_OK || echo HOOK_ROOT_MISSING\n" +
                "echo ---files---\nls -l /data/camera 2>&1; ls -l /data/vcplax /data/libvc.so /data/libvc++.so 2>&1 || true\n" +
                "echo ---vcplax-log---\ntail -80 /data/camera/vcplax.log 2>/dev/null || true\n" +
                "echo ---vcplax-err---\ntail -80 /data/camera/vcplax.err 2>/dev/null || true\n" +
                "echo ---logcat-native---\nlogcat -d -t 120 2>/dev/null | grep -iE 'vcplax|vlive|libvc|FATAL|servicemanager|avc: denied' || true\n";
        Shell.Result r = Shell.su(script);
        log.logBlock("status", r.all());
        return r.all();
    }
}
