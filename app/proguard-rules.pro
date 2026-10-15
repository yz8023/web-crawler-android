# 网页通过 window.Android 调用的 JS 桥方法必须保留原名
-keepclassmembers class com.webcrawler.tool.MainActivity$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}

# 进一步压缩
-repackageclasses
-allowaccessmodification
