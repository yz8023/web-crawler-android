package com.webcrawler.tool.core;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 全站爬虫核心：由 crawl.php 逐函数移植（纯 Java，无 Android 依赖，可在电脑上直接测试）。
 * <p>
 * 流程：BFS 爬取同站全部文件（HTML/CSS/JS 二次深层检索、同站过滤、自动防重命名）
 * → 生成「所有下载文件列表.txt」「下载统计.txt」→ 把域名文件夹打包为 .zip → 删除文件夹。
 */
public final class Crawler {

    public static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    public static final String STOP_USER = "user";
    public static final String STOP_MAX_FILES = "maxFiles";
    public static final String STOP_MAX_RUNTIME = "maxRuntime";
    public static final String STOP_LOW_STORAGE = "lowStorage";

    private static final int CONNECT_TIMEOUT_MS = 10_000;          // CURLOPT_CONNECTTIMEOUT
    private static final int MAX_REDIRECTS = 10;                   // CURLOPT_MAXREDIRS
    private static final int MAX_IN_MEMORY = 8 * 1024 * 1024;      // 超过此大小的响应直接落盘，不做文本处理（控制内存占用）
    private static final int MAX_NAME_BYTES = 200;                 // 单个文件/文件夹名最大字节数（ext4 上限 255）

    private static final Set<String> INDEX_NAMES = new HashSet<>(Arrays.asList(
            "index", "index.html", "index.htm", "index.php"));
    private static final String[] DYNAMIC_EXT = {".php", ".asp", ".aspx", ".jsp", ".cgi", ".pl", ".py", ".rb"};
    private static final String[] SKIP_WORDS = {"logout", "signin", "signup", "register"};
    private static final Set<String> TEXTUAL_TYPES = new HashSet<>(Arrays.asList(
            "text/html", "text/css", "text/plain", "text/xml", "text/csv",
            "application/json", "application/xml", "application/javascript",
            "text/javascript", "image/svg+xml", "application/xhtml+xml",
            "application/rss+xml", "application/atom+xml"));
    private static final Map<String, String> EXT_MAP = new HashMap<>();

    static {
        String[] kv = {
                "text/html", ".html", "text/css", ".css",
                "text/javascript", ".js", "application/javascript", ".js",
                "application/json", ".json", "application/xml", ".xml",
                "image/jpeg", ".jpg", "image/jpg", ".jpg", "image/png", ".png",
                "image/gif", ".gif", "image/svg+xml", ".svg", "image/webp", ".webp",
                "image/bmp", ".bmp", "image/x-icon", ".ico",
                "image/vnd.microsoft.icon", ".ico", "application/pdf", ".pdf",
                "application/zip", ".zip", "application/gzip", ".gz",
                "application/x-tar", ".tar", "application/msword", ".doc",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", ".docx",
                "application/vnd.ms-excel", ".xls",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ".xlsx",
                "application/vnd.ms-powerpoint", ".ppt",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation", ".pptx",
                "text/plain", ".txt", "text/csv", ".csv",
                "audio/mpeg", ".mp3", "audio/wav", ".wav",
                "video/mp4", ".mp4", "video/webm", ".webm",
                "font/woff", ".woff", "font/woff2", ".woff2",
                "font/ttf", ".ttf", "font/otf", ".otf",
                "application/font-woff", ".woff", "application/font-woff2", ".woff2",
                "application/x-font-ttf", ".ttf", "application/x-font-otf", ".otf",
        };
        for (int i = 0; i < kv.length; i += 2) EXT_MAP.put(kv[i], kv[i + 1]);
    }

    /** 配置项（对应 crawl.php 顶部的配置区） */
    public static final class Config {
        public int maxFiles = 0;                  // MAX_FILES，0 = 不限
        public int maxRuntimeSeconds = 0;         // MAX_RUNTIME_SECONDS，0 = 不限
        public int requestTimeoutSeconds = 20;    // REQUEST_TIMEOUT
        public int maxRetries = 3;                // MAX_RETRIES（单个 URL 最多请求次数）
        public long minFreeBytes = 200L * 1024 * 1024; // 手机剩余空间低于此值时自动停止
        public boolean startUrlGuessed = false;   // 用户未输入协议（自动补了 https://），https 连不上时改用 http 重试
    }

    public interface Listener {
        void onProgress(Crawler crawler);
    }

