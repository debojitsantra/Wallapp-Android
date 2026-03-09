# Add project specific ProGuard rules here.

# Keep JavaScript interface methods
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep WebView-related classes
-keepclassmembers class * extends android.webkit.WebViewClient {
    public *;
}
