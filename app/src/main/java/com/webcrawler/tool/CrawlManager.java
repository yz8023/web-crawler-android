package com.webcrawler.tool;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import com.webcrawler.tool.core.Crawler;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 爬取任务管理（进程内单例）：Activity 重建、切到后台都不影响任务；结果持久化到文件，进程被杀后也能恢复。
 */
final class CrawlManager {

    interface Listener {
        void onCrawlProgress(String json);

        void onCrawlFinished();
    }

    static final int NOTIFY_PROGRESS = 1;
    static final int NOTIFY_DONE = 2;
    private static final String CHANNEL_PROGRESS = "crawl_progress";
    private static final String CHANNEL_DONE = "crawl_done";

    // 默认值与原项目 README 中的本地运行配置一致
    static final int DEFAULT_MAX_FILES = 0;
    static final int DEFAULT_MAX_RUNTIME = 0;
    static final int DEFAULT_TIMEOUT = 20;
    static final int DEFAULT_RETRIES = 3;

    @SuppressLint("StaticFieldLeak") // 只持有 Application Context，不会泄漏
    private static CrawlManager instance;

    static synchronized CrawlManager get(Context ctx) {
        if (instance == null) instance = new CrawlManager(ctx.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean progressScheduled = new AtomicBoolean(false);

    private Listener listener;
    private boolean activityVisible;
    private CrawlService service;
    private PowerManager.WakeLock wakeLock;
    private long lastNotifyAt;

    private volatile Crawler crawler;
    private volatile boolean running;
    private volatile String resultJson;

    private CrawlManager(Context app) {
        this.app = app;
        this.prefs = app.getSharedPreferences("crawler", Context.MODE_PRIVATE);
        this.resultJson = readText(resultFile());
        createChannels();
    }

    // ==================== 目录 ====================

    File baseDir() { return new File(app.getFilesDir(), "crawl"); }

    static File zipDir(Context ctx) { return new File(ctx.getFilesDir(), "zips"); }

    private File tmpDir() { return new File(app.getFilesDir(), "tmp"); }

    private File resultFile() { return new File(app.getFilesDir(), "last_result.json"); }

    /** 最近一次结果的 zip 文件（不存在返回 null） */
    File zipFile() {
        String json = resultJson;
        if (json == null || running) return null;
        try {
            String name = new JSONObject(json).optString("zipName", "");
            if (name.isEmpty()) return null;
            File f = new File(zipDir(app), name);
            return f.isFile() ? f : null;
        } catch (JSONException e) {
            return null;
        }
    }

    // ==================== 生命周期 ====================

    void setListener(Listener l) { listener = l; }

    void clearListener(Listener l) {
        if (listener == l) listener = null;
    }

    void setActivityVisible(boolean visible) {
        activityVisible = visible;
        if (visible) {
            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIFY_DONE);
        }
    }

    boolean isRunning() { return running; }

    void attachService(CrawlService s) { service = s; }

    void detachService(CrawlService s) {
        if (service == s) service = null;
    }

    // ==================== 启动 / 停止 ====================

    /** 启动爬取，成功返回 null，否则返回错误信息 */
    synchronized String start(String input, String settingsJson) {
        if (running) return "已有爬取任务正在进行，请等待完成或先停止";
        final String url;
        try {
            url = Crawler.prepareStartUrl(input);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        Crawler.Config cfg = parseSettings(settingsJson);
        cfg.startUrlGuessed = Crawler.isSchemeGuessed(input);
        prefs.edit()
                .putInt("maxFiles", cfg.maxFiles)
                .putInt("maxRuntime", cfg.maxRuntimeSeconds)
                .putInt("timeout", cfg.requestTimeoutSeconds)
                .putInt("retries", cfg.maxRetries)
                .putString("lastUrl", input.trim())
                .apply();

        resultJson = null;
        //noinspection ResultOfMethodCallIgnored
        resultFile().delete();

        final Crawler c = new Crawler(url, baseDir(), zipDir(app), tmpDir(), cfg, new Crawler.Listener() {
            @Override
            public void onProgress(Crawler crawler) {
                onWorkerProgress(crawler);
            }
        });
        crawler = c;
        running = true;
        acquireWakeLock();
        startKeepAliveService();

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                runCrawl(c);
            }
        }, "crawler");
        t.start();
        return null;
    }

    void stop() {
        Crawler c = crawler;
        if (c != null) c.stop();
    }

    private void runCrawl(final Crawler c) {
        final Crawler.Result r = c.run();
        final String json = resultToJson(r).toString();
        writeText(resultFile(), json);
        main.post(new Runnable() {
            @Override
            public void run() {
                resultJson = json;
                running = false;
                crawler = null;
                releaseWakeLock();
                if (service != null) service.finish();
                notifyDone(r);
                if (listener != null) listener.onCrawlFinished();
            }
        });
    }

    private void onWorkerProgress(final Crawler c) {
        if (!progressScheduled.compareAndSet(false, true)) return;
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                progressScheduled.set(false);
                if (!running || crawler != c) return;
                if (listener != null) listener.onCrawlProgress(progressJson(c).toString());
                long now = SystemClock.uptimeMillis();
                if (service != null && now - lastNotifyAt >= 1000) {
                    lastNotifyAt = now;
                    service.update(buildProgressNotification(service));
                }
            }
        }, 150);
    }

    // ==================== JSON ====================

    String stateJson() {
        JSONObject o = new JSONObject();
        try {
            Crawler c = crawler;
            String r = resultJson;
            if (running && c != null) {
                o.put("state", "running");
                o.put("progress", progressJson(c));
            } else if (r != null) {
                o.put("state", "done");
                o.put("result", new JSONObject(r));
            } else {
                o.put("state", "idle");
            }
        } catch (JSONException e) {
            // ignore
        }
        return o.toString();
    }

    String settingsJson() {
        JSONObject o = new JSONObject();
        try {
            JSONObject cur = new JSONObject();
            cur.put("maxFiles", prefs.getInt("maxFiles", DEFAULT_MAX_FILES));
            cur.put("maxRuntime", prefs.getInt("maxRuntime", DEFAULT_MAX_RUNTIME));
            cur.put("timeout", prefs.getInt("timeout", DEFAULT_TIMEOUT));
            cur.put("retries", prefs.getInt("retries", DEFAULT_RETRIES));
            JSONObject def = new JSONObject();
            def.put("maxFiles", DEFAULT_MAX_FILES);
            def.put("maxRuntime", DEFAULT_MAX_RUNTIME);
            def.put("timeout", DEFAULT_TIMEOUT);
            def.put("retries", DEFAULT_RETRIES);
            o.put("current", cur);
            o.put("defaults", def);
            o.put("lastUrl", prefs.getString("lastUrl", ""));
        } catch (JSONException e) {
            // ignore
        }
        return o.toString();
    }

    private static Crawler.Config parseSettings(String json) {
        Crawler.Config cfg = new Crawler.Config();
        cfg.maxFiles = DEFAULT_MAX_FILES;
        cfg.maxRuntimeSeconds = DEFAULT_MAX_RUNTIME;
        cfg.requestTimeoutSeconds = DEFAULT_TIMEOUT;
        cfg.maxRetries = DEFAULT_RETRIES;
        try {
            JSONObject o = new JSONObject(json == null ? "{}" : json);
            cfg.maxFiles = clamp(o.optInt("maxFiles", DEFAULT_MAX_FILES), 0, 10_000_000);
            cfg.maxRuntimeSeconds = clamp(o.optInt("maxRuntime", DEFAULT_MAX_RUNTIME), 0, 7 * 24 * 3600);
            cfg.requestTimeoutSeconds = clamp(o.optInt("timeout", DEFAULT_TIMEOUT), 1, 600);
            cfg.maxRetries = clamp(o.optInt("retries", DEFAULT_RETRIES), 1, 10);
        } catch (JSONException e) {
            // 使用默认值
        }
        return cfg;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static JSONObject progressJson(Crawler c) {
        JSONObject p = new JSONObject();
        try {
            p.put("host", c.getStartHost());
            p.put("startUrl", c.getStartUrl());
            p.put("phase", c.getPhase());
            p.put("stopping", c.isStopRequested());
            p.put("downloadCount", c.getDownloadCount());
            p.put("visitedCount", c.getVisitedCount());
            p.put("failCount", c.getFailCount());
            p.put("queueRemaining", c.getQueueRemaining());
            p.put("currentUrl", c.getCurrentUrl());
            p.put("elapsedMs", c.getElapsedMs());
        } catch (JSONException e) {
            // ignore
        }
        return p;
    }

    private static JSONObject resultToJson(Crawler.Result r) {
        JSONObject o = new JSONObject();
        try {
            o.put("success", r.success);
            o.put("error", r.error == null ? "" : r.error);
            o.put("startUrl", r.startUrl);
            o.put("zipName", r.zipName == null ? "" : r.zipName);
            o.put("zipSize", r.zipSize);
            o.put("downloadCount", r.downloadCount);
            o.put("visitedCount", r.visitedCount);
            o.put("failCount", r.failCount);
            o.put("stats", r.stats);
            o.put("fileList", r.fileList);
            StringBuilder fails = new StringBuilder();
            for (String s : r.failed) fails.append(s).append('\n');
            o.put("failList", fails.toString());
            o.put("stopReason", r.stopReason == null ? "" : r.stopReason);
            o.put("elapsedMs", r.elapsedMs);
        } catch (JSONException e) {
            // ignore
        }
        return o;
    }

    // ==================== 前台服务 / 通知 / 唤醒锁 ====================

    private void startKeepAliveService() {
        try {
            Intent i = new Intent(app, CrawlService.class);
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i);
            else app.startService(i);
        } catch (Throwable ignored) {
            // 前台服务启动失败不影响爬取本身（只是切到后台后可能被系统暂停）
        }
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WebCrawler:crawl");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(6 * 60 * 60 * 1000L);
        } catch (Throwable ignored) {
            wakeLock = null;
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable ignored) {
            // ignore
        }
        wakeLock = null;
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel progress = new NotificationChannel(CHANNEL_PROGRESS, "爬取进度", NotificationManager.IMPORTANCE_LOW);
        progress.setShowBadge(false);
        nm.createNotificationChannel(progress);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_DONE, "爬取完成提醒", NotificationManager.IMPORTANCE_DEFAULT));
    }

    @SuppressWarnings("deprecation")
    private Notification.Builder builder(Context ctx, String channel) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(ctx, channel);
        } else {
            b = new Notification.Builder(ctx);
        }
        return b.setSmallIcon(R.drawable.ic_notification)
                .setColor(0xFF2563EB)
                .setContentIntent(openAppIntent(ctx));
    }

    private static int immutableFlag() {
        return Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
    }

    private static PendingIntent openAppIntent(Context ctx) {
        Intent i = new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(ctx, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | immutableFlag());
    }

    @SuppressWarnings("deprecation")
    Notification buildProgressNotification(Context ctx) {
        Crawler c = crawler;
        String title = c != null ? "正在爬取 " + c.getStartHost() : "网页爬虫";
        String text;
        if (c == null) {
            text = "正在完成…";
        } else if ("zipping".equals(c.getPhase())) {
            text = "正在打包 zip 压缩包…";
        } else {
            text = "已下载 " + c.getDownloadCount() + " 个文件 · 失败 " + c.getFailCount()
                    + " · 待处理 " + c.getQueueRemaining();
        }
        Intent stop = new Intent(ctx, CrawlService.class).setAction(CrawlService.ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(ctx, 1, stop, PendingIntent.FLAG_UPDATE_CURRENT | immutableFlag());
        Notification.Builder b = builder(ctx, CHANNEL_PROGRESS)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setProgress(0, 0, true)
                .addAction(0, "停止", stopPi);
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_LOW);
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }

    private void notifyDone(Crawler.Result r) {
        if (activityVisible) return;
        if (Build.VERSION.SDK_INT >= 33
                && app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        String text = r.success
                ? "共下载 " + r.downloadCount + " 个文件，已打包为 " + r.zipName + "，点击查看"
                : "爬取失败：" + r.error;
        Notification n = builder(app, CHANNEL_DONE)
                .setContentTitle(r.success ? "爬取完成" : "爬取失败")
                .setContentText(text)
                .setAutoCancel(true)
                .build();
        try {
            nm.notify(NOTIFY_DONE, n);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    // ==================== 文件读写 ====================

    private static String readText(File f) {
        if (!f.isFile()) return null;
        try {
            InputStream in = new FileInputStream(f);
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeText(File f, String s) {
        try {
            FileOutputStream out = new FileOutputStream(f);
            try {
                out.write(s.getBytes(StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
        } catch (IOException ignored) {
            // ignore
        }
    }
}
