package com.dudu2255.astaps;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class ServerService extends Service {

    public static final String ACTION_START = "com.dudu2255.astaps.START";
    public static final String ACTION_STOP = "com.dudu2255.astaps.STOP";
    public static final String ACTION_UNZIP = "com.dudu2255.astaps.UNZIP";

    public static final String RES_ZIP_URL =
            "https://codeload.github.com/MeChen618/AstaPS-Resource/zip/refs/heads/main";
    public static final String RES_ZIP_TOP = "AstaPS-Resource-main/";

    private static final String CHANNEL = "astaps";
    private static final int NOTIF_ID = 1;

    public static volatile String status = "未启动";
    public static volatile boolean running = false;

    private PowerManager.WakeLock wake;
    private Process mongoProc;
    private Process javaProc;
    private Thread worker;

    public static void start(Context c) {
        Intent i = new Intent(c, ServerService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    public static void stop(Context c) {
        c.startService(new Intent(c, ServerService.class).setAction(ACTION_STOP));
    }

    public static void unzip(Context c) {
        c.startService(new Intent(c, ServerService.class).setAction(ACTION_UNZIP));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "astaps:server");
        wake.setReferenceCounted(false);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel ch = new NotificationChannel(CHANNEL, "AstaPS", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotif(String text) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setContentTitle("AstaPS 服务器")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true);
        return b.build();
    }

    private void setStatus(String s) {
        status = s;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.notify(NOTIF_ID, buildNotif(s));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        startForeground(NOTIF_ID, buildNotif(status));
        if (!wake.isHeld()) wake.acquire();

        if (ACTION_STOP.equals(action)) {
            stopAll();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_UNZIP.equals(action)) {
            new Thread(this::unzipResources, "astaps-unzip").start();
            return START_STICKY;
        }
        if (worker == null || !worker.isAlive()) {
            worker = new Thread(this::runServer, "astaps-server");
            worker.start();
        }
        return START_STICKY;
    }

    private File files() {
        return getFilesDir();
    }

    private File logFile() {
        File d = new File(files(), "logs");
        d.mkdirs();
        return new File(d, "server.log");
    }

    private List<String> prootCmd(String... rest) {
        File files = files();
        File rootfs = new File(files, "rootfs");
        File tmp = new File(files, "tmp");
        tmp.mkdirs();
        List<String> c = new ArrayList<>();
        c.add(new File(files, "proot").getAbsolutePath());
        c.add("--kill-on-exit");
        c.add("--link2symlink");
        c.add("-0");
        c.add("-r");
        c.add(rootfs.getAbsolutePath());
        c.add("-b");
        c.add("/dev");
        c.add("-b");
        c.add("/proc");
        c.add("-b");
        c.add("/dev/urandom:/dev/random");
        c.add("-b");
        c.add(tmp.getAbsolutePath() + ":/tmp");
        for (String r : rest) c.add(r);
        return c;
    }

    private Process exec(List<String> cmd, File out) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.environment().clear();
        pb.environment().put("LD_LIBRARY_PATH", files().getAbsolutePath());
        pb.environment().put("PROOT_TMP_DIR", new File(files(), "tmp").getAbsolutePath());
        pb.environment().put("HOME", files().getAbsolutePath());
        pb.environment().put("TMPDIR", new File(files(), "tmp").getAbsolutePath());
        final OutputStream os = new FileOutputStream(out, true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(out));
        return pb.start();
    }

    private void runServer() {
        if (running) return;
        running = true;
        try {
            File files = files();
            File rootfs = new File(files, "rootfs");
            File serverDir = new File(rootfs, "opt/astaps");
            File res = new File(getExternalFilesDir(null), "resources");

            if (!new File(serverDir, "grasscutter.jar").exists()) {
                if (!installPayload()) {
                    running = false;
                    return;
                }
            }
            if (!res.isDirectory() || res.list() == null || res.list().length == 0) {
                setStatus("缺少资源包，请先下载并解压资源");
                running = false;
                return;
            }

            File log = logFile();

            if (!Util.portOpen("127.0.0.1", 27017)) {
                setStatus("启动数据库 MongoDB…");
                mongoProc = exec(prootCmd("-w", "/root",
                        "/opt/mongodb/bin/mongod",
                        "--dbpath", "/opt/astaps/data/db",
                        "--bind_ip", "127.0.0.1",
                        "--port", "27017", "--quiet"), log);
                for (int i = 0; i < 90 && !Util.portOpen("127.0.0.1", 27017); i++) {
                    Thread.sleep(1000);
                }
                if (!Util.portOpen("127.0.0.1", 27017)) {
                    setStatus("MongoDB 启动失败，请查看日志");
                    running = false;
                    return;
                }
            }

            setStatus("启动游戏服务端…");
            List<String> cmd = prootCmd(
                    "-b", res.getAbsolutePath() + ":/opt/astaps/resources",
                    "-w", "/opt/astaps",
                    "/usr/bin/env", "-i",
                    "HOME=/root",
                    "PATH=/opt/jre/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin",
                    "LANG=C.UTF-8",
                    "/opt/jre/bin/java", "-Xms512m", "-Xmx2g",
                    "-jar", "/opt/astaps/grasscutter.jar");
            javaProc = exec(cmd, log);

            for (int i = 0; i < 180; i++) {
                if (Util.portOpen("127.0.0.1", 10086)) break;
                Thread.sleep(1000);
            }
            if (Util.portOpen("127.0.0.1", 10086)) {
                setStatus("运行中 · dispatch 127.0.0.1:10086");
            } else {
                setStatus("服务端启动中…（首次加载资源较慢）");
            }

            int code = javaProc.waitFor();
            setStatus("服务端已退出 (code " + code + ")，点启动重试");
        } catch (Exception e) {
            setStatus("启动出错：" + e.getMessage());
        } finally {
            running = false;
        }
    }

    private boolean installPayload() {
        try {
            setStatus("首次运行：解压运行环境…");
            File tar = new File(files(), "payload.tar.gz");
            if (!tar.exists()) {
                try (InputStream in = getAssets().open("payload.tar.gz");
                     OutputStream os = new FileOutputStream(tar)) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                }
            }
            Util.untarGz(tar, files(), this::setStatus);
            try {
                android.system.Os.chmod(new File(files(), "proot").getAbsolutePath(), 0755);
            } catch (Exception ignored) {
            }
            tar.delete();
            setStatus("运行环境就绪");
            return true;
        } catch (Exception e) {
            setStatus("解压失败：" + e.getMessage());
            return false;
        }
    }

    private void unzipResources() {
        try {
            File zip = new File(getExternalFilesDir(null), "resources.zip");
            File out = new File(getExternalFilesDir(null), "resources");
            if (!Util.zipExists(zip)) {
                setStatus("没有找到下载好的 resources.zip");
                return;
            }
            setStatus("解压资源包…");
            Util.unzip(zip, out, RES_ZIP_TOP, this::setStatus);
            setStatus("资源解压完成");
        } catch (Exception e) {
            setStatus("资源解压失败：" + e.getMessage());
        }
    }

    private void stopAll() {
        try {
            if (javaProc != null) javaProc.destroy();
        } catch (Exception ignored) {
        }
        try {
            if (mongoProc != null) mongoProc.destroy();
        } catch (Exception ignored) {
        }
        running = false;
        status = "已停止";
        if (wake != null && wake.isHeld()) wake.release();
    }

    @Override
    public void onDestroy() {
        stopAll();
        super.onDestroy();
    }
}
