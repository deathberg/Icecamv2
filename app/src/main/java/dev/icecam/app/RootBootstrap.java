package dev.icecam.app;

import android.content.Context;
import android.content.SharedPreferences;

public final class RootBootstrap {
    public static final String FIXED_SERVICE_NAME = "privsam_service";
    private final Context ctx;
    private final AppLogger log;
    public RootBootstrap(Context c, AppLogger logger) { ctx = c.getApplicationContext(); log = logger; }

    public String serverName() {
        SharedPreferences p = ctx.getSharedPreferences("app_config", Context.MODE_PRIVATE);
        String s = p.getString("ServerName", FIXED_SERVICE_NAME);
        // v10: stable Binder service name. Random names break reconnects after media changes.
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

    public String bootstrap() {
        NativeExtractor.Result ex = NativeExtractor.extract(ctx, log);
        String server = serverName();
        String src = ex.dir.getAbsolutePath();
        String script = deployScript(src, server, ex.abi, true);
        Shell.Result r = Shell.su(script);
        String all = r.all();
        log.logBlock("root", all);
        return all;
    }

    /** Redeploy hook libs + restart cameraserver without restarting vcplax. */
    public String redeployHookLibs() {
        NativeExtractor.Result ex = NativeExtractor.extract(ctx, log);
        String script = deployScript(ex.dir.getAbsolutePath(), serverName(), ex.abi, false);
        Shell.Result r = Shell.su(script);
        String all = r.all();
        log.logBlock("hook", all);
        return all;
    }

    public boolean hookLibsPresent() {
        Shell.Result r = Shell.su(
                "test -f /data/libvc.so && test -f /data/libvc++.so && " +
                "test -f /data/camera/libvc.so && test -f /data/camera/libshadowhook.so && echo HOOK_OK || echo HOOK_MISSING");
        return r.out.contains("HOOK_OK");
    }

    private static String deployScript(String src, String server, String abi, boolean launchDaemon) {
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
            sb.append("rm -rf /data/camera /data/samera\n");
        }
        sb.append("mkdir -p /data/camera /data/local/tmp/icecam\n");
        sb.append("chattr -i /data/camera 2>/dev/null || true\n");
        sb.append("chattr -i /data/libvc.so /data/libvc++.so 2>/dev/null || true\n");
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
        sb.append("deploy \"$SRC/vcplax.so\" /data/vcplax 700 2>/dev/null || true\n");
        sb.append("echo ---hook-verify---\n");
        sb.append("wc -c /data/libvc.so /data/libvc++.so /data/camera/libvc.so /data/camera/libshadowhook.so /data/camera/vcplax 2>&1\n");
        sb.append("echo ---restart-cameraserver---\n");
        sb.append("killall cameraserver 2>/dev/null || true\n");
        sb.append("sleep 2\n");
        sb.append("ps -A | grep -i cameraserver || ps | grep -i cameraserver || true\n");
        if (launchDaemon) {
            sb.append("rm -f /data/camera/vcplax.log /data/camera/vcplax.err\n");
            sb.append("export LD_LIBRARY_PATH=/data/camera:/data:/system/lib64:/system_ext/lib64:/vendor/lib64:/system/lib:/system_ext/lib:/vendor/lib:$LD_LIBRARY_PATH\n");
            sb.append("export ICECAM_SERVER=$SERVER\n");
            sb.append("EXEC=/data/camera/vcplax\n");
            sb.append("[ -x \"$EXEC\" ] || EXEC=/data/vcplax\n");
            sb.append("echo ---launch $EXEC $SERVER---\n");
            sb.append("nohup $EXEC $SERVER >/data/camera/vcplax.log 2>/data/camera/vcplax.err &\n");
            sb.append("echo spawned_pid=$! exec=$EXEC\n");
            sb.append("for i in 1 2 3 4 5; do sleep 1; service check $SERVER 2>&1 | grep -qi found && break; done\n");
            sb.append("echo ---process---\nps -A | grep -i vcplax || ps | grep -i vcplax || true\n");
            sb.append("echo ---expected-service---\nservice check $SERVER 2>&1 || true\n");
            sb.append("echo ---service-list-filtered---\nservice list 2>/dev/null | grep -iE \"^$SERVER$|vcplax\" || true\n");
        }
        sb.append("echo ---files---\nls -l /data/camera 2>&1; ls -l /data/vcplax /data/libvc.so /data/libvc++.so 2>&1 || true\n");
        sb.append("echo ---vcplax.log---\ncat /data/camera/vcplax.log 2>/dev/null || true\n");
        sb.append("echo ---vcplax.err---\ncat /data/camera/vcplax.err 2>/dev/null || true\n");
        sb.append("echo ---selinux-after---\ngetenforce 2>/dev/null || true\n");
        return sb.toString();
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
                "echo ---camera-services---\n" +
                "service list 2>/dev/null | grep -iE \"camera|media.camera|$SERVER|vcplax\" || true\n" +
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
                "echo ---process---\nps -A | grep -i vcplax || ps | grep -i vcplax || true\n" +
                "echo ---expected-service---\nservice check $SERVER 2>&1 || true\n" +
                "echo ---service-list-filtered---\nservice list 2>/dev/null | grep -iE \"^$SERVER$|vcplax\" || true\n" +
                "echo ---files---\nls -l /data/camera 2>&1; ls -l /data/vcplax /data/libvc.so /data/libvc++.so 2>&1 || true\n" +
                "echo ---vcplax-log---\ntail -160 /data/camera/vcplax.log 2>/dev/null || true\n" +
                "echo ---vcplax-err---\ntail -160 /data/camera/vcplax.err 2>/dev/null || true\n" +
                "echo ---logcat-native---\nlogcat -d -t 220 2>/dev/null | grep -iE \"icecam|vcplax|vlive|libvc|shadowhook|binder|servicemanager|avc: denied|Parcel\" || true\n";
        Shell.Result r = Shell.su(script);
        log.logBlock("status", r.all());
        return r.all();
    }
}
