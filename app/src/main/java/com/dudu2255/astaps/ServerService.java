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
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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

    private File prootLoader() {
        File installedNative = nativeGuestExecutable("libprootloader.so");
        return installedNative.isFile() ? installedNative : new File(files(), "loader");
    }

    private File nativeGuestExecutable(String name) {
        return new File(getApplicationInfo().nativeLibraryDir, name);
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

            String configuredPath = getConfiguredResourcesPath(this);
            if (serverDir().exists()) {
                if (!ensureResources()) return;
            } else {
                setStatus("资源包已导入；启动时将按 " + configuredPath + " 自动处理");
            }
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
        prepareMasterConfig(this);
        if (!serverDir().exists()) return;

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (InputStream in = new FileInputStream(master)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        }
        String json = new String(bos.toByteArray(), "UTF-8");
        try (FileOutputStream os = new FileOutputStream(new File(serverDir(), "config.json"))) {
            os.write(json.getBytes("UTF-8"));
        }
    }

    public static void prepareMasterConfig(Context context) throws IOException {
        File master = masterConfig(context);
        if (!master.exists()) {
            try (InputStream in = context.getAssets().open("config.default.json")) {
                Util.copy(in, master);
            }
        }
        migrateWatermarkConfig(master);
    }

    /** Add watermark settings to configurations created by older APKs. */
    private static void migrateWatermarkConfig(File master) throws IOException {
        final JSONObject config;
        try (InputStream in = new FileInputStream(master)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            config = new JSONObject(bos.toString("UTF-8"));
        } catch (JSONException e) {
            throw new IOException("config.json is not valid JSON", e);
        }

        try {
            JSONObject server = childObject(config, "server");
            JSONObject game = childObject(server, "game");
            JSONObject gameOptions = childObject(game, "gameOptions");
            JSONObject watermark = childObject(gameOptions, "watermark");
            boolean changed = false;
            changed |= putDefault(watermark, "enabled", true);
            changed |= putDefault(watermark, "text", "原神{uid}");
            changed |= putDefault(watermark, "color", "#9333EA");
            changed |= putDefault(watermark, "gradientTo", "#FF1493");
            changed |= putDefault(watermark, "cmdId", 0);
            changed |= putDefault(watermark, "payloadField", 0);
            File watermarkTextMarker = new File(master.getParentFile(), ".watermark-default-text-v1");
            boolean createWatermarkTextMarker = !watermarkTextMarker.exists();
            if (createWatermarkTextMarker) {
                if (watermark.optString("text", "").isBlank()) {
                    watermark.put("text", "原神{uid}");
                    changed = true;
                }
            }
            if (config.optInt("version", 0) < 19) {
                config.put("version", 19);
                changed = true;
            }
            if (changed) {
                try (FileOutputStream out = new FileOutputStream(master)) {
                    out.write(config.toString(2).getBytes("UTF-8"));
                }
            }
            if (createWatermarkTextMarker && !watermarkTextMarker.createNewFile()) {
                throw new IOException("Unable to mark the watermark text migration");
            }
        } catch (JSONException e) {
            throw new IOException("Unable to update watermark settings in config.json", e);
        }
    }

    private static JSONObject childObject(JSONObject parent, String key) throws JSONException {
        JSONObject child = parent.optJSONObject(key);
        if (child == null) {
            child = new JSONObject();
            parent.put(key, child);
        }
        return child;
    }

    private static boolean putDefault(JSONObject object, String key, Object value) throws JSONException {
        if (object.has(key)) return false;
        object.put(key, value);
        return true;
    }

    // ---------- 资源包 ----------

    public static String getConfiguredResourcesPath(Context context) throws IOException {
        File master = masterConfig(context);
        if (!master.isFile()) return "./Resources.zip";
        try (InputStream in = new FileInputStream(master)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) > 0) bos.write(buffer, 0, count);
            JSONObject config = new JSONObject(bos.toString("UTF-8"));
            JSONObject folders = config.optJSONObject("folderStructure");
            String path = folders == null ? "./Resources.zip" : folders.optString("resources", "./Resources.zip");
            if (path.isBlank()) throw new IOException("folderStructure.resources is empty");
            return path;
        } catch (JSONException e) {
            throw new IOException("Unable to read folderStructure.resources", e);
        }
    }

    private File configuredResourcesTarget() throws IOException {
        String configuredPath = getConfiguredResourcesPath(this).replace('\\', '/');
        Path relative = Paths.get(configuredPath).normalize();
        if (relative.isAbsolute() || relative.startsWith("..") || relative.toString().isBlank()) {
            throw new IOException("folderStructure.resources must be a path inside the server folder");
        }
        File base = serverDir().getCanonicalFile();
        File target = new File(base, relative.toString()).getCanonicalFile();
        if (!target.toPath().startsWith(base.toPath()) || target.equals(base)) {
            throw new IOException("folderStructure.resources must be a path inside the server folder");
        }
        return target;
    }

    private static boolean isZipPath(File path) {
        return path.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".zip");
    }

    /** Keep ZIP inputs zipped when configured to a .zip path; otherwise extract into the configured folder. */
    private boolean ensureResources() {
        try {
            File target = configuredResourcesTarget();
            File importedZip = importedResourcesZip();
            File legacyZip = new File(serverDir(), "Resources.zip");
            if (isZipPath(target)) {
                File source = importedZip.isFile() ? importedZip : legacyZip;
                if (source.isFile() && !sameFile(source, target)) {
                    copyFileVerified(source, target);
                }
                if (!target.isFile() || target.length() < 100L * 1024 * 1024) {
                    log("Resource archive missing or incomplete: " + target.getAbsolutePath());
                    setStatus("资源包未放置到 " + getConfiguredResourcesPath(this) + "，请重新导入资源包");
                    return false;
                }
                setStatus("ZIP 资源包已就绪：" + getConfiguredResourcesPath(this));
                return true;
            }

            File sourceZip = importedZip.isFile() ? importedZip : legacyZip;
            if (sourceZip.isFile()) {
                if (sourceZip.length() < 100L * 1024 * 1024) {
                    setStatus("资源包文件太小或不完整，请重新导入");
                    return false;
                }
                extractZip(sourceZip, target);
                if (importedZip.isFile()) importedZip.delete();
                if (legacyZip.isFile()) legacyZip.delete();
            }
            File[] children = target.listFiles();
            if (!target.isDirectory() || children == null || children.length == 0) {
                setStatus("还没有资源目录 " + getConfiguredResourcesPath(this) + "，先点「导入资源包」");
                return false;
            }
            setStatus("资源包已解压并整理到 " + getConfiguredResourcesPath(this));
            return true;
        } catch (Exception e) {
            log("处理资源包失败: " + e);
            setStatus("资源包处理失败：" + e.getMessage());
            return false;
        }
    }

    private static boolean sameFile(File first, File second) throws IOException {
        return first.getCanonicalFile().equals(second.getCanonicalFile());
    }

    private File importedResourcesZip() {
        File current = new File(files(), "Resources.zip");
        if (current.isFile()) return current;
        File legacyImport = new File(files(), "import/Resources.zip");
        return legacyImport.isFile() ? legacyImport : current;
    }

    /** Copy across the app/rootfs boundary and verify the destination before the server starts. */
    private static void copyFileVerified(File source, File target) throws IOException {
        if (!source.isFile()) throw new IOException("资源包不存在：" + source.getAbsolutePath());
        File parent = target.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("无法创建资源目录");
        File temporary = new File(parent, "." + target.getName() + ".copying");
        if (temporary.exists() && !temporary.delete()) {
            throw new IOException("无法清理临时资源包");
        }
        try (InputStream in = new FileInputStream(source)) {
            Util.copy(in, temporary);
        }
        if (temporary.length() != source.length()) {
            temporary.delete();
            throw new IOException("资源包复制不完整");
        }
        if (target.exists() && !target.delete()) {
            temporary.delete();
            throw new IOException("无法替换旧资源包");
        }
        if (!temporary.renameTo(target)) {
            temporary.delete();
            throw new IOException("无法放置资源包：" + target.getAbsolutePath());
        }
        if (!target.isFile() || target.length() != source.length()) {
            throw new IOException("资源包目标文件校验失败：" + target.getAbsolutePath());
        }
    }

    private void extractZip(File archive, File target) throws IOException {
        File parent = target.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("无法创建资源目录");
        File temp = new File(parent, "." + target.getName() + ".importing");
        File backup = new File(parent, "." + target.getName() + ".previous");
        deleteRecursively(temp);
        deleteRecursively(backup);
        if (!temp.mkdirs()) throw new IOException("无法创建临时解压目录");

        boolean backedUp = false;
        try {
            long expandedBytes = 0;
            int entries = 0;
            String tempPath = temp.getCanonicalPath() + File.separator;
            try (ZipInputStream zip = new ZipInputStream(new java.io.BufferedInputStream(new FileInputStream(archive)))) {
                ZipEntry entry;
                byte[] buffer = new byte[64 * 1024];
                while ((entry = zip.getNextEntry()) != null) {
                    if (++entries > 1_000_000) throw new IOException("资源包包含过多文件");
                    String entryName = entry.getName().replace('\\', '/');
                    Path relative = Paths.get(entryName).normalize();
                    if (relative.isAbsolute() || relative.startsWith("..")) {
                        throw new IOException("资源包包含非法路径");
                    }
                    if (relative.toString().isEmpty() || relative.toString().equals(".")) {
                        zip.closeEntry();
                        continue;
                    }
                    File output = new File(temp, relative.toString()).getCanonicalFile();
                    if (!output.getPath().startsWith(tempPath)) throw new IOException("资源包包含非法路径");
                    if (entry.isDirectory()) {
                        if (!output.isDirectory() && !output.mkdirs()) throw new IOException("无法创建资源子目录");
                    } else {
                        File outputParent = output.getParentFile();
                        if (!outputParent.isDirectory() && !outputParent.mkdirs()) {
                            throw new IOException("无法创建资源子目录");
                        }
                        try (FileOutputStream out = new FileOutputStream(output)) {
                            int count;
                            while ((count = zip.read(buffer)) != -1) {
                                expandedBytes += count;
                                if (expandedBytes > 20L * 1024 * 1024 * 1024) {
                                    throw new IOException("解压后的资源包超过 20 GB");
                                }
                                out.write(buffer, 0, count);
                            }
                        }
                    }
                    zip.closeEntry();
                }
            }

            File[] children = temp.listFiles();
            if (children == null || children.length == 0) throw new IOException("资源压缩包为空");
            File content = children.length == 1 && children[0].isDirectory()
                    ? children[0] : temp;
            if (content == temp) {
                File[] meaningful = java.util.Arrays.stream(children)
                        .filter(file -> !file.getName().equals("__MACOSX") && !file.getName().equals(".DS_Store"))
                        .toArray(File[]::new);
                if (meaningful.length == 1 && meaningful[0].isDirectory()) content = meaningful[0];
            }

            if (target.exists()) {
                if (!target.renameTo(backup)) throw new IOException("无法备份旧资源目录");
                backedUp = true;
            }
            if (!content.renameTo(target)) throw new IOException("无法将解压目录整理为配置指定的名称");
            deleteRecursively(temp);
            deleteRecursively(backup);
        } catch (IOException | RuntimeException e) {
            if (target.exists() && backedUp) deleteRecursively(target);
            if (backedUp && backup.exists()) backup.renameTo(target);
            deleteRecursively(temp);
            throw e;
        }
    }

    private static void deleteRecursively(File file) throws IOException {
        if (!file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        if (file.exists() && !file.delete()) throw new IOException("无法删除 " + file.getName());
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
        addNativeBind(c, "libmongod.so", "/opt/mongodb/bin/mongod");
        addNativeBind(c, "libldlinux.so", "/lib/ld-linux-aarch64.so.1");
        addNativeBind(c, "libguestenv.so", "/usr/bin/env");
        addNativeBind(c, "libguestjava.so", "/usr/lib/jvm/java-21-openjdk-arm64/bin/java");
        for (String r : rest) c.add(r);
        return c;
    }

    private void addNativeBind(List<String> command, String library, String guestPath) {
        File executable = nativeGuestExecutable(library);
        if (executable.isFile()) {
            command.add("-b");
            command.add(executable.getAbsolutePath() + ":" + guestPath);
        }
    }

    private Process exec(List<String> cmd, File out) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.environment().clear();
        pb.environment().put("LD_PRELOAD", "");
        pb.environment().put("LD_LIBRARY_PATH", files().getAbsolutePath());
        pb.environment().put("PROOT_TMP_DIR", new File(files(), "tmp").getAbsolutePath());
        pb.environment().put("PROOT_LOADER", prootLoader().getAbsolutePath());
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
        waitForImports();
        if (!importedJar().isFile() || importedJar().length() == 0) {
            if (jarFile().isFile() && !jarFile().delete()) {
                log("Unable to remove the previous bundled Grasscutter JAR");
            }
            setStatus("请先导入匹配版本的 grasscutter.jar，再启动服务端");
            return false;
        }
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
            if (!jarFile().isFile() || jarFile().length() == 0) {
                setStatus("请先导入匹配版本的 grasscutter.jar，再启动服务端");
                return false;
            }
            syncPlugins();

            if (!ensureResources()) {
                return false;
            }

            File log = logFile();
            File mongoLog = mongoLogFile(this);
            try {
                File mongod = nativeGuestExecutable("libmongod.so");
                android.system.StructStat st = android.system.Os.stat(mongod.getAbsolutePath());
                log("mongod mode=" + Integer.toOctalString(st.st_mode) + " size=" + st.st_size);
            } catch (Exception e) {
                log("stat mongod failed: " + e);
            }
            if (!Util.portOpen("127.0.0.1", 27017)) {
                setStatus("启动数据库 MongoDB…");
                ensureNativeGuestExecutable("libmongod.so");
                ensureNativeGuestExecutable("libldlinux.so");
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
            ensureNativeGuestExecutable("libguestenv.so");
            ensureNativeGuestExecutable("libguestjava.so");
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

    private void waitForImports() throws IOException, InterruptedException {
        for (int i = 0; i < 1200; i++) {
            synchronized (this) {
                if (activeImports == 0) return;
            }
            if (stopRequested) return;
            if (i % 8 == 0) setStatus("正在完成导入操作…");
            Thread.sleep(500);
        }
        synchronized (this) {
            if (activeImports != 0) throw new IOException("导入操作仍在进行，请稍后再启动");
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
            ensureNativeGuestExecutable("libprootloader.so");
        } catch (android.system.ErrnoException e) {
            throw new IOException("无法修复 proot 执行权限", e);
        }
    }

    private void ensureNativeGuestExecutable(String name) throws IOException {
        File executable = nativeGuestExecutable(name);
        if (!executable.isFile() || !executable.canExecute()) {
            throw new IOException("APK 缺少可执行原生组件: " + executable.getAbsolutePath());
        }
        try {
            android.system.StructStat stat = android.system.Os.stat(executable.getAbsolutePath());
            log("native-exec-check " + name + " mode=" + Integer.toOctalString(stat.st_mode)
                    + " size=" + stat.st_size);
        } catch (android.system.ErrnoException e) {
            throw new IOException("无法检查原生组件: " + name, e);
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
