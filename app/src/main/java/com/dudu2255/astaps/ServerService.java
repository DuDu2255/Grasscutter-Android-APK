package com.dudu2255.astaps;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class ServerService extends Service {

    public static final String ACTION_START = "com.dudu2255.astaps.START";
    public static final String ACTION_STOP = "com.dudu2255.astaps.STOP";
    public static final String ACTION_IMPORT_JAR = "com.dudu2255.astaps.IMPORT_JAR";
    public static final String ACTION_IMPORT_RES = "com.dudu2255.astaps.IMPORT_RES";
    public static final String EXTRA_URI = "uri";

    public static final String PAYLOAD_URL =
            "https://github.com/DuDu2255/Grasscutter-Android-APK/releases/latest/download/payload.tar.gz";

    private static final String CHANNEL = "astaps";
    private static final int NOTIF_ID = 1;

    public static volatile String status = "未启动";
    public static volatile boolean running = false;

    private PowerManager.WakeLock wake;
    private Process mongoProc;
    private Process javaProc;
    private Thread worker;

    public static void start(Context c) {
        send(c, new Intent(c, ServerService.class).setAction(ACTION_START));
    }

    public static void stop(Context c) {
        send(c, new Intent(c, ServerService.class).setAction(ACTION_STOP));
    }

    public static void importJar(Context c, Uri uri) {
        send(c, new Intent(c, ServerService.class).setAction(ACTION_IMPORT_JAR).putExtra(EXTRA_URI, uri));
    }

    public static void importResources(Context c, Uri uri) {
        send(c, new Intent(c, ServerService.class).setAction(ACTION_IMPORT_RES).putExtra(EXTRA_URI, uri));
    }

    private static void send(Context c, Intent i) {
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
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
        if (ACTION_IMPORT_JAR.equals(action)) {
            final Uri uri = intent.getParcelableExtra(EXTRA_URI);
            new Thread(() -> doImportJar(uri), "astaps-jar").start();
            return START_STICKY;
        }
        if (ACTION_IMPORT_RES.equals(action)) {
            final Uri uri = intent.getParcelableExtra(EXTRA_URI);
            new Thread(() -> doImportResources(uri), "astaps-res").start();
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

    private File rootfs() {
        return new File(files(), "rootfs");
    }

    private File serverDir() {
        return new File(rootfs(), "opt/astaps");
    }

    private File jarFile() {
        return new File(serverDir(), "grasscutter.jar");
    }

    private File importedJar() {
        return new File(files(), "import/grasscutter.jar");
    }

    private File logFile() {
        File d = new File(files(), "logs");
        d.mkdirs();
        return new File(d, "server.log");
    }

    public static File masterConfig(Context c) {
        return new File(c.getFilesDir(), "config.json");
    }

    // ---------- 导入 ----------

    private void doImportJar(Uri uri) {
        try {
            if (uri == null) {
                setStatus("没有选择文件");
                return;
            }
            setStatus("导入 jar…");
            File dst = importedJar();
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("无法读取文件");
                Util.copy(in, dst);
            }
            applyImportedJar();
            setStatus("jar 已导入（" + (dst.length() / 1024 / 1024) + " MB）");
        } catch (Exception e) {
            log("导入 jar 失败: " + e);
            setStatus("导入 jar 失败：" + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    private void doImportResources(Uri uri) {
        try {
            if (uri == null) {
                setStatus("没有选择文件");
                return;
            }
            setStatus("复制资源包…");
            File tmp = new File(files(), "Resources.zip.tmp");
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("无法读取文件");
                Util.copy(in, tmp);
            }
            File src = new File(files(), "Resources.zip");
            if (src.exists()) src.delete();
            if (!tmp.renameTo(src)) throw new IOException("保存失败");

            File dst = new File(serverDir(), "Resources.zip");
            if (dst.exists()) dst.delete();
            if (serverDir().exists() && !src.renameTo(dst)) {
                try (InputStream in = new FileInputStream(src)) {
                    Util.copy(in, dst);
                }
            }
            long size = dst.exists() ? dst.length() : src.length();
            setStatus("资源包已就绪 " + (size / 1024 / 1024) + " MB（不解压，直接用 zip）");
        } catch (Exception e) {
            log("导入资源包失败: " + e);
            setStatus("导入资源失败：" + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    private void applyImportedJar() throws IOException {
        File src = importedJar();
        if (!src.exists() || !serverDir().exists()) return;
        try (InputStream in = new FileInputStream(src)) {
            Util.copy(in, jarFile());
        }
    }

    // ---------- 配置 ----------

    private void ensureConfig() throws IOException {
        File master = masterConfig(this);
        if (!master.exists()) {
            try (InputStream in = getAssets().open("config.default.json")) {
                Util.copy(in, master);
            }
        }
        if (!serverDir().exists()) return;

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (InputStream in = new FileInputStream(master)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        }
        String json = new String(bos.toByteArray(), "UTF-8")
                .replace("\"./resources/\"", "\"./Resources.zip\"");
        try (FileOutputStream os = new FileOutputStream(new File(serverDir(), "config.json"))) {
            os.write(json.getBytes("UTF-8"));
        }
    }

    // ---------- 资源包 ----------

    /** 服务端原生支持把 resources 指到 zip，所以资源包保持压缩包形态，不解压 */
    private boolean ensureResources() {
        File src = new File(files(), "Resources.zip");
        File dst = new File(serverDir(), "Resources.zip");
        if (!dst.exists() && src.exists() && serverDir().exists()) {
            if (!src.renameTo(dst)) {
                try (InputStream in = new FileInputStream(src)) {
                    Util.copy(in, dst);
                } catch (Exception e) {
                    log("放置 Resources.zip 失败: " + e);
                }
            }
        }
        long size = dst.exists() ? dst.length() : (src.exists() ? src.length() : 0);
        if (size < 100L * 1024 * 1024) {
            setStatus("还没有资源包 Resources.zip，先点「导入资源包」");
            return false;
        }
        return true;
    }

    // ---------- 运行 ----------

    private List<String> prootCmd(String... rest) {
        File tmp = new File(files(), "tmp");
        tmp.mkdirs();
        List<String> c = new ArrayList<>();
        c.add(new File(files(), "proot").getAbsolutePath());
        c.add("--kill-on-exit");
        c.add("--link2symlink");
        c.add("-0");
        c.add("-r");
        c.add(rootfs().getAbsolutePath());
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
        pb.environment().put("LD_PRELOAD", "");
        pb.environment().put("LD_LIBRARY_PATH", files().getAbsolutePath());
        pb.environment().put("PROOT_TMP_DIR", new File(files(), "tmp").getAbsolutePath());
        pb.environment().put("HOME", files().getAbsolutePath());
        pb.environment().put("TMPDIR", new File(files(), "tmp").getAbsolutePath());
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(out));
        return pb.start();
    }

    private void runServer() {
        if (running) return;
        running = true;
        try {
            if (!new File(rootfs(), "usr").exists()) {
                if (!installPayload()) {
                    running = false;
                    return;
                }
            }
            if (!new File(files(), ".perms-ok").exists()) {
                setStatus("修复文件权限…");
                fixPerms(rootfs());
                try {
                    new File(files(), ".perms-ok").createNewFile();
                } catch (Exception ignored) {
                }
            }
            ensureConfig();
            applyImportedJar();

            if (!ensureResources()) {
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
                    setStatus("MongoDB 启动失败，请看日志");
                    running = false;
                    return;
                }
            }

            setStatus("启动游戏服务端…");
            List<String> cmd = prootCmd(
                    "-w", "/opt/astaps",
                    "/usr/bin/env", "-i",
                    "HOME=/root",
                    "PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin",
                    "LANG=C.UTF-8",
                    "/usr/lib/jvm/java-21-openjdk-arm64/bin/java", "-Xms512m", "-Xmx2g",
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
            log("启动失败: " + e);
            setStatus("启动出错：" + e.getClass().getSimpleName() + " " + e.getMessage());
        } finally {
            running = false;
        }
    }

    private boolean installPayload() {
        File tar = new File(files(), "payload.tar.gz");
        try {
            long free = files().getUsableSpace();
            if (free < 2L * 1024 * 1024 * 1024) {
                setStatus("存储空间不足：运行环境需要约 2 GB（可用 " + (free / 1024 / 1024) + " MB）");
                return false;
            }
            if (!tar.exists() || tar.length() < 100L * 1024 * 1024) {
                setStatus("首次运行：准备运行环境…");
                boolean ok = false;
                try {
                    copyAsset(findPayloadAsset(), tar);
                    ok = true;
                } catch (Exception e) {
                    log("内置 payload 读取失败: " + e);
                }
                if (!ok) {
                    setStatus("内置运行环境缺失，联网下载中（约 250 MB）…");
                    download(PAYLOAD_URL, tar);
                }
            }
            setStatus("解压运行环境…");
            Util.untarGz(tar, files(), this::setStatus);
            try {
                android.system.Os.chmod(new File(files(), "proot").getAbsolutePath(), 0755);
            } catch (Exception ignored) {
            }
            if (!new File(rootfs(), "usr/lib/jvm").exists()) {
                setStatus("运行环境包不完整（缺 java）");
                return false;
            }
            tar.delete();
            setStatus("运行环境就绪");
            return true;
        } catch (Exception e) {
            log("解压运行环境失败: " + e);
            setStatus("解压失败：" + e.getClass().getSimpleName() + " " + e.getMessage());
            tar.delete();
            return false;
        }
    }

    /** aapt 打包可能改写扩展名，按前缀查找 */
    private String findPayloadAsset() throws IOException {
        String[] list = getAssets().list("");
        if (list != null) {
            for (String n : list) {
                if (n.startsWith("payload")) return n;
            }
        }
        return "payload.bin";
    }

    private void copyAsset(String name, File dst) throws IOException {
        try (InputStream in = getAssets().open(name);
             FileOutputStream os = new FileOutputStream(dst)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
    }

    private void download(String url, File dst) throws IOException {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.connect();
        long total = c.getContentLength();
        try (InputStream in = c.getInputStream();
             FileOutputStream os = new FileOutputStream(dst)) {
            byte[] buf = new byte[1 << 16];
            long done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                done += n;
                if (done % (8 * 1024 * 1024) < buf.length) {
                    setStatus("下载运行环境 " + (done / 1024 / 1024) + " MB / " + (total / 1024 / 1024) + " MB");
                }
            }
        } finally {
            c.disconnect();
        }
    }

    /** Android 上解压后要确保可执行位；ELF 和 bin 目录给 0755 */
    private void fixPerms(File f) {
        try {
            if (f.isDirectory()) {
                chmod(f, 0755);
                File[] kids = f.listFiles();
                if (kids != null) for (File c : kids) fixPerms(c);
                return;
            }
            if (!f.isFile()) return;
            boolean exec = f.getPath().contains("/bin/") || isElf(f);
            chmod(f, exec ? 0755 : 0644);
        } catch (Exception ignored) {
        }
    }

    private boolean isElf(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[4];
            if (in.read(b) < 4) return false;
            return b[0] == 0x7f && b[1] == 'E' && b[2] == 'L' && b[3] == 'F';
        } catch (Exception e) {
            return false;
        }
    }

    private void chmod(File f, int mode) {
        try {
            android.system.Os.chmod(f.getAbsolutePath(), mode);
        } catch (Exception ignored) {
        }
    }

    private void log(String msg) {
        try {
            FileOutputStream os = new FileOutputStream(logFile(), true);
            os.write((msg + "\n").getBytes("UTF-8"));
            os.close();
        } catch (Exception ignored) {
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
