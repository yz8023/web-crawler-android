package com.webcrawler.tool.core;

import java.io.ByteArrayOutputStream;
import java.net.IDN;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 宽松的 URL 解析 / 拼接工具。
 * <p>
 * 对应 crawl.php 中的 parse_url / url_join / normalize_url / build_url。
 * 相对地址按 RFC 3986 解析（即 crawl.php 注释里所说的“等价 Python 的 urljoin”）。
 * 不使用 java.net.URI，因为它对网页里各种不规范的地址过于严格。
 */
public final class UrlUtil {

    private UrlUtil() {
    }

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    /** URL 各组成部分；不存在的部分为 null（path 至少为 ""） */
    public static final class Parts {
        public String scheme;      // 小写
        public boolean hasAuthority;
        public String host;        // 小写，不含端口
        public String port;        // 无端口为 null
        public String path = "";
        public String query;       // 无 ? 为 null
        public String fragment;    // 无 # 为 null

        public String build() {
            StringBuilder sb = new StringBuilder();
            if (scheme != null) sb.append(scheme).append(':');
            if (hasAuthority) {
                sb.append("//");
                if (host != null) sb.append(host);
                if (port != null) sb.append(':').append(port);
            }
            sb.append(path);
            if (query != null) sb.append('?').append(query);
            if (fragment != null) sb.append('#').append(fragment);
            return sb.toString();
        }
    }

    /** 解析 URL（不做任何校验，尽量与 PHP parse_url 的宽松程度一致） */
    public static Parts parse(String s) {
        Parts p = new Parts();
        String rest = s;
        int hash = rest.indexOf('#');
        if (hash >= 0) {
            p.fragment = rest.substring(hash + 1);
            rest = rest.substring(0, hash);
        }
        int q = rest.indexOf('?');
        if (q >= 0) {
            p.query = rest.substring(q + 1);
            rest = rest.substring(0, q);
        }
        int sl = schemeLength(rest);
        if (sl > 0) {
            p.scheme = rest.substring(0, sl).toLowerCase(Locale.ROOT);
            rest = rest.substring(sl + 1);
        }
        if (rest.startsWith("//")) {
            p.hasAuthority = true;
            int end = rest.indexOf('/', 2);
            String auth = end < 0 ? rest.substring(2) : rest.substring(2, end);
            rest = end < 0 ? "" : rest.substring(end);
            int at = auth.lastIndexOf('@');          // 去掉 user:pass@
            if (at >= 0) auth = auth.substring(at + 1);
            String host = auth;
            String port = null;
            if (auth.startsWith("[")) {               // IPv6 字面量
                int rb = auth.indexOf(']');
                if (rb > 0) {
                    host = auth.substring(0, rb + 1);
                    String after = auth.substring(rb + 1);
                    if (after.startsWith(":") && after.length() > 1) port = after.substring(1);
                }
            } else {
                int c = auth.lastIndexOf(':');
                if (c >= 0) {
                    host = auth.substring(0, c);
                    if (c + 1 < auth.length()) port = auth.substring(c + 1);
                }
            }
            p.host = host.toLowerCase(Locale.ROOT);
            p.port = port;
        }
        p.path = rest;
        return p;
    }

