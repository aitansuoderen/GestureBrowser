# 网页 JS 调用的原生接口不能被混淆或删掉
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
-keep class com.example.gesturebrowser.** { *; }
