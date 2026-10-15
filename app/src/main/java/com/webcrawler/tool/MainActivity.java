package com.webcrawler.tool;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Insets;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity implements CrawlManager.Listener {

    private static final String PAGE_URL = "file:///android_asset/index.html";
    private static final String SAVE_FOLDER = "网页爬虫";
    private static final int REQ_STORAGE = 11;
    private static final int REQ_NOTIFICATIONS = 12;
    private static final int BG_COLOR = 0xFFF5F7FA;
    private static final Pattern URL_IN_TEXT = Pattern.compile("https?://[^\\s\"'<>，。、；！？）】」]+", Pattern.CASE_INSENSITIVE);

    private CrawlManager manager;
    private WebView webView;
    private boolean pageLoaded;
    private String pendingSharedUrl;
    private boolean saving;
    private Object backCallback;   // OnBackInvokedCallback（API 33+）
    private boolean backCallbackRegistered;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        manager = CrawlManager.get(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG_COLOR);
        webView = new WebView(this);
        webView.setBackgroundColor(BG_COLOR);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        applyInsets(root);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        webView.addJavascriptInterface(new Bridge(), "Android");
        webView.setWebViewClient(new WebViewClient() {
            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleNavigation(url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleNavigation(request.getUrl().toString());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageLoaded = true;
                deliverSharedUrl();
            }
        });

        manager.setListener(this);
        handleShareIntent(getIntent());
        webView.loadUrl(PAGE_URL);
        updateRunningUi();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShareIntent(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        manager.setActivityVisible(true);
    }

    @Override
    protected void onStop() {
        manager.setActivityVisible(false);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        manager.clearListener(this);
        unregisterBackCallback();
        if (webView != null) {
            ViewGroup parent = (ViewGroup) webView.getParent();
            if (parent != null) parent.removeView(webView);
            webView.removeJavascriptInterface("Android");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private boolean handleNavigation(String url) {
        if (url != null && url.startsWith("file:///android_asset/")) return false;
        if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception ignored) {
                // 没有浏览器
            }
        }
        return true;
    }

    // ==================== 窗口适配 ====================

    private void applyInsets(View root) {
        if (Build.VERSION.SDK_INT >= 35) {
            // Android 15+ 强制沉浸式（edge-to-edge），需要自己避开状态栏、导航栏、刘海和输入法
            root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override
                public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                    Insets i = insets.getInsets(WindowInsets.Type.systemBars()
                            | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                    v.setPadding(i.left, i.top, i.right, i.bottom);
                    return WindowInsets.CONSUMED;
                }
            });
        }
    }

    private void updateRunningUi() {
        boolean running = manager.isRunning();
        if (running) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 33) {
            if (running) registerBackCallback();
            else unregisterBackCallback();
        }
    }

    // ==================== 返回键 ====================

    /** 仅 Android 12 及以下会调用；Android 13+ 由 {@link #registerBackCallback()} 处理（支持预测性返回手势） */
    @SuppressLint("GestureBackNavigation")
    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (manager.isRunning()) {
            confirmLeave();
            return;
        }
        super.onBackPressed();
    }

    private void registerBackCallback() {
        if (Build.VERSION.SDK_INT < 33 || backCallbackRegistered) return;
        if (backCallback == null) {
            backCallback = new OnBackInvokedCallback() {
                @Override
                public void onBackInvoked() {
                    if (manager.isRunning()) confirmLeave();
                    else {
                        unregisterBackCallback();
                        finish();
                    }
                }
            };
        }
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, (OnBackInvokedCallback) backCallback);
        backCallbackRegistered = true;
    }

    private void unregisterBackCallback() {
        if (Build.VERSION.SDK_INT < 33 || !backCallbackRegistered) return;
        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((OnBackInvokedCallback) backCallback);
        backCallbackRegistered = false;
    }

    private void confirmLeave() {
        new AlertDialog.Builder(this)
                .setTitle("正在爬取")
                .setMessage("爬取任务仍在进行。可以切到后台继续运行（通知栏可查看进度），也可以立即停止（已下载的文件仍会打包）。")
                .setPositiveButton("后台运行", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        moveTaskToBack(true);
                    }
                })
                .setNegativeButton("停止爬取", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        manager.stop();
                    }
                })
                .setNeutralButton("取消", null)
                .show();
    }

    // ==================== 爬取回调 ====================

    @Override
    public void onCrawlProgress(String json) {
        callJs("onCrawlProgress(" + jsSafe(json) + ")");
    }

    @Override
    public void onCrawlFinished() {
        updateRunningUi();
        callJs("onCrawlFinished()");
    }

    private void callJs(String script) {
        if (webView != null && pageLoaded) webView.evaluateJavascript(script, null);
    }

    private static String jsSafe(String json) {
        return json.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
    }

    // ==================== 分享进来的网址 ====================

    private void handleShareIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (text == null) return;
        Matcher m = URL_IN_TEXT.matcher(text);
        String url = m.find() ? m.group() : text.trim();
        if (url.isEmpty() || url.length() > 2048) return;
        pendingSharedUrl = url;
        // 防止 Activity 重建时重复处理
        intent.setAction(Intent.ACTION_MAIN);
        deliverSharedUrl();
    }

    private void deliverSharedUrl() {
        if (pendingSharedUrl == null || !pageLoaded) return;
        callJs("setSharedUrl(" + jsSafe(JSONObject.quote(pendingSharedUrl)) + ")");
        pendingSharedUrl = null;
    }

    // ==================== 通知权限（Android 13+） ====================

    private void maybeAskNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return;
        SharedPreferences p = getSharedPreferences("crawler", MODE_PRIVATE);
        if (p.getBoolean("askedNotifications", false)) return;
        p.edit().putBoolean("askedNotifications", true).apply();
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
    }

    // ==================== 保存 / 分享 zip ====================

    private void saveZip() {
        if (saving) return;
        final File zip = manager.zipFile();
        if (zip == null) {
            sendSaveResult(false, "压缩包不存在，请重新爬取");
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            return;
        }
        saving = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                String message;
                boolean ok;
                try {
                    message = "已保存到：" + (Build.VERSION.SDK_INT >= 29 ? saveViaMediaStore(zip) : saveLegacy(zip));
                    ok = true;
                } catch (Exception e) {
                    message = "保存失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                    ok = false;
                }
                final boolean fOk = ok;
                final String fMsg = message;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        saving = false;
                        sendSaveResult(fOk, fMsg);
                    }
                });
            }
        }, "save-zip").start();
    }

    @TargetApi(29)
    private String saveViaMediaStore(File zip) throws IOException {
        ContentResolver cr = getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, zip.getName());
        v.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + SAVE_FOLDER);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("无法在下载目录创建文件");
        try {
            OutputStream out = cr.openOutputStream(uri);
            if (out == null) throw new IOException("无法写入下载目录");
            copy(zip, out);
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, done, null, null);
        } catch (IOException | RuntimeException e) {
            try {
                cr.delete(uri, null, null);
            } catch (Exception ignored) {
                // ignore
            }
            throw e;
        }
        String name = zip.getName();
        Cursor c = cr.query(uri, new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null);
        if (c != null) {
            try {
                if (c.moveToFirst() && c.getString(0) != null) name = c.getString(0);
            } finally {
                c.close();
            }
        }
        return Environment.DIRECTORY_DOWNLOADS + "/" + SAVE_FOLDER + "/" + name;
    }

    @SuppressWarnings("deprecation")
    private String saveLegacy(File zip) throws IOException {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SAVE_FOLDER);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法创建目录 " + dir.getAbsolutePath());
        String name = zip.getName();
        String stem = name.endsWith(".zip") ? name.substring(0, name.length() - 4) : name;
        File target = new File(dir, name);
        for (int i = 1; target.exists(); i++) target = new File(dir, stem + " (" + i + ").zip");
        OutputStream out = new FileOutputStream(target);
        try {
            copy(zip, out);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            throw e;
        }
        MediaScannerConnection.scanFile(getApplicationContext(), new String[]{target.getAbsolutePath()},
                new String[]{"application/zip"}, null);
        return Environment.DIRECTORY_DOWNLOADS + "/" + SAVE_FOLDER + "/" + target.getName();
    }

    private static void copy(File from, OutputStream out) throws IOException {
        InputStream in = new FileInputStream(from);
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        } finally {
            in.close();
            out.close();
        }
    }

    private void sendSaveResult(boolean ok, String message) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", ok);
            o.put("message", message);
        } catch (JSONException ignored) {
            // ignore
        }
        callJs("onSaveResult(" + jsSafe(o.toString()) + ")");
    }

    private void shareZip() {
        File zip = manager.zipFile();
        if (zip == null) {
            sendSaveResult(false, "压缩包不存在，请重新爬取");
            return;
        }
        Uri uri = new Uri.Builder().scheme("content")
                .authority(getPackageName() + ".share")
                .appendPath(zip.getName())
                .build();
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("application/zip")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, zip.getName())
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newRawUri(zip.getName(), uri));
        try {
            startActivity(Intent.createChooser(send, "分享压缩包"));
        } catch (Exception e) {
            sendSaveResult(false, "没有可用于分享的应用");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                saveZip();
            } else {
                sendSaveResult(false, "没有存储权限，无法保存到下载目录。可以点「分享」把压缩包发送到其他应用。");
            }
        }
    }

    // ==================== JS 桥 ====================

    /** 网页通过 window.Android 调用；方法在 WebView 的后台线程执行 */
    private final class Bridge {

        @JavascriptInterface
        public String getState() {
            return manager.stateJson();
        }

        @JavascriptInterface
        public String getSettings() {
            return manager.settingsJson();
        }

        @JavascriptInterface
        public String start(String url, String settingsJson) {
            String error = manager.start(url, settingsJson);
            JSONObject o = new JSONObject();
            try {
                o.put("ok", error == null);
                o.put("error", error == null ? "" : error);
            } catch (JSONException ignored) {
                // ignore
            }
            if (error == null) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        updateRunningUi();
                        maybeAskNotificationPermission();
                    }
                });
            }
            return o.toString();
        }

        @JavascriptInterface
        public void stop() {
            manager.stop();
        }

        @JavascriptInterface
        public void saveZip() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    MainActivity.this.saveZip();
                }
            });
        }

        @JavascriptInterface
        public void shareZip() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    MainActivity.this.shareZip();
                }
            });
        }
    }
}
