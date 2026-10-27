package com.dudu2255.astaps;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.pm.ServiceInfo;
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
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class ServerService extends Service {

    public static final String ACTION_START = "com.dudu2255.astaps.START";
    public static final String ACTION_STOP = "com.dudu2255.astaps.STOP";
    public static final String ACTION_IMPORT_JAR = "com.dudu2255.astaps.IMPORT_JAR";
    public static final String ACTION_IMPORT_RES = "com.dudu2255.astaps.IMPORT_RES";
    public static final String ACTION_IMPORT_PLUGIN = "com.dudu2255.astaps.IMPORT_PLUGIN";
    public static final String ACTION_COMMAND = "com.dudu2255.astaps.COMMAND";
    public static final String EXTRA_URI = "uri";
    public static final String EXTRA_COMMAND = "command";

    public static final String PAYLOAD_URL =
            "https://github.com/DuDu2255/Grasscutter-Android-APK/releases/latest/download/payload.tar.gz";

    private static final String CHANNEL = "astaps";
    private static final int NOTIF_ID = 1;

    public static volatile String status = "未启动";
    public static volatile boolean running = false;

    private PowerManager.WakeLock wake;
    private Process mongoProc;
    private volatile Process javaProc;
    private Thread worker;
    private volatile boolean stopRequested;
    private int activeImports;

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

    public static void importPlugin(Context c, Uri uri) {
        send(c, new Intent(c, ServerService.class).setAction(ACTION_IMPORT_PLUGIN).putExtra(EXTRA_URI, uri));
    }

    public static void sendCommand(Context c, String command) {
        send(c, new Intent(c, ServerService.class).setAction(ACTION_COMMAND)
                .putExtra(EXTRA_COMMAND, command));
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
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Grasscutter Android", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotif(String text) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setContentTitle("Grasscutter Android 服务器")
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
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, buildNotif(status),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, buildNotif(status));
        }
        if (!wake.isHeld()) wake.acquire();

        if (ACTION_STOP.equals(action)) {
            stopRequested = true;
            Thread activeWorker = worker;
            if (activeWorker != null) activeWorker.interrupt();
            stopAll();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_IMPORT_JAR.equals(action)) {
            final Uri uri = intent.getParcelableExtra(EXTRA_URI);
            startImport(() -> doImportJar(uri), startId, "astaps-jar");
            return START_STICKY;
        }
        if (ACTION_IMPORT_RES.equals(action)) {
            final Uri uri = intent.getParcelableExtra(EXTRA_URI);
            startImport(() -> doImportResources(uri), startId, "astaps-res");
            return START_STICKY;
        }
        if (ACTION_IMPORT_PLUGIN.equals(action)) {
            final Uri uri = intent.getParcelableExtra(EXTRA_URI);
            startImport(() -> doImportPlugin(uri), startId, "astaps-plugin");
            return START_STICKY;
        }
        if (ACTION_COMMAND.equals(action)) {
            sendCommandToServer(intent.getStringExtra(EXTRA_COMMAND));
            boolean idle;
            synchronized (this) {
                idle = activeImports == 0 && !running
                        && (worker == null || !worker.isAlive());
            }
            if (idle) {
                if (wake != null && wake.isHeld()) wake.release();
                stopForeground(true);
                stopSelfResult(startId);
                return START_NOT_STICKY;
            }
            return START_STICKY;
        }
        if (worker == null || !worker.isAlive()) {
            stopRequested = false;
            worker = new Thread(this::runServer, "astaps-server");
            worker.start();
        }
        return START_STICKY;
    }

    private void startImport(Runnable task, int startId, String name) {
        synchronized (this) {
            activeImports++;
        }
        new Thread(() -> {
            try {
                task.run();
            } finally {
                boolean idle;
                synchronized (ServerService.this) {
                    activeImports--;
                    idle = activeImports == 0 && !running;
                }
                if (idle && !stopRequested) {
                    if (wake != null && wake.isHeld()) wake.release();
                    stopForeground(true);
                    stopSelfResult(startId);
                }
            }
        }, name).start();
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

    private File prootExecutable() {
        File installedNative = new File(getApplicationInfo().nativeLibraryDir, "libproot.so");
        return installedNative.isFile() ? installedNative : new File(files(), "proot");
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

    public static File mongoLogFile(Context context) {
        File d = new File(context.getFilesDir(), "logs");
        d.mkdirs();
        return new File(d, "mongodb.log");
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

    private String queryName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private File pluginsStash() {
        File d = new File(files(), "plugins");
        d.mkdirs();
        return d;
    }

    private File pluginsDir() {
        return new File(serverDir(), "plugins");
    }

    private void doImportPlugin(Uri uri) {
        try {
            if (uri == null) {
                setStatus("没有选择文件");
                return;
            }
            String name = queryName(uri);
            if (name == null || !name.toLowerCase().endsWith(".jar")) {
                name = "plugin-" + System.currentTimeMillis() + ".jar";
            }
            name = new File(name).getName().replaceAll("[^A-Za-z0-9._-]", "_");
            if (name.isEmpty() || name.equals(".") || name.equals("..")) {
                name = "plugin-" + System.currentTimeMillis() + ".jar";
            }
            File dst = new File(pluginsStash(), name);
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("无法读取文件");
                Util.copy(in, dst);
            }
            syncPlugins();
            setStatus("插件已导入 " + name + "（重启服务生效）");
        } catch (Exception e) {
            log("导入插件失败: " + e);
            setStatus("导入插件失败：" + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    private void syncPlugins() {
        File stash = pluginsStash();
        File target = pluginsDir();
        if (!serverDir().exists()) return;
        target.mkdirs();
        File[] list = stash.listFiles();
        if (list == null) return;
        for (File f : list) {
            if (!f.isFile() || !f.getName().toLowerCase().endsWith(".jar")) continue;
            File out = new File(target, f.getName());
            if (out.exists() && out.length() == f.length()) continue;
            try (InputStream in = new FileInputStream(f)) {
                Util.copy(in, out);
            } catch (Exception e) {
                log("复制插件失败 " + f.getName() + ": " + e);
            }
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
        c.add(prootExecutable().getAbsolutePath());
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
        pb.environment().put("PROOT_LOADER", new File(files(), "loader").getAbsolutePath());
        pb.environment().put("PROOT_LOADER_32", new File(files(), "loader32").getAbsolutePath());
        pb.environment().put("HOME", files().getAbsolutePath());
        pb.environment().put("TMPDIR", new File(files(), "tmp").getAbsolutePath());
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(out));
        return pb.start();
    }

    private void sendCommandToServer(String command) {
        String line = command == null ? "" : command.trim();
        if (line.isEmpty() || line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) return;
        Process process = javaProc;
        if (process == null || !process.isAlive()) {
            setStatus("服务端未运行，无法发送指令");
            return;
        }
        try {
            OutputStream input = process.getOutputStream();
            input.write((line + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            input.flush();
            log("> " + line);
        } catch (IOException e) {
            log("发送指令失败: " + e);
            setStatus("指令发送失败：" + e.getMessage());
        }
    }

    private void runServer() {
        if (running) return;
        running = true;
        int restartCount = 0;
        try {
            while (!stopRequested) {
                long attemptStartedAt = android.os.SystemClock.elapsedRealtime();
                boolean retry;
                try {
                    retry = runServerAttempt();
                } catch (Exception e) {
                    retry = !stopRequested;
                    if (retry) {
                        log("启动失败: " + e);
                        setStatus("启动出错：" + e.getClass().getSimpleName() + " " + e.getMessage());
                    }
                }

                stopProcesses();
                if (!retry || stopRequested) break;
                if (android.os.SystemClock.elapsedRealtime() - attemptStartedAt >= 30_000) {
                    restartCount = 0;
                }
                if (restartCount >= 5) {
                    setStatus("连续重启失败，请检查日志后手动重试");
                    break;
                }

                long delaySeconds = Math.min(30, 2L << restartCount);
                restartCount++;
                setStatus("服务异常，" + delaySeconds + " 秒后自动重启（" + restartCount + "/5）");
                Thread.sleep(delaySeconds * 1000);
            }
        } catch (InterruptedException e) {
            if (!stopRequested) {
                Thread.currentThread().interrupt();
                log("服务监控线程被中断");
            }
        } finally {
            running = false;
            stopAll();
            stopForeground(true);
            stopSelf();
        }
    }

    private boolean runServerAttempt() throws Exception {
        if (stopRequested) return false;
        try {
            if (!new File(rootfs(), "usr").exists() || !new File(files(), "loader").exists()) {
                if (!installPayload()) {
                    return false;
                }
            }
            ensureProotExecutable();
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
            syncPlugins();

            if (!ensureResources()) {
                return false;
            }

            File log = logFile();
            File mongoLog = mongoLogFile(this);
            try {
                File mongod = new File(rootfs(), "opt/mongodb/bin/mongod");
                android.system.StructStat st = android.system.Os.stat(mongod.getAbsolutePath());
                log("mongod mode=" + Integer.toOctalString(st.st_mode) + " size=" + st.st_size);
            } catch (Exception e) {
                log("stat mongod failed: " + e);
            }
            if (!Util.portOpen("127.0.0.1", 27017)) {
                setStatus("启动数据库 MongoDB…");
                ensureRootfsExecutable("opt/mongodb/bin/mongod");
                ensureRootfsExecutable("lib/ld-linux-aarch64.so.1");
                mongoProc = exec(prootCmd("-w", "/root",
                        "/opt/mongodb/bin/mongod",
                        "--dbpath", "/opt/astaps/data/db",
                        "--bind_ip", "127.0.0.1",
                        "--port", "27017", "--quiet"), mongoLog);
                for (int i = 0; i < 90 && !Util.portOpen("127.0.0.1", 27017); i++) {
                    if (stopRequested) return false;
                    if (!mongoProc.isAlive()) break;
                    Thread.sleep(1000);
                }
                if (!Util.portOpen("127.0.0.1", 27017)) {
                    setStatus("MongoDB 启动失败，请看日志");
                    return true;
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
                if (stopRequested) return false;
                if (!javaProc.isAlive()) break;
                if (Util.portOpen("127.0.0.1", 10086)) break;
                Thread.sleep(1000);
            }
            if (Util.portOpen("127.0.0.1", 10086)) {
                setStatus("运行中 · dispatch 127.0.0.1:10086");
            } else {
                setStatus("服务端启动中…（首次加载资源较慢）");
            }

            while (!stopRequested && javaProc.isAlive()) {
                if (mongoProc != null && !mongoProc.isAlive()) {
                    setStatus("MongoDB 已退出，准备重启服务");
                    javaProc.destroy();
                    break;
                }
                if (javaProc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) break;
            }
            if (stopRequested) return false;
            int code = javaProc.exitValue();
            if (!stopRequested) setStatus("服务端已退出 (code " + code + ")，点启动重试");
            return true;
        } catch (InterruptedException e) {
            if (stopRequested) return false;
            throw e;
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

    private void ensureProotExecutable() throws IOException {
        File proot = prootExecutable();
        if (!proot.isFile()) throw new IOException("运行环境缺少 proot: " + proot.getAbsolutePath());
        try {
            if (!proot.canExecute()) android.system.Os.chmod(proot.getAbsolutePath(), 0755);
            android.system.StructStat stat = android.system.Os.stat(proot.getAbsolutePath());
            int permissions = stat.st_mode & 0777;
            log("proot mode=" + Integer.toOctalString(stat.st_mode) + " size=" + stat.st_size);
            if ((permissions & 0111) == 0) {
                throw new IOException("proot 执行位缺失，mode=" + Integer.toOctalString(stat.st_mode));
            }
            if (!proot.canExecute()) {
                throw new IOException("系统禁止执行 proot 文件：" + proot.getAbsolutePath());
            }
        } catch (android.system.ErrnoException e) {
            throw new IOException("无法修复 proot 执行权限", e);
        }
    }

    private void ensureRootfsExecutable(String relativePath) throws IOException {
        File root = rootfs();
        File target = new File(root, relativePath);
        if (!target.exists() || !target.isFile()) {
            throw new IOException("运行环境缺少可执行文件: /" + relativePath);
        }
        try {
            File canonicalRoot = root.getCanonicalFile();
            File canonicalTarget = target.getCanonicalFile();
            if (!canonicalTarget.getPath().startsWith(canonicalRoot.getPath() + File.separator)) {
                throw new IOException("运行环境可执行文件路径越界: /" + relativePath);
            }

            File parent = canonicalTarget.getParentFile();
            while (parent != null && parent.getPath().startsWith(canonicalRoot.getPath())) {
                android.system.Os.chmod(parent.getAbsolutePath(), 0755);
                android.system.StructStat parentStat = android.system.Os.stat(parent.getAbsolutePath());
                if ((parentStat.st_mode & 0111) == 0) {
                    throw new IOException("目录缺少遍历权限: " + parent.getAbsolutePath());
                }
                if (parent.equals(canonicalRoot)) break;
                parent = parent.getParentFile();
            }

            android.system.Os.chmod(canonicalTarget.getAbsolutePath(), 0755);
            android.system.StructStat stat = android.system.Os.stat(canonicalTarget.getAbsolutePath());
            log("exec-check " + relativePath + " mode=" + Integer.toOctalString(stat.st_mode)
                    + " size=" + stat.st_size);
            if ((stat.st_mode & 0111) == 0) {
                throw new IOException("文件缺少执行权限: /" + relativePath);
            }
        } catch (android.system.ErrnoException e) {
            throw new IOException("无法修复运行环境执行权限: /" + relativePath, e);
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
        stopProcesses();
        running = false;
        if (stopRequested) status = "已停止";
        if (wake != null && wake.isHeld()) wake.release();
    }

    private void stopProcesses() {
        stopProcess(javaProc);
        stopProcess(mongoProc);
        javaProc = null;
        mongoProc = null;
    }

    private void stopProcess(Process process) {
        if (process == null) return;
        try {
            process.destroy();
            new Thread(() -> {
                try {
                    if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
            }, "astaps-stop-process").start();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onDestroy() {
        stopAll();
        super.onDestroy();
    }
}
