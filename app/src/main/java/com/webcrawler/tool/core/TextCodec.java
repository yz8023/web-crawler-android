package com.webcrawler.tool.core;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文本统一转成 UTF-8（对应 crawl.php 的 to_utf8，兼容 GBK / BIG-5 等网页）。
 * <p>
 * 顺序：合法 UTF-8 原样保留 → 网页声明的编码（Content-Type / meta / @charset / xml 声明）
 * → GB18030 → BIG-5 → Windows-1252。转码后同步把页面里声明的编码改成 utf-8，
 * 否则用浏览器打开保存下来的页面会乱码。
 */
public final class TextCodec {

    private TextCodec() {
    }

    public static final class Decoded {
        public final String text;
        public final byte[] utf8;
        public final boolean converted;

        Decoded(String text, byte[] utf8, boolean converted) {
            this.text = text;
            this.utf8 = utf8;
            this.converted = converted;
        }
    }

    private static final Pattern CT_CHARSET = Pattern.compile(
            "charset\\s*=\\s*[\"']?\\s*([A-Za-z0-9_\\-:.]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern META_CHARSET = Pattern.compile(
            "<meta\\b[^>]*?charset\\s*=\\s*[\"']?\\s*([A-Za-z0-9_\\-:.]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CSS_CHARSET = Pattern.compile(
            "^\\s*@charset\\s+[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern XML_ENCODING = Pattern.compile(
            "^\\s*<\\?xml\\b[^>]*?encoding\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    private static final Pattern META_CHARSET_REWRITE = Pattern.compile(
            "(<meta\\b[^>]*?charset\\s*=\\s*[\"']?\\s*)([A-Za-z0-9_\\-:.]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CSS_CHARSET_REWRITE = Pattern.compile(
            "^(\\uFEFF?\\s*@charset\\s+[\"'])([^\"']+)([\"'])", Pattern.CASE_INSENSITIVE);
    private static final Pattern XML_ENCODING_REWRITE = Pattern.compile(
            "^(\\uFEFF?\\s*<\\?xml\\b[^>]*?encoding\\s*=\\s*[\"'])([^\"']+)([\"'])", Pattern.CASE_INSENSITIVE);

    /**
     * @param isHtml 是否为 HTML（决定是否改写 meta charset）
     * @param isCss  是否为 CSS（决定是否改写 @charset）
     */
    public static Decoded toUtf8(byte[] body, String contentType, boolean isHtml, boolean isCss) {
        String s = decodeStrict(body, StandardCharsets.UTF_8);
        if (s != null) return new Decoded(s, body, false);   // 已是 UTF-8：字节原样保留

        String text = null;
        String label = declaredCharset(body, contentType);
        if (label != null) {
            Charset cs = lookup(label);
            if (cs != null) text = new String(body, cs);       // 按声明编码宽松解码（与浏览器一致）
        }
        if (text == null) text = decodeStrict(body, charset("GB18030"));
        if (text == null) text = decodeStrict(body, charset("Big5"));
        if (text == null) {
            Charset cp1252 = charset("windows-1252");
            text = new String(body, cp1252 != null ? cp1252 : StandardCharsets.ISO_8859_1);
        }

        if (isHtml) {
            text = META_CHARSET_REWRITE.matcher(text).replaceAll("$1utf-8");
        }
        if (isCss) {
            text = CSS_CHARSET_REWRITE.matcher(text).replaceFirst("$1utf-8$3");
        }
        text = XML_ENCODING_REWRITE.matcher(text).replaceFirst("$1UTF-8$3");
        return new Decoded(text, text.getBytes(StandardCharsets.UTF_8), true);
    }

    private static String declaredCharset(byte[] body, String contentType) {
        if (contentType != null) {
            Matcher m = CT_CHARSET.matcher(contentType);
            if (m.find()) return m.group(1);
        }
        String head = new String(body, 0, Math.min(body.length, 4096), StandardCharsets.ISO_8859_1);
        Matcher m = META_CHARSET.matcher(head);
        if (m.find()) return m.group(1);
        m = CSS_CHARSET.matcher(head);
        if (m.find()) return m.group(1);
        m = XML_ENCODING.matcher(head);
        if (m.find()) return m.group(1);
        return null;
    }

    /** 按 WHATWG 编码标签习惯映射到更宽的超集编码 */
    private static Charset lookup(String label) {
        String l = label.trim().toLowerCase(Locale.ROOT);
        switch (l) {
            case "gb2312": case "gbk": case "x-gbk": case "gb_2312-80": case "cp936":
            case "windows-936": case "euc-cn": case "chinese": case "csgb2312": case "gb18030":
                return charset("GB18030");
            case "big5": case "big5-hkscs": case "cn-big5": case "x-x-big5": case "csbig5":
                Charset hk = charset("Big5-HKSCS");
                return hk != null ? hk : charset("Big5");
            case "iso-8859-1": case "latin1": case "l1": case "us-ascii": case "ascii":
            case "iso8859-1": case "cp1252": case "windows-1252":
                return charset("windows-1252");
            case "shift_jis": case "sjis": case "x-sjis": case "ms_kanji": case "windows-31j": case "csshiftjis":
                Charset ms = charset("windows-31j");
                return ms != null ? ms : charset("Shift_JIS");
            case "utf8": case "utf-8": case "unicode-1-1-utf-8":
                return StandardCharsets.UTF_8;
            default:
                return charset(l);
        }
    }

    private static Charset charset(String name) {
        try {
            return Charset.forName(name);
        } catch (Exception e) {
            return null;
        }
    }

    private static String decodeStrict(byte[] body, Charset cs) {
        if (cs == null) return null;
        try {
            return cs.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}
