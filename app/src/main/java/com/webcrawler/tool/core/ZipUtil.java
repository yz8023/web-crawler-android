package com.webcrawler.tool.core;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 打包 / 删除工具（对应 crawl.php 的 create_zip / delete_folder） */
public final class ZipUtil {

    private ZipUtil() {
    }

    /** 本身已压缩的格式直接存储，省时间 */
    private static final Set<String> STORED_EXT = new HashSet<>(Arrays.asList(
            "jpg", "jpeg", "png", "gif", "webp", "avif", "heic", "mp4", "webm", "mov", "mkv", "flv",
            "mp3", "m4a", "aac", "ogg", "opus", "woff", "woff2", "zip", "gz", "tgz", "rar", "7z",
            "bz2", "xz", "br", "zst", "apk", "jar"));

    /**
     * 把 baseDir 下的每个一级文件夹（域名文件夹）整体压缩进 zip，zip 内含一级文件夹本身。
     * firstFolder 排在最前面。
     */
    public static void zipFolders(File baseDir, File zipFile, final String firstFolder) throws IOException {
        File[] tops = baseDir.listFiles();
        List<File> dirs = new ArrayList<>();
        if (tops != null) {
            for (File f : tops) {
                if (f.isDirectory()) dirs.add(f);
            }
        }
        Collections.sort(dirs, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                boolean fa = a.getName().equals(firstFolder);
                boolean fb = b.getName().equals(firstFolder);
                if (fa != fb) return fa ? -1 : 1;
                return a.getName().compareTo(b.getName());
            }
        });
        File parent = zipFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("无法创建目录：" + parent);
        }
        ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zipFile), 64 * 1024));
        boolean ok = false;
        try {
            byte[] buf = new byte[64 * 1024];
            for (File d : dirs) addDir(zos, d, d.getName(), buf);
            ok = true;
        } finally {
            try {
                zos.close();
            } catch (IOException e) {
                if (ok) throw e;
            }
            if (!ok) //noinspection ResultOfMethodCallIgnored
                zipFile.delete();
        }
    }

    private static void addDir(ZipOutputStream zos, File dir, String prefix, byte[] buf) throws IOException {
        File[] children = dir.listFiles();
        if (children == null) return;
        Arrays.sort(children, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareTo(b.getName());
            }
        });
        for (File c : children) {
            String name = prefix + "/" + c.getName();
            if (c.isDirectory()) {
                addDir(zos, c, name, buf);
            } else {
                addFile(zos, c, name, buf);
            }
        }
    }

    private static void addFile(ZipOutputStream zos, File f, String entryName, byte[] buf) throws IOException {
        String lower = f.getName().toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        String ext = dot >= 0 ? lower.substring(dot + 1) : "";
        zos.setLevel(STORED_EXT.contains(ext) ? Deflater.NO_COMPRESSION : Deflater.DEFAULT_COMPRESSION);
        ZipEntry e = new ZipEntry(entryName);
        e.setTime(f.lastModified());
        zos.putNextEntry(e);
        InputStream in = new FileInputStream(f);
        try {
            int n;
            while ((n = in.read(buf)) != -1) zos.write(buf, 0, n);
        } finally {
            in.close();
        }
        zos.closeEntry();
    }

    /** 递归删除文件或文件夹 */
    public static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursively(c);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
