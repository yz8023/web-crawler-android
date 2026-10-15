package com.webcrawler.tool.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 链接提取（含二次深层检索），对应 crawl.php 中的
 * extract_links_from_html / extract_links_from_css / extract_links_from_js。
 * <p>
 * PHP 版用 DOMDocument 解析 HTML；这里用一个轻量的标签扫描器代替，
 * 输出顺序与 PHP 版一致：先按标签种类（a、link、img…）分组，再是 meta refresh、
 * &lt;style&gt; 中的 CSS 链接，最后是 xlink:href，然后去重。
 */
public final class LinkExtractor {

    private LinkExtractor() {
    }

    private static final String[] TAGS = {"a", "link", "img", "script", "iframe", "embed", "object",
            "audio", "video", "source", "track", "frame", "area", "form",
            "use", "button", "input"};
    private static final String[] ATTRS = {"href", "src", "data-src", "data-href", "data-original",
            "action", "poster", "formaction"};
    private static final String[] SRCSET_ATTRS = {"srcset", "data-srcset", "imagesrcset"};

    private static final Pattern CSS_URL = Pattern.compile(
            "url\\(\\s*['\"]?([^'\")\\s]+)['\"]?\\s*\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CSS_IMPORT = Pattern.compile(
            "@import\\s+(?:url\\(\\s*)?['\"]([^'\"]+)['\"]", Pattern.CASE_INSENSITIVE);
    private static final Pattern JS_ASSET = Pattern.compile(
            "[\"']((?:\\.{1,2}/|/)?[^\"'<>\\\\\\s]+\\.(?:js|css|mjs|png|jpe?g|gif|svg|webp|ico|woff2?|ttf|otf|eot|mp4|webm|mp3|wav|json))(\\?[^\"']*)?[\"']",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern META_REFRESH_URL = Pattern.compile(
            "url\\s*=\\s*([^;]+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern XLINK_HREF = Pattern.compile(
            "xlink:href\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    /** CSS 中的 url() 和 @import */
    public static List<String> fromCss(String content) {
        List<String> links = new ArrayList<>();
        Matcher m = CSS_URL.matcher(content);
        while (m.find()) links.add(m.group(1));
        m = CSS_IMPORT.matcher(content);
        while (m.find()) links.add(m.group(1));
        return links;
    }

    /** JS 中的静态资源字符串 */
    public static List<String> fromJs(String content) {
        List<String> links = new ArrayList<>();
        Matcher m = JS_ASSET.matcher(content);
        while (m.find()) {
            String raw = m.group();
            links.add(raw.substring(1, raw.length() - 1));
        }
        return links;
    }

    /** HTML 中所有可提取的链接（a/link/img/script/... + srcset + style + meta refresh） */
    public static List<String> fromHtml(String content) {
        // 去掉注释，避免把注释里的链接也抓进来
        content = stripComments(content);

        Map<String, List<String>> byTag = new HashMap<>();
        for (String t : TAGS) byTag.put(t, new ArrayList<String>());
        List<String> metaLinks = new ArrayList<>();
        List<String> styleTexts = new ArrayList<>();

        scan(content, byTag, metaLinks, styleTexts);

        List<String> links = new ArrayList<>();
        for (String t : TAGS) links.addAll(byTag.get(t));
        links.addAll(metaLinks);
        for (String css : styleTexts) {
            if (!css.isEmpty()) links.addAll(fromCss(css));
        }
        // 兜底：SVG use 的 xlink:href
        Matcher m = XLINK_HREF.matcher(content);
        while (m.find()) links.add(m.group(1));

        return new ArrayList<>(new LinkedHashSet<>(links));
    }

    static String stripComments(String s) {
        if (s.indexOf("<!--") < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (true) {
            int open = s.indexOf("<!--", i);
            if (open < 0) break;
            int close = s.indexOf("-->", open + 4);
            if (close < 0) break;   // 未闭合的注释保持原样（与正则 /<!--.*?-->/s 行为一致）
            sb.append(s, i, open);
            i = close + 3;
        }
        sb.append(s, i, s.length());
        return sb.toString();
    }

    /** 轻量 HTML 标签扫描：正确处理引号内的 '>'，script/style 内容按原始文本跳过 */
    private static void scan(String s, Map<String, List<String>> byTag,
                             List<String> metaLinks, List<String> styleTexts) {
        int n = s.length();
        int i = 0;
        while (i < n) {
            int lt = s.indexOf('<', i);
            if (lt < 0 || lt + 1 >= n) return;
            char c = s.charAt(lt + 1);
            if (c == '!') {
                int end;
                if (s.startsWith("<!--", lt)) {
                    end = s.indexOf("-->", lt + 4);
                    if (end < 0) return;
                    i = end + 3;
                } else {
                    end = s.indexOf('>', lt + 2);
                    if (end < 0) return;
                    i = end + 1;
                }
                continue;
            }
            if (c == '?' || c == '/') {
                int end = s.indexOf('>', lt + 2);
                if (end < 0) return;
                i = end + 1;
                continue;
            }
            if (!UrlUtil.isAsciiLetter(c)) {
                i = lt + 1;
                continue;
            }
            int p = lt + 1;
            while (p < n && isTagNameChar(s.charAt(p))) p++;
            String tag = s.substring(lt + 1, p).toLowerCase(Locale.ROOT);
            Map<String, String> attrs = new HashMap<>();
            p = parseAttributes(s, p, attrs);
            handleTag(tag, attrs, byTag, metaLinks);
            if (tag.equals("script") || tag.equals("style")) {
                int close = indexOfIgnoreCase(s, "</" + tag, p);
                int end = close < 0 ? n : close;
                if (tag.equals("style")) styleTexts.add(s.substring(p, end));
                i = end;
            } else {
                i = p;
            }
        }
    }

    private static int parseAttributes(String s, int p, Map<String, String> attrs) {
        int n = s.length();
        while (p < n) {
            char ch = s.charAt(p);
            if (ch == '>') return p + 1;
            if (isSpace(ch) || ch == '/') {
                p++;
                continue;
            }
            int ns = p;
            while (p < n) {
                ch = s.charAt(p);
                if (isSpace(ch) || ch == '>' || ch == '/' || (ch == '=' && p > ns)) break;
                p++;
            }
            String name = s.substring(ns, p).toLowerCase(Locale.ROOT);
            while (p < n && isSpace(s.charAt(p))) p++;
            String value = "";
            if (p < n && s.charAt(p) == '=') {
                p++;
                while (p < n && isSpace(s.charAt(p))) p++;
                if (p < n) {
                    char qc = s.charAt(p);
                    if (qc == '"' || qc == '\'') {
                        int end = s.indexOf(qc, p + 1);
                        if (end < 0) {
                            value = s.substring(p + 1);
                            p = n;
                        } else {
                            value = s.substring(p + 1, end);
                            p = end + 1;
                        }
                    } else {
                        int vs = p;
                        while (p < n && !isSpace(s.charAt(p)) && s.charAt(p) != '>') p++;
                        value = s.substring(vs, p);
                    }
                }
            }
            // 与 DOM 一致：重复属性只取第一个；属性值解码 HTML 实体（&amp; -> &）
            if (!name.isEmpty() && !attrs.containsKey(name)) attrs.put(name, decodeEntities(value));
        }
        return n;
    }

    private static void handleTag(String tag, Map<String, String> attrs,
                                  Map<String, List<String>> byTag, List<String> metaLinks) {
        List<String> out = byTag.get(tag);
        if (out != null) {
            for (String a : ATTRS) {
                String v = attrs.get(a);
                if (v != null && !v.isEmpty()) out.add(v);
            }
            for (String a : SRCSET_ATTRS) {
                String v = attrs.get(a);
                if (v == null) continue;
                for (String candidate : v.split(",", -1)) {
                    String t = candidate.trim();
                    if (t.isEmpty()) continue;
                    String first = t.split("\\s+", 2)[0].trim();
                    if (!first.isEmpty()) out.add(first);
                }
            }
        } else if (tag.equals("meta")) {
            // meta refresh 跳转
            String httpEquiv = attrs.get("http-equiv");
            String content = attrs.get("content");
            if (httpEquiv != null && content != null && httpEquiv.toLowerCase(Locale.ROOT).equals("refresh")) {
                Matcher m = META_REFRESH_URL.matcher(content);
                if (m.find()) {
                    String u = trimChars(m.group(1), " \t\n\r\"'");
                    if (!u.isEmpty()) metaLinks.add(u);
                }
            }
        }
    }

    static String decodeEntities(String s) {
        if (s.indexOf('&') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '&') {
                int semi = s.indexOf(';', i + 1);
                if (semi > i + 1 && semi - i <= 12) {
                    String ent = s.substring(i + 1, semi);
                    String rep = null;
                    if (ent.charAt(0) == '#') {
                        try {
                            int cp = (ent.length() > 1 && (ent.charAt(1) == 'x' || ent.charAt(1) == 'X'))
                                    ? Integer.parseInt(ent.substring(2), 16)
                                    : Integer.parseInt(ent.substring(1));
                            if (cp > 0 && cp <= 0x10FFFF) rep = new String(Character.toChars(cp));
                        } catch (NumberFormatException ignored) {
                            // 非法数字实体，保持原样
                        }
                    } else {
                        switch (ent) {
                            case "amp": rep = "&"; break;
                            case "lt": rep = "<"; break;
                            case "gt": rep = ">"; break;
                            case "quot": rep = "\""; break;
                            case "apos": rep = "'"; break;
                            case "nbsp": rep = "\u00A0"; break;
                            default: break;
                        }
                    }
                    if (rep != null) {
                        sb.append(rep);
                        i = semi + 1;
                        continue;
                    }
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private static String trimChars(String s, String chars) {
        int start = 0;
        int end = s.length();
        while (start < end && chars.indexOf(s.charAt(start)) >= 0) start++;
        while (end > start && chars.indexOf(s.charAt(end - 1)) >= 0) end--;
        return s.substring(start, end);
    }

    private static int indexOfIgnoreCase(String s, String needle, int from) {
        int n = s.length() - needle.length();
        for (int i = Math.max(0, from); i <= n; i++) {
            if (s.regionMatches(true, i, needle, 0, needle.length())) return i;
        }
        return -1;
    }

    private static boolean isTagNameChar(char c) {
        return UrlUtil.isAsciiLetter(c) || (c >= '0' && c <= '9') || c == '-' || c == ':' || c == '_';
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f';
    }
}
