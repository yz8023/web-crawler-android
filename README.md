# 网页爬虫（Android 版）

把 [91cnm/web-crawler](https://github.com/91cnm/web-crawler)（PHP 全站网页爬虫）移植成 **原生安卓 App**：
在手机上直接爬取整站文件，打包成 `.zip` 保存到手机或分享，不需要电脑、服务器或 PHP 环境。

- **体积约 52 KB**：纯 Java 实现，没有任何第三方依赖（无 AndroidX / Kotlin），R8 压缩 + 资源压缩
- **支持 Android 5.0 及以上**（minSdk 21，targetSdk 36）
- 界面沿用原项目 `index.html` 的设计（WebView 加载本地页面）

📦 **下载 APK**：前往 [Releases](https://github.com/yz8023/web-crawler-android/releases/latest) 下载最新版本

## 功能

与 PHP 版 `crawl.php` 逐函数对应：

- BFS 爬取同站全部文件（含子域名），HTML / CSS / JS **二次深层检索**（a、link、img、script、srcset、`<style>`、
  CSS `url()` / `@import`、JS 中的静态资源字符串、meta refresh、SVG `xlink:href` 等）
- 跳过 `.php/.asp/.aspx/.jsp/.cgi/.pl/.py/.rb` 动态脚本和含 `logout/signin/signup/register` 的地址
- 按 URL 目录结构保存到 `域名/...`，同名文件自动加 `_2`、`_3`，带参数的 URL 加 `md5(参数)` 前 6 位后缀
- 文本统一转为 UTF-8（兼容 GBK / GB2312 / BIG5 等，并同步修改页面里声明的编码）
- 生成 `所有下载文件列表.txt`、`下载统计.txt`（格式与 PHP 版完全一致）
- 打包为 `域名.zip`（含第一级域名文件夹），打包后自动删除文件夹，仅保留压缩包
- 失败自动重试，失败记录显示在结果页「下载失败记录」中

App 额外提供：

- 实时进度（已下载 / 失败 / 待处理 / 已用时 / 当前地址），可随时 **停止**（已下载的文件仍会打包）
- **后台继续爬取**：前台服务 + 通知栏进度，可从通知栏直接停止；完成后通知提醒
- 「高级设置」：最多下载文件数、最长运行时间、请求超时、单个网址最多请求次数
- 结果可 **保存到 `Download/网页爬虫/`** 或通过微信、QQ、网盘等 **分享**
- 在浏览器里「分享」网址到本 App，自动填入网址
- 手机剩余空间低于 200 MB 时自动停止，避免存储被占满

## 与 PHP 版的差异

| 项目 | PHP 版 | Android 版 |
| --- | --- | --- |
| 默认限制 | 超时 10 秒、最多 200 个文件、最长 50 秒（README 说明本地为 20 秒 / 不限 / 不限） | 超时 20 秒、不限文件数、不限时间（与原 README 的本地配置一致），可在「高级设置」修改 |
| 重定向 | 按请求地址保存，同一页面可能重复保存 | 以跳转后的最终地址保存并去重，相对链接按最终地址解析；起始地址跳到其他域名（如 `example.com` → `www.example.com`）时以新域名为准 |
| 子目录 `index.html` | 被保存为根目录的 `index_2.html` | 保留在所在目录，如 `sub/index.html` |
| 路径安全 | `../` 可写到 `sites/` 目录之外（路径穿越） | 过滤 `.`/`..` 段，并校验最终路径必须在工作目录内 |
| 文件与目录同名 | 写入失败但仍计入文件列表 | 目录自动改名为 `xxx_dir` |
| 没写协议的网址 | 自动加 `https://`，网站不支持 https 时整站失败 | 自动加 `https://`，连接失败时自动改用 `http://` 重试 |
| 起始网址打不开 | 仍返回成功，压缩包里只有两个记录文件 | 直接提示失败原因（如「域名解析失败」） |
| 关键词过滤 | 检查整个网址，域名含 `register` 等词的网站完全爬不了 | 只检查路径和参数；起始网址不过滤 |
| 4xx 错误 | 也重试 3 次 | 4xx（408/429 除外）不重试，节省时间 |
| 文件名 | 区分大小写 | 不区分大小写去重（zip 在 Windows 上解压不冲突），超长文件名截断并加哈希 |
| 超大文件 | 全部读入内存 | 超过 8 MB 直接写入文件，不占内存 |

## 编译

环境：JDK 17、Android SDK（platform 36、build-tools 36），Gradle 由 wrapper 自动下载（8.14.3，AGP 8.13.2）。

```bash
# 1. 指定 SDK 位置（或设置 ANDROID_HOME 环境变量）
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# 2. 编译 release 版
./gradlew assembleRelease        # Windows: gradlew.bat assembleRelease

# 输出：app/build/outputs/apk/release/app-release.apk
```

### 签名

release 包的签名信息从项目根目录的 `keystore.properties` 读取（文件不存在时生成未签名的 APK）：

```properties
storeFile=keystore/release.jks
storePassword=你的密码
keyAlias=webcrawler
keyPassword=你的密码
```

> ⚠️ 以后发布新版本必须使用 **同一个签名文件**，否则手机上无法覆盖安装。请备份好 `keystore/release.jks` 和密码，
> 不要上传到公开仓库（已写入 `.gitignore`）。

## 项目结构

```
app/src/main/
├── assets/index.html                  界面（沿用原项目设计，ES5，兼容旧版 WebView）
├── java/com/webcrawler/tool/
│   ├── MainActivity.java              WebView 界面 + JS 桥 + 保存 / 分享
│   ├── CrawlManager.java              任务管理（进度、结果持久化、通知、唤醒锁）
│   ├── CrawlService.java              前台服务（后台保活 + 通知栏进度）
│   ├── ShareProvider.java             分享 zip 用的极简 ContentProvider
│   └── core/                          爬虫核心（纯 Java，可在电脑上直接测试）
│       ├── Crawler.java               对应 crawl.php 主流程
│       ├── LinkExtractor.java         HTML / CSS / JS 链接提取
│       ├── UrlUtil.java               URL 解析、拼接（RFC 3986）、同站判断
│       ├── TextCodec.java             编码检测与 UTF-8 转换
│       └── ZipUtil.java               打包 / 删除
└── res/                               主题、图标（矢量图）
tools/CrawlerCli.java                  在电脑上测试爬虫核心的命令行工具
```

在电脑上测试爬虫核心：

```bash
javac -encoding UTF-8 -d out app/src/main/java/com/webcrawler/tool/core/*.java tools/CrawlerCli.java
java -cp out CrawlerCli example.com ./test-output
```

## 权限说明

| 权限 | 用途 |
| --- | --- |
| `INTERNET` | 爬取网页 |
| `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_DATA_SYNC` | 切到后台后继续爬取 |
| `POST_NOTIFICATIONS` | 通知栏显示进度 / 完成提醒（Android 13+ 首次爬取时询问，可拒绝） |
| `WAKE_LOCK` | 锁屏后继续爬取 |
| `WRITE_EXTERNAL_STORAGE` | 仅 Android 9 及以下保存到「下载」目录时需要；Android 10+ 无需任何存储权限 |

> 与 PHP 版一致，爬取时不校验 HTTPS 证书（以便抓取证书过期 / 自签名的网站）。请只爬取你有权下载的网站内容。
