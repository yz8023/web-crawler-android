package com.webcrawler.tool;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * 前台服务：仅用于在爬取期间保活（切到后台 / 锁屏后继续爬取），并在通知栏显示进度。
 * 实际任务由 {@link CrawlManager} 管理。
 */
public class CrawlService extends Service {

    static final String ACTION_STOP = "com.webcrawler.tool.action.STOP";

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        CrawlManager m = CrawlManager.get(this);
        Notification n = m.buildProgressNotification(this);
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(CrawlManager.NOTIFY_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(CrawlManager.NOTIFY_PROGRESS, n);
            }
        } catch (Throwable t) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_STOP.equals(intent.getAction())) m.stop();
        if (!m.isRunning()) {
            finish();
            return START_NOT_STICKY;
        }
        m.attachService(this);
        return START_NOT_STICKY;
    }

    void update(Notification n) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        try {
            nm.notify(CrawlManager.NOTIFY_PROGRESS, n);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    @SuppressWarnings("deprecation")
    void finish() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        stopSelf();
    }

    /** Android 15+：dataSync 类型前台服务每日累计 6 小时上限，到时停止爬取（已下载内容仍会打包） */
    @Override
    public void onTimeout(int startId, int fgsType) {
        CrawlManager.get(this).stop();
        finish();
    }

    @Override
    public void onDestroy() {
        CrawlManager.get(this).detachService(this);
        super.onDestroy();
    }
}