    /** scheme 长度（不含冒号）；不是 scheme 返回 -1 */
    public static int schemeLength(String s) {
        int n = s.length();
        if (n == 0 || !isAsciiLetter(s.charAt(0))) return -1;
        for (int i = 1; i < n; i++) {
            char c = s.charAt(i);
            if (c == ':') return i;
            if (!(isAsciiLetter(c) || (c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.')) return -1;
        }
        return -1;
    }

    /** RFC 3986 第 5.2 节：把 ref 解析为基于 base 的绝对地址 */
    public static String resolve(String base, String ref) {
        Parts b = parse(base);
        Parts r = parse(ref);
        Parts t = new Parts();
        if (r.scheme != null) {
            t.scheme = r.scheme;
            t.hasAuthority = r.hasAuthority;
            t.host = r.host;
            t.port = r.port;
            t.path = removeDotSegments(r.path);
            t.query = r.query;
        } else {
            if (r.hasAuthority) {                       // //host/path（协议相对）
                t.hasAuthority = true;
                t.host = r.host;
                t.port = r.port;
                t.path = removeDotSegments(r.path);
                t.query = r.query;
            } else {
                if (r.path.isEmpty()) {                 // 仅 ?query 或空
                    t.path = b.path;
                    t.query = r.query != null ? r.query : b.query;
                } else {
                    if (r.path.startsWith("/")) {       // 根相对
                        t.path = removeDotSegments(r.path);
                    } else {                            // 目录相对
                        t.path = removeDotSegments(merge(b, r.path));
                    }
                    t.query = r.query;
                }
                t.hasAuthority = b.hasAuthority;
                t.host = b.host;
                t.port = b.port;
            }
            t.scheme = b.scheme;
        }
        t.fragment = r.fragment;
        return t.build();
    }

    private static String merge(Parts base, String refPath) {
        if (base.hasAuthority && base.path.isEmpty()) return "/" + refPath;
        int slash = base.path.lastIndexOf('/');
        return slash < 0 ? refPath : base.path.substring(0, slash + 1) + refPath;
    }

    /** RFC 3986 第 5.2.4 节：去掉路径中的 . 和 .. 段 */
    public static String removeDotSegments(String path) {
        if (path.isEmpty() || (path.indexOf("/.") < 0 && !path.startsWith("."))) return path;
        StringBuilder out = new StringBuilder();
        String in = path;
        while (!in.isEmpty()) {
            if (in.startsWith("../")) {
                in = in.substring(3);
            } else if (in.startsWith("./")) {
                in = in.substring(2);
            } else if (in.startsWith("/./")) {
                in = in.substring(2);
            } else if (in.equals("/.")) {
                in = "/";
            } else if (in.startsWith("/../")) {
                in = in.substring(3);
                removeLastSegment(out);
            } else if (in.equals("/..")) {
                in = "/";
                removeLastSegment(out);
            } else if (in.equals(".") || in.equals("..")) {
                in = "";
            } else {
                int start = in.startsWith("/") ? 1 : 0;
                int next = in.indexOf('/', start);
                if (next < 0) next = in.length();
                out.append(in, 0, next);
                in = in.substring(next);
            }
        }
        return out.toString();
    }

    private static void removeLastSegment(StringBuilder out) {
        int idx = out.lastIndexOf("/");
        out.setLength(idx < 0 ? 0 : idx);
    }

    /**
     * 对应 PHP normalize_url：去 fragment、补全相对地址、过滤非 http(s) 协议；非法返回 null。
     * 返回的是规范化后的地址（host 小写、空路径补 /、必要字符做百分号编码），可直接作为去重 key。
     */
    public static String normalizeUrl(String url, String base) {
        if (url == null) return null;
        url = url.trim();
        // 浏览器会忽略 URL 中的换行和制表符（多行 srcset/href 很常见）
        if (url.indexOf('\n') >= 0 || url.indexOf('\r') >= 0 || url.indexOf('\t') >= 0) {
            url = url.replace("\n", "").replace("\r", "").replace("\t", "");
        }
        // 编码 URL 中的空格（如 "Dynamic button.css"），与 PHP 版一致
        url = url.replace(" ", "%20");
        if (url.isEmpty() || url.startsWith("#")) return null;
        int sl = schemeLength(url);
        if (sl > 0) {
            // mailto: / tel: / javascript: / data: 以及 about:、weixin: 等其他协议一律跳过
            String scheme = url.substring(0, sl).toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) return null;
        }
        Parts p = parse(resolve(base, url));
        p.fragment = null;
        if (p.scheme == null || p.host == null || p.host.isEmpty()) return null;
        if (!p.scheme.equals("http") && !p.scheme.equals("https")) return null;
        if (p.path.isEmpty()) p.path = "/";
        p.path = encode(p.path);
        if (p.query != null) p.query = encode(p.query);
        return p.build();
    }

    /** 把规范化地址转成可直接请求的地址（中文域名转 punycode） */
    public static String toRequestUrl(String url) {
        Parts p = parse(url);
        if (p.host != null && !isAscii(p.host)) {
            try {
                p.host = IDN.toASCII(p.host, IDN.ALLOW_UNASSIGNED);
            } catch (IllegalArgumentException ignored) {
                // 保持原样，交给网络层报错
            }
        }
        return p.build();
    }

    /** 对路径 / 查询串中不能直接出现在请求行里的字符做百分号编码，已有的 %XX 统一为大写 */
    static String encode(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        int n = s.length();
        for (int i = 0; i < n; ) {
            char c = s.charAt(i);
            if (c == '%') {
                if (i + 2 < n && isHex(s.charAt(i + 1)) && isHex(s.charAt(i + 2))) {
                    sb.append('%')
                            .append(Character.toUpperCase(s.charAt(i + 1)))
                            .append(Character.toUpperCase(s.charAt(i + 2)));
                    i += 3;
                } else {
                    sb.append("%25");
                    i++;
                }
                continue;
            }
            int cp = s.codePointAt(i);
            int len = Character.charCount(cp);
            if (cp <= 0x20 || cp >= 0x7F || "\"<>\\^`{|}[]".indexOf(cp) >= 0) {
                byte[] bytes = s.substring(i, i + len).getBytes(StandardCharsets.UTF_8);
                for (byte b : bytes) {
                    sb.append('%').append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
                }
            } else {
                sb.append(c);
            }
            i += len;
        }
        return sb.toString();
    }

    /** 等价 PHP rawurldecode（不把 + 当空格），按 UTF-8 解码 */
    public static String rawUrlDecode(String s) {
        if (s.indexOf('%') < 0) return s;
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        int n = s.length();
        for (int i = 0; i < n; ) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < n && isHex(s.charAt(i + 1)) && isHex(s.charAt(i + 2))) {
                out.write((hexVal(s.charAt(i + 1)) << 4) | hexVal(s.charAt(i + 2)));
                i += 3;
            } else {
                int cp = s.codePointAt(i);
                int len = Character.charCount(cp);
                byte[] b = s.substring(i, i + len).getBytes(StandardCharsets.UTF_8);
                out.write(b, 0, b.length);
                i += len;
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    public static String host(String url) {
        String h = parse(url).host;
        return h == null ? "" : h;
    }

    /** 同站判断（含子域名），与 PHP same_site 一致 */
    public static boolean sameSite(String host, String domain) {
        if (host == null || domain == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        domain = domain.toLowerCase(Locale.ROOT);
        int dLen = domain.length();
        int hLen = host.length();
        return host.equals(domain)
                || (hLen > dLen && host.endsWith("." + domain))
                || (dLen > hLen && domain.endsWith("." + host));
    }

    static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static int hexVal(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        return c - 'A' + 10;
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) >= 0x80) return false;
        }
        return true;
    }
}