    public static final class Result {
        public boolean success;
        public String error;
        public String startUrl;
        public String siteKey;
        public String zipName;
        public File zipFile;
        public long zipSize;
        public int downloadCount;
        public int visitedCount;
        public int failCount;
        public String stats = "";
        public String fileList = "";
        public final List<String> failed = new ArrayList<>();
        public String stopReason;
        public long elapsedMs;
    }

    private volatile String startUrl;
    private final String startHost;
    private final File baseDir;
    private final File zipDir;
    private final File tmpDir;
    private final Config cfg;
    private final Listener listener;
    private final List<String> siteDomains = new ArrayList<>();

    private volatile boolean stopRequested;
    private volatile HttpURLConnection activeConn;
    private volatile long startedAt;
    private volatile int downloadCount;
    private volatile int visitedCount;
    private volatile int failCount;
    private volatile int queueRemaining;
    private volatile String currentUrl = "";
    private volatile String phase = "crawling";

    /**
     * @param startUrl 已经过 {@link #prepareStartUrl(String)} 处理的起始地址
     * @param baseDir  爬取工作目录（对应 PHP 的 sites/），开始时清空
     * @param zipDir   zip 输出目录，开始时清空（只保留最新一次结果）
     * @param tmpDir   大文件下载临时目录
     */
    public Crawler(String startUrl, File baseDir, File zipDir, File tmpDir, Config cfg, Listener listener) {
        this.startUrl = startUrl;
        this.startHost = UrlUtil.host(startUrl);
        this.baseDir = baseDir;
        this.zipDir = zipDir;
        this.tmpDir = tmpDir;
        this.cfg = cfg;
        this.listener = listener;
    }

    // ==================== 输入校验（对应 crawl.php 请求处理部分） ====================

    /** 用户输入是否没有带协议（会被自动补上 https://） */
    public static boolean isSchemeGuessed(String input) {
        String url = input == null ? "" : input.trim();
        return !url.regionMatches(true, 0, "http://", 0, 7) && !url.regionMatches(true, 0, "https://", 0, 8);
    }

