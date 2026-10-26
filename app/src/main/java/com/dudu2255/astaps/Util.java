package com.dudu2255.astaps;

import android.system.Os;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class Util {

    public interface Progress {
        void onProgress(String message);
    }

    private Util() {
    }

    public static boolean portOpen(String host, int port) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(host, port), 400);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 解压 tar.gz（支持符号链接 / 硬链接 / GNU 长文件名 / 权限位） */
    public static void untarGz(File src, File dest, Progress cb) throws IOException {
        long total = Math.max(1, src.length());
        long done = 0;
        long last = 0;
        byte[] hdr = new byte[512];
        try (InputStream in = new GZIPInputStream(new BufferedInputStream(new FileInputStream(src), 1 << 16), 1 << 16)) {
            String longName = null;
            while (true) {
                if (readFully(in, hdr) < 512) break;
                if (isZero(hdr)) break;

                String name = cstr(hdr, 0, 100);
                long size = octal(hdr, 124, 12);
                int mode = (int) octal(hdr, 100, 8);
                int type = hdr[156] & 0xff;
                String prefix = cstr(hdr, 345, 155);
                if (!prefix.isEmpty()) name = prefix + "/" + name;
                if (longName != null) {
                    name = longName;
                    longName = null;
                }
                long pad = (512 - (size % 512)) % 512;

                if (type == 'L') {
                    byte[] buf = new byte[(int) Math.min(size, 16384)];
                    readFully(in, buf);
                    longName = cstr(buf, 0, buf.length);
                    skipFully(in, size - buf.length + pad);
                    continue;
                }
                if (type == 'x' || type == 'g' || type == 'K') {
                    skipFully(in, size + pad);
                    continue;
                }

                File out = new File(dest, name);
                if (type == '5') {
                    out.mkdirs();
                } else if (type == '2') {
                    String link = cstr(hdr, 157, 100);
                    mkdirs(out.getParentFile());
                    try {
                        Os.symlink(link, out.getAbsolutePath());
                    } catch (Exception ignored) {
                    }
                } else if (type == '1') {
                    String link = cstr(hdr, 157, 100);
                    File target = new File(dest, link);
                    mkdirs(out.getParentFile());
                    try {
                        java.nio.file.Files.createLink(out.toPath(), target.toPath());
                    } catch (Throwable t) {
                        copyFile(target, out);
                    }
                } else if (type == '0' || type == 0 || type == '7') {
                    mkdirs(out.getParentFile());
                    copyN(in, out, size);
                    try {
                        Os.chmod(out.getAbsolutePath(), mode & 07777);
                    } catch (Exception ignored) {
                    }
                } else {
                    skipFully(in, size);
                }
                if (pad > 0) skipFully(in, pad);

                done += size + 512;
                long now = System.currentTimeMillis();
                if (cb != null && now - last > 400) {
                    last = now;
                    cb.onProgress("解压运行环境 " + (done * 100 / total) + "%");
                }
            }
        }
    }

    /** 解压 zip，stripPrefix 为需要去掉的顶层目录（如 "repo-main/"） */
    public static void unzip(File zip, File dest, String stripPrefix, Progress cb) throws IOException {
        byte[] buf = new byte[1 << 16];
        int count = 0;
        try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip), 1 << 16))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName();
                if (stripPrefix != null && name.startsWith(stripPrefix)) {
                    name = name.substring(stripPrefix.length());
                }
                if (name.isEmpty()) continue;
                File out = new File(dest, name);
                if (e.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                mkdirs(out.getParentFile());
                try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out), 1 << 16)) {
                    int n;
                    while ((n = zin.read(buf)) > 0) os.write(buf, 0, n);
                }
                count++;
                if (cb != null && count % 300 == 0) cb.onProgress("解压资源 " + count + " 个文件");
            }
        }
    }

    public static String tail(File f, int lines) {
        if (!f.exists()) return "";
        StringBuilder sb = new StringBuilder();
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
            long len = raf.length();
            long start = Math.max(0, len - 8192);
            raf.seek(start);
            byte[] b = new byte[(int) (len - start)];
            raf.readFully(b);
            String s = new String(b, java.nio.charset.StandardCharsets.UTF_8);
            String[] arr = s.split("\n");
            int from = Math.max(0, arr.length - lines);
            for (int i = from; i < arr.length; i++) {
                sb.append(arr[i]).append('\n');
            }
        } catch (Exception ignored) {
        }
        return sb.toString();
    }

    public static boolean zipExists(File f) {
        return f.isFile() && f.length() > 100L * 1024 * 1024;
    }

    private static void mkdirs(File d) {
        if (d != null && !d.exists()) d.mkdirs();
    }

    private static int readFully(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n <= 0) return off;
            off += n;
        }
        return off;
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        byte[] buf = new byte[8192];
        while (n > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, n));
            if (r <= 0) return;
            n -= r;
        }
    }

    private static void copyN(InputStream in, File out, long size) throws IOException {
        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out), 1 << 16)) {
            byte[] buf = new byte[1 << 16];
            long left = size;
            while (left > 0) {
                int r = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (r <= 0) break;
                os.write(buf, 0, r);
                left -= r;
            }
        }
    }

    private static void copyFile(File src, File dst) {
        try (InputStream in = new FileInputStream(src); OutputStream os = new FileOutputStream(dst)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        } catch (Exception ignored) {
        }
    }

    private static boolean isZero(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    private static String cstr(byte[] b, int off, int len) {
        int end = off;
        int max = Math.min(off + len, b.length);
        while (end < max && b[end] != 0) end++;
        try {
            return new String(b, off, end - off, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static long octal(byte[] b, int off, int len) {
        long v = 0;
        for (int i = off; i < off + len && i < b.length; i++) {
            int c = b[i] & 0xff;
            if (c == 0 || c == ' ') {
                if (v != 0) break;
                continue;
            }
            if (c < '0' || c > '7') break;
            v = (v << 3) + (c - '0');
        }
        return v;
    }
}