    public static String prepareStartUrl(String input) {
        String url = input == null ? "" : input.trim();
        if (url.isEmpty()) throw new IllegalArgumentException("请先输入要爬取的网址");
        int sl = UrlUtil.schemeLength(url);
        if (sl > 0 && url.startsWith("://", sl)) {
            String scheme = url.substring(0, sl).toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                throw new IllegalArgumentException("仅支持 http / https 协议");
            }
        }
        if (!url.regionMatches(true, 0, "http://", 0, 7) && !url.regionMatches(true, 0, "https://", 0, 8)) {
            url = "https://" + url;
        }
        UrlUtil.Parts p = UrlUtil.parse(url);
        if (p.host == null || p.host.isEmpty()) throw new IllegalArgumentException("网址格式不正确，请检查后重试");
        if (!"http".equals(p.scheme) && !"https".equals(p.scheme)) {
            throw new IllegalArgumentException("仅支持 http / https 协议");
        }
        return url;
    }

    // ==================== 进度 ====================

    public String getStartUrl() { return startUrl; }
    public String getStartHost() { return startHost; }
    public int getDownloadCount() { return downloadCount; }
    public int getVisitedCount() { return visitedCount; }
    public int getFailCount() { return failCount; }
    public int getQueueRemaining() { return queueRemaining; }
    public String getCurrentUrl() { return currentUrl; }
    public String getPhase() { return phase; }
    public boolean isStopRequested() { return stopRequested; }

    public long getElapsedMs() {
        long s = startedAt;
        return s == 0 ? 0 : System.currentTimeMillis() - s;
    }

    /** 停止爬取：已下载的文件仍会生成记录并打包 */
    public void stop() {
        stopRequested = true;
        HttpURLConnection c = activeConn;
        if (c != null) {
            try {
                c.disconnect();
            } catch (Throwable ignored) {
                // ignore
            }
        }
    }

    private void notifyProgress() {
        if (listener != null) listener.onProgress(this);
    }

    // ==================== 主流程 ====================

    public Result run() {
        startedAt = System.currentTimeMillis();
        Result res = new Result();
        try {
            ZipUtil.deleteRecursively(baseDir);
            ZipUtil.deleteRecursively(zipDir);
            ZipUtil.deleteRecursively(tmpDir);
            mkdirs(baseDir);
            mkdirs(zipDir);
            mkdirs(tmpDir);

            String siteHost = crawlSite(res);

            phase = "zipping";
            notifyProgress();
            String siteKey = sanitizeName(siteHost);
            res.siteKey = siteKey;
            res.zipName = siteKey + ".zip";
            File zip = new File(zipDir, res.zipName);
            try {
                ZipUtil.zipFolders(baseDir, zip, siteKey);
            } catch (IOException e) {
                throw new IOException("打包 zip 失败（" + describe(e) + "），请检查手机剩余存储空间", e);
            }
            res.zipFile = zip;
            res.zipSize = zip.length();
            res.success = true;
        } catch (Throwable t) {
            res.success = false;
            res.error = describe(t);
        } finally {
            // 打包后自动删除域名文件夹，只保留 zip（统计和文件列表内容已随结果返回）
            ZipUtil.deleteRecursively(baseDir);
            ZipUtil.deleteRecursively(tmpDir);
            phase = "done";
            res.startUrl = startUrl;
            res.elapsedMs = System.currentTimeMillis() - startedAt;
        }
        return res;
    }

    /** 对应 PHP crawl_site()，返回用于命名 zip 的站点域名 */
    private String crawlSite(Result res) throws IOException {
        siteDomains.add(startHost);
        String siteHost = startHost;

        List<String> queue = new ArrayList<>();      // 待处理队列（BFS）
        queue.add(startUrl);
        int qIdx = 0;
        Set<String> queued = new HashSet<>();        // 已入队去重
        queued.add(startUrl);
        Set<String> visited = new HashSet<>();       // 已访问 URL
        Set<String> savedUrls = new HashSet<>();     // 已保存内容的 URL（含重定向后的地址）
        List<File> downloaded = new ArrayList<>();   // 已下载文件
        Map<String, Set<String>> usedNames = new HashMap<>(); // 每个保存目录的名字占用集合
        List<String> failLog = new ArrayList<>();    // 下载失败记录
        String stopReason = null;
        String startError = null;                    // 起始网址的失败原因

        while (qIdx < queue.size()) {
            if (stopRequested) { stopReason = STOP_USER; break; }
            if (cfg.maxRuntimeSeconds > 0 && System.currentTimeMillis() - startedAt > cfg.maxRuntimeSeconds * 1000L) {
                stopReason = STOP_MAX_RUNTIME;
                break;
            }
            if (cfg.maxFiles > 0 && downloaded.size() >= cfg.maxFiles) { stopReason = STOP_MAX_FILES; break; }
            if (lowStorage()) { stopReason = STOP_LOW_STORAGE; break; }

            boolean isStart = qIdx == 0;
            String current = queue.get(qIdx++);
            queueRemaining = queue.size() - qIdx;
            String normUrl = UrlUtil.normalizeUrl(current, startUrl);
            if (normUrl == null) continue;
            // 起始网址是用户明确要爬的，不做动态脚本 / 关键词过滤
            if (!isStart && !shouldDownloadFile(normUrl)) continue;
            if (visited.contains(normUrl)) continue;
            visited.add(normUrl);
            visitedCount = visited.size();
            currentUrl = normUrl;
            notifyProgress();

            // ---- 下载（带重试） ----
            Fetched f = httpGet(normUrl);
            if (f.body == null && isStart && !stopRequested && cfg.startUrlGuessed
                    && f.status == 0 && normUrl.startsWith("https://")) {
                // 用户没写协议且 https 无法连接（如网站不支持 https），改用 http 再试
                String alt = "http://" + normUrl.substring(8);
                currentUrl = alt;
                notifyProgress();
                Fetched f2 = httpGet(alt);
                if (f2.body != null) {
                    f = f2;
                    normUrl = alt;
                    startUrl = alt;
                    visited.add(alt);
                    visitedCount = visited.size();
                }
            }
            if (f.body == null) {
                if (stopRequested) { stopReason = STOP_USER; break; }
                if (isStart) startError = f.error;
                failLog.add(normUrl + "  —  " + f.error);
                failCount = failLog.size();
                continue;
            }

            try {
                // ---- 处理重定向：同站内以最终地址为准，避免同一内容重复保存 ----
                String finalUrl = f.finalUrl;
                String finalHost = UrlUtil.host(finalUrl);
                if (isStart && !finalHost.equals(startHost)) {
                    // 起始地址跳转到了别的域名（如 example.com -> www.example.com），以跳转后的站点为准
                    if (!sameSiteAny(finalHost)) siteDomains.add(finalHost);
                    siteHost = finalHost;
                }
                String saveUrl = sameSiteAny(finalHost) ? finalUrl : normUrl;
                if (!saveUrl.equals(normUrl)) {
                    if (savedUrls.contains(saveUrl)) continue;   // 跳转到已下载过的页面
                    visited.add(saveUrl);
                    visitedCount = visited.size();
                }

                // ---- 解析保存路径和文件名，保存文件 ----
                Saved saved;
                try {
                    saved = saveResponse(saveUrl, f, usedNames);
                } catch (IOException | RuntimeException | OutOfMemoryError e) {
                    failLog.add(normUrl + "  —  保存失败：" + describe(e));
                    failCount = failLog.size();
                    continue;
                }
                downloaded.add(saved.file);
                downloadCount = downloaded.size();
                savedUrls.add(normUrl);
                savedUrls.add(saveUrl);

                // ---- 二次深层检索：按类型提取嵌套引用（相对地址以最终地址为基准） ----
                for (String link : saved.links) {
                    String n = UrlUtil.normalizeUrl(link, finalUrl);
                    if (n != null && sameSiteAny(UrlUtil.host(n)) && !queued.contains(n) && !visited.contains(n)) {
                        queued.add(n);
                        queue.add(n);
                    }
                }
                queueRemaining = queue.size() - qIdx;
                notifyProgress();
            } finally {
                f.release();
            }
        }

        if (downloaded.isEmpty() && startError != null && stopReason == null) {
            // 起始网址都打不开，打包一个空压缩包没有意义，直接报错
            throw new IOException("无法访问 " + startUrl + "：" + startError + "，请检查网址或网络连接");
        }

        // ---- 生成记录文件 ----
        File recordDir = new File(baseDir, sanitizeName(siteHost));
        mkdirs(recordDir);

        StringBuilder list = new StringBuilder("=== 所有下载文件列表 ===\n\n");
        String basePath = baseDir.getAbsolutePath();
        for (File file : downloaded) {
            String p = file.getAbsolutePath();
            if (p.startsWith(basePath)) p = p.substring(basePath.length());
            while (p.startsWith("/") || p.startsWith("\\")) p = p.substring(1);
            list.append(p.replace('\\', '/')).append('\n');
        }
        writeFile(new File(recordDir, "所有下载文件列表.txt"), list.toString().getBytes(StandardCharsets.UTF_8));

        String stats = "=== 下载统计 ===\n\n"
                + "总下载文件数: " + downloaded.size() + "\n"
                + "访问的URL数量: " + visited.size() + "\n"
                + "起始URL: " + startUrl + "\n"
                + "下载时间: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + "\n";
        writeFile(new File(recordDir, "下载统计.txt"), stats.getBytes(StandardCharsets.UTF_8));

        res.downloadCount = downloaded.size();
        res.visitedCount = visited.size();
        res.failCount = failLog.size();
        res.failed.addAll(failLog);
        res.stats = stats;
        res.fileList = list.toString();
        res.stopReason = stopReason;
        return siteHost;
    }

    // ==================== 保存 ====================

    private static final class Saved {
        File file;
        List<String> links = Collections.emptyList();
    }

    private Saved saveResponse(String saveUrl, Fetched f, Map<String, Set<String>> usedNames) throws IOException {
        String[] st = parseUrlStructure(saveUrl);   // 域名, 目录路径, 文件名, query
        String fullName = st[2];
        String ext = getFileExtensionFromUrl(saveUrl, f.contentType);

        String filename;
        int dot = fullName.lastIndexOf('.');
        if (dot >= 0) {
            String fileExt = fullName.substring(dot + 1);
            filename = fullName.substring(0, dot);
            if (ext.isEmpty() && !fileExt.isEmpty()) ext = "." + fileExt;
        } else {
            filename = fullName;
        }

        String main = mainType(f.contentType);
        if (main.contains("html") && isDynamicExt(ext)) ext = ".html";   // 如起始网址是 index.php
        if (ext.isEmpty()) {
            if (main.contains("html")) ext = ".html";
            else if (main.startsWith("text/")) ext = ".txt";
            else ext = ".bin";
        }

        String cleaned = sanitizeName(filename);
        if (cleaned.isEmpty()) cleaned = "index";   // 主页/无名文件用 index
        // 带 query 的 URL 加哈希后缀，避免不同参数互相覆盖
        if (!st[3].isEmpty()) cleaned += "_" + md5(st[3]).substring(0, 6);
        ext = "." + sanitizeName(ext.substring(1));
        cleaned = limitName(cleaned, ext);

        File saveDir = createNestedFolders(st[0], st[1]);
        String dirKey = saveDir.getAbsolutePath();
        Set<String> used = usedNames.get(dirKey);
        if (used == null) {
            used = new HashSet<>();
            usedNames.put(dirKey, used);
        }
        String fullFilename = makeUniqueFilename(saveDir, cleaned + ext, used);
        File file = new File(saveDir, fullFilename);
        if (!file.getCanonicalPath().startsWith(baseDir.getCanonicalPath() + File.separator)) {
            throw new IOException("非法保存路径");
        }

        // 决定是否做深层检索
        boolean htmlType = main.contains("html");
        int kind = 0; // 1 html, 2 css, 3 js
        if (htmlType && (ext.equals(".html") || ext.equals(".htm"))) kind = 1;
        else if (ext.equals(".css")) kind = 2;
        else if (ext.equals(".js") || ext.equals(".mjs")) kind = 3;
        else if (htmlType) kind = 1;   // 如 .shtml / 无扩展名的页面，按 HTML 解析

        Saved saved = new Saved();
        saved.file = file;
        if (f.body.bytes != null) {
            byte[] data = f.body.bytes;
            boolean textType = isTextType(f.contentType);
            TextCodec.Decoded decoded = null;
            if (textType || kind != 0) {
                decoded = TextCodec.toUtf8(data, f.contentType, kind == 1, kind == 2 || main.equals("text/css"));
            }
            // ---- 保存文件：文本统一转 UTF-8，其余原样 ----
            writeFile(file, textType ? decoded.utf8 : data);
            if (kind == 1) saved.links = LinkExtractor.fromHtml(decoded.text);
            else if (kind == 2) saved.links = LinkExtractor.fromCss(decoded.text);
            else if (kind == 3) saved.links = LinkExtractor.fromJs(decoded.text);
        } else {
            // 超大文件：临时文件直接移动过去
            File tmp = f.body.file;
            if (!tmp.renameTo(file)) {
                copyFile(tmp, file);
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
        return saved;
    }

    /** 返回（域名, 目录路径, 文件名, query），对应 PHP parse_url_structure */
    static String[] parseUrlStructure(String url) {
        UrlUtil.Parts parts = UrlUtil.parse(url);
        String host = parts.host == null ? "" : parts.host;
        String path = rtrimSlashes(parts.path);
        // 解码 URL 编码字符（%20 等），避免文件名变成 xxx_20button.css
        path = rtrimSlashes(UrlUtil.rawUrlDecode(path));
        String query = parts.query == null ? "" : parts.query;
        int slash = path.lastIndexOf('/');
        String fullName = path.substring(slash + 1);
        if (fullName.isEmpty()) fullName = "index";
        String dirPath = slash > 0 ? path.substring(0, slash) : "";
        while (dirPath.startsWith("/")) dirPath = dirPath.substring(1);
        if (dirPath.equals(".")) dirPath = "";
        if (path.isEmpty() || INDEX_NAMES.contains(fullName.toLowerCase(Locale.ROOT))) {
            // 主页固定为 index；子目录下的 index.html 保留所在目录
            return new String[]{host, dirPath, "index", query};
        }
        return new String[]{host, dirPath, fullName, query};
    }

    /** 从 URL / Content-Type 推断扩展名，对应 PHP get_file_extension_from_url */
    static String getFileExtensionFromUrl(String url, String contentType) {
        String path = UrlUtil.parse(url).path;
        if (path.indexOf('.') >= 0) {
            String trimmed = rtrimSlashes(path);
            String base = trimmed.substring(trimmed.lastIndexOf('/') + 1);
            int dot = base.lastIndexOf('.');
            if (dot >= 0) {
                String ext = base.substring(dot + 1).toLowerCase(Locale.ROOT);
                if (!ext.isEmpty() && ext.length() <= 10) return "." + ext;
            }
        }
        String mapped = EXT_MAP.get(mainType(contentType));
        return mapped == null ? "" : mapped;
    }

    /** 跳过动态脚本与登录类地址，对应 PHP should_download_file（关键词只检查路径和参数，不检查域名） */
    static boolean shouldDownloadFile(String url) {
        UrlUtil.Parts p = UrlUtil.parse(url);
        String path = p.path.toLowerCase(Locale.ROOT);
        if (isDynamicExt(path)) return false;
        String lower = p.query == null ? path : path + "?" + p.query.toLowerCase(Locale.ROOT);
        for (String w : SKIP_WORDS) {
            if (lower.contains(w)) return false;
        }
        return true;
    }

    private static boolean isDynamicExt(String pathOrExt) {
        for (String ext : DYNAMIC_EXT) {
            if (pathOrExt.endsWith(ext)) return true;
        }
        return false;
    }

    /** 文本类 MIME 判定（按文本保存，其余一律二进制），对应 PHP is_text_type */
    static boolean isTextType(String contentType) {
        String main = mainType(contentType);
        if (TEXTUAL_TYPES.contains(main)) return true;
        if (main.startsWith("text/")) return true;
        return main.contains("javascript") || main.contains("json") || main.contains("xml");
    }

    static String mainType(String contentType) {
        if (contentType == null) return "";
        int semi = contentType.indexOf(';');
        return (semi >= 0 ? contentType.substring(0, semi) : contentType).trim().toLowerCase(Locale.ROOT);
    }

    /** 清洗名称，保留中文等各国文字、字母、数字、下划线、点、短横线（等价 PHP 的 /[^\w\.\-]/u） */
    static String sanitizeName(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); ) {
            int cp = name.codePointAt(i);
            sb.appendCodePoint(isWordChar(cp) || cp == '.' || cp == '-' ? cp : '_');
            i += Character.charCount(cp);
        }
        return sb.toString();
    }

    private static boolean isWordChar(int cp) {
        if (cp == '_') return true;
        if (cp < 0x80) return (cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z') || (cp >= '0' && cp <= '9');
        if (Character.isLetter(cp)) return true;
        int t = Character.getType(cp);
        return t == Character.DECIMAL_DIGIT_NUMBER || t == Character.LETTER_NUMBER
                || t == Character.OTHER_NUMBER || t == Character.NON_SPACING_MARK
                || t == Character.CONNECTOR_PUNCTUATION;
    }

    /** 同目录冲突时自动加 _2 _3...（不区分大小写，保证 zip 在 Windows 上解压不冲突） */
    static String makeUniqueFilename(File dir, String filename, Set<String> used) {
        String key = filename.toLowerCase(Locale.ROOT);
        if (!used.contains(key) && !new File(dir, filename).exists()) {
            used.add(key);
            return filename;
        }
        int dot = filename.lastIndexOf('.');
        String ext = dot >= 0 ? filename.substring(dot + 1) : "";
        String stem = !ext.isEmpty() ? filename.substring(0, filename.length() - ext.length() - 1) : filename;
        for (int i = 2; ; i++) {
            String candidate = !ext.isEmpty() ? stem + "_" + i + "." + ext : stem + "_" + i;
            String ck = candidate.toLowerCase(Locale.ROOT);
            if (!used.contains(ck) && !new File(dir, candidate).exists()) {
                used.add(ck);
                return candidate;
            }
        }
    }

    /** 按 URL 目录结构创建嵌套文件夹，对应 PHP create_nested_folders */
    private File createNestedFolders(String domain, String dirPath) throws IOException {
        File dir = new File(baseDir, limitName(sanitizeName(domain), ""));
        if (!dirPath.isEmpty()) {
            for (String part : dirPath.split("/")) {
                String s = sanitizeName(part);
                if (s.isEmpty() || s.equals(".") || s.equals("..")) continue;   // 防止路径穿越
                s = limitName(s, "");
                File next = new File(dir, s);
                if (next.isFile()) next = new File(dir, s + "_dir");          // 与已保存的文件同名
                dir = next;
            }
        }
        mkdirs(dir);
        return dir;
    }

    /** 限制名称长度（UTF-8 字节），超长时截断并加哈希保证唯一 */
    private static String limitName(String stem, String ext) {
        int extBytes = ext.getBytes(StandardCharsets.UTF_8).length;
        if (stem.getBytes(StandardCharsets.UTF_8).length + extBytes <= MAX_NAME_BYTES) return stem;
        String hash = "_" + md5(stem).substring(0, 8);
        int budget = MAX_NAME_BYTES - extBytes - hash.length();
        StringBuilder sb = new StringBuilder();
        int bytes = 0;
        for (int i = 0; i < stem.length(); ) {
            int cp = stem.codePointAt(i);
            int len = Character.charCount(cp);
            int b = stem.substring(i, i + len).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + b > budget) break;
            sb.appendCodePoint(cp);
            bytes += b;
            i += len;
        }
        return sb + hash;
    }

    private boolean sameSiteAny(String host) {
        for (String d : siteDomains) {
            if (UrlUtil.sameSite(host, d)) return true;
        }
        return false;
    }

    private boolean lowStorage() {
        if (cfg.minFreeBytes <= 0) return false;
        long usable = baseDir.getUsableSpace();
        return usable > 0 && usable < cfg.minFreeBytes;
    }

    // ==================== 网络 ====================

    private static final class Body {
        byte[] bytes;   // 内存中的内容
        File file;      // 或：超大文件落盘
    }

    private static final class Fetched {
        int status;
        String contentType;
        String finalUrl;
        Body body;
        String error;

        void release() {
            if (body != null && body.file != null && body.file.exists()) {
                //noinspection ResultOfMethodCallIgnored
                body.file.delete();
            }
        }
    }

    /** 下载 URL（带重试），对应 PHP http_get */
    private Fetched httpGet(String url) {
        String lastError = "下载失败";
        int lastStatus = 0;
        int attempts = Math.max(1, cfg.maxRetries);
        for (int i = 0; i < attempts; i++) {
            if (stopRequested) return failed("已停止");
            try {
                Fetched f = fetchOnce(url);
                if (f.status >= 200 && f.status < 400 && f.body != null) return f;
                lastError = "HTTP " + f.status;
                lastStatus = f.status;
                // 4xx（除 408/429）重试也没用，直接判定失败
                if (f.status >= 400 && f.status < 500 && f.status != 408 && f.status != 429) {
                    Fetched r = failed(lastError);
                    r.status = f.status;
                    return r;
                }
            } catch (IOException | RuntimeException e) {
                if (stopRequested) return failed("已停止");
                lastError = describe(e);
                lastStatus = 0;
            } catch (OutOfMemoryError e) {
                return failed("内存不足");
            }
            if (i < attempts - 1) {
                try {
                    Thread.sleep(300);   // 0.3 秒后退重试
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return failed("已停止");
                }
            }
        }
        Fetched r = failed(attempts > 1 ? lastError + "（已尝试 " + attempts + " 次）" : lastError);
        r.status = lastStatus;
        return r;
    }

    private static Fetched failed(String error) {
        Fetched f = new Fetched();
        f.error = error;
        return f;
    }

    /** 单次请求：手动跟随重定向（与 curl FOLLOWLOCATION 一致，支持 http↔https 跳转） */
    private Fetched fetchOnce(String url) throws IOException {
        long deadline = System.currentTimeMillis() + cfg.requestTimeoutSeconds * 1000L;
        String current = url;
        for (int hop = 0; ; hop++) {
            int remaining = (int) Math.max(1, deadline - System.currentTimeMillis());
            HttpURLConnection conn = open(current, remaining);
            activeConn = conn;
            boolean keep = false;
            try {
                if (stopRequested) throw new IOException("已停止");
                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = conn.getHeaderField("Location");
                    if (loc != null && !loc.trim().isEmpty()) {
                        if (hop >= MAX_REDIRECTS) throw new IOException("重定向次数过多");
                        String next = UrlUtil.normalizeUrl(loc, current);
                        if (next == null) throw new IOException("无效的重定向地址");
                        drain(conn, true);
                        keep = true;
                        current = next;
                        continue;
                    }
                }
                Fetched f = new Fetched();
                f.status = code;
                f.finalUrl = current;
                if (code < 200 || code >= 400) {
                    drain(conn, false);
                    keep = true;
                    return f;
                }
                f.contentType = conn.getContentType();
                f.body = readBody(conn.getInputStream(), deadline);
                keep = true;
                return f;
            } finally {
                activeConn = null;
                if (!keep) {
                    try {
                        conn.disconnect();
                    } catch (Throwable ignored) {
                        // ignore
                    }
                }
            }
        }
    }

    private HttpURLConnection open(String url, int timeoutMs) throws IOException {
        URL u = new URL(UrlUtil.toRequestUrl(url));
        HttpURLConnection conn = (HttpURLConnection) u.openConnection();
        if (conn instanceof HttpsURLConnection) {
            // 与 PHP 版 CURLOPT_SSL_VERIFYPEER=false 一致：不校验证书，兼容证书过期 / 自签名的网站
            SSLSocketFactory factory = lenientSslFactory();
            if (factory != null) {
                HttpsURLConnection https = (HttpsURLConnection) conn;
                https.setSSLSocketFactory(factory);
                https.setHostnameVerifier(LENIENT_HOSTS);
            }
        }
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(Math.min(CONNECT_TIMEOUT_MS, timeoutMs));
        conn.setReadTimeout(timeoutMs);
        conn.setUseCaches(false);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Accept", "*/*");
        return conn;
    }

    private Body readBody(InputStream in, long deadline) throws IOException {
        ByteArrayOutputStream mem = new ByteArrayOutputStream(16 * 1024);
        File spill = null;
        OutputStream fileOut = null;
        long total = 0;
        byte[] buf = new byte[32 * 1024];
        boolean ok = false;
        try {
            int n;
            while ((n = in.read(buf)) != -1) {
                if (stopRequested) throw new IOException("已停止");
                if (System.currentTimeMillis() > deadline) throw new SocketTimeoutException("timeout");
                if (fileOut == null && total + n > MAX_IN_MEMORY) {
                    spill = File.createTempFile("download", ".tmp", tmpDir);
                    fileOut = new FileOutputStream(spill);
                    mem.writeTo(fileOut);
                    mem = null;
                }
                if (fileOut != null) fileOut.write(buf, 0, n);
                else mem.write(buf, 0, n);
                total += n;
            }
            if (fileOut != null) {
                fileOut.close();
                fileOut = null;
            }
            ok = true;
        } finally {
            closeQuietly(in);
            closeQuietly(fileOut);
            if (!ok && spill != null) {
                //noinspection ResultOfMethodCallIgnored
                spill.delete();
            }
        }
        Body body = new Body();
        if (spill != null) body.file = spill;
        else body.bytes = mem.toByteArray();
        return body;
    }

    private static void drain(HttpURLConnection conn, boolean success) {
        try {
            InputStream in = success ? conn.getInputStream() : conn.getErrorStream();
            if (in != null) {
                byte[] buf = new byte[8192];
                int total = 0;
                int n;
                while (total < 64 * 1024 && (n = in.read(buf)) != -1) total += n;
                in.close();
            }
        } catch (IOException ignored) {
            // ignore
        }
    }

    private static final HostnameVerifier LENIENT_HOSTS = new HostnameVerifier() {
        @Override
        public boolean verify(String hostname, SSLSession session) {
            return true;
        }
    };

    private static SSLSocketFactory lenientFactory;

    private static synchronized SSLSocketFactory lenientSslFactory() {
        if (lenientFactory == null) {
            try {
                TrustManager[] tms = {new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }};
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, tms, new SecureRandom());
                lenientFactory = ctx.getSocketFactory();
            } catch (Exception e) {
                return null;
            }
        }
        return lenientFactory;
    }

    // ==================== 工具 ====================

    public static String describe(Throwable e) {
        if (e instanceof SocketTimeoutException) return "请求超时";
        if (e instanceof UnknownHostException) return "域名解析失败";
        if (e instanceof ConnectException) return "连接失败";
        if (e instanceof SSLException) return "SSL 连接错误";
        if (e instanceof java.net.SocketException || e instanceof java.io.EOFException) return "网络连接中断";
        if (e instanceof OutOfMemoryError) return "内存不足";
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }

    private static String rtrimSlashes(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '/') end--;
        return s.substring(0, end);
    }

    static String md5(String s) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : d) sb.append(String.format(Locale.US, "%02x", b & 0xFF));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode()) + "00000000";
        }
    }

    private static void mkdirs(File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("无法创建目录：" + dir.getName());
        }
    }

    private static void writeFile(File f, byte[] data) throws IOException {
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(data);
        } finally {
            out.close();
        }
    }

    private static void copyFile(File from, File to) throws IOException {
        InputStream in = new FileInputStream(from);
        try {
            OutputStream out = new FileOutputStream(to);
            try {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }

    private static void closeQuietly(Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
            // ignore
        }
    }
}
