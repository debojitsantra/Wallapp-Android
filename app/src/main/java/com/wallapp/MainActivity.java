package com.wallapp;

import android.Manifest;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

public class MainActivity extends AppCompatActivity {

    private static final String APP_URL = "https://debwallapp.pages.dev/";
    private static final int STORAGE_PERMISSION_CODE = 100;

    private WebView webView;
    private ProgressBar progressBar;
    private SwipeRefreshLayout swipeRefreshLayout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        progressBar = findViewById(R.id.progressBar);
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);

        if (!isNetworkAvailable()) {
            showNoInternetDialog();
            return;
        }

        setupWebView();
        setupSwipeRefresh();

        webView.loadUrl(APP_URL);

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            checkStoragePermission();
        }
    }

    /* -------------------- WebView -------------------- */

    private void setupWebView() {
        WebSettings s = webView.getSettings();

        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);

        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        s.setUserAgentString(s.getUserAgentString() + " WallApp/1.0");

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cm.setAcceptThirdPartyCookies(webView, true);
        }

        // Add JavaScript interface for native downloads
        webView.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void downloadImage(String url, String filename) {
                runOnUiThread(() -> downloadFile(url, filename, "image/jpeg"));
            }
        }, "AndroidDownloader");

        webView.setWebViewClient(new WebViewClient() {

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                progressBar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
                swipeRefreshLayout.setRefreshing(false);
                
                // Inject download handler script
                injectDownloadHandler();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                
                // Allow same-domain navigation
                if (url.startsWith(APP_URL)) {
                    return false;
                }
                
                // Allow Cloudinary URLs (for images)
                if (url.contains("res.cloudinary.com")) {
                    return false;
                }
                
                // Open other external links in browser
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int progress) {
                progressBar.setProgress(progress);
            }
        });

        webView.setDownloadListener((DownloadListener)
                (url, userAgent, contentDisposition, mimetype, contentLength) ->
                        downloadFile(url, contentDisposition, mimetype));
    }

    /* -------------------- Swipe Refresh -------------------- */

    private void setupSwipeRefresh() {
        swipeRefreshLayout.setOnRefreshListener(() -> webView.reload());
    }

    /* -------------------- Download Handler Injection -------------------- */

    private void injectDownloadHandler() {
        String script = 
            "(function() {" +
            "    if (window.downloadHandlerInjected) return;" +
            "    window.downloadHandlerInjected = true;" +
            "    " +
            "    // Override window.open for download fallback" +
            "    const originalOpen = window.open;" +
            "    window.open = function(url, target) {" +
            "        if (url && url.includes('res.cloudinary.com')) {" +
            "            const filename = 'wallpaper_' + Date.now() + '.jpg';" +
            "            if (typeof AndroidDownloader !== 'undefined') {" +
            "                AndroidDownloader.downloadImage(url, filename);" +
            "                return null;" +
            "            }" +
            "        }" +
            "        return originalOpen.call(window, url, target);" +
            "    };" +
            "    " +
            "    // Intercept download link clicks" +
            "    document.addEventListener('click', function(e) {" +
            "        let target = e.target;" +
            "        // Find the nearest link element" +
            "        while (target && target.tagName !== 'A') {" +
            "            target = target.parentElement;" +
            "        }" +
            "        " +
            "        if (target && target.tagName === 'A') {" +
            "            const href = target.href;" +
            "            const hasDownload = target.hasAttribute('download');" +
            "            " +
            "            if (hasDownload && href && href.includes('res.cloudinary.com')) {" +
            "                e.preventDefault();" +
            "                e.stopPropagation();" +
            "                " +
            "                const filename = target.download || 'wallpaper_' + Date.now() + '.jpg';" +
            "                if (typeof AndroidDownloader !== 'undefined') {" +
            "                    AndroidDownloader.downloadImage(href, filename);" +
            "                }" +
            "                return false;" +
            "            }" +
            "        }" +
            "    }, true);" +
            "})();";
        
        webView.evaluateJavascript(script, null);
    }

    /* -------------------- Download -------------------- */

    private void downloadFile(String url, String contentDisposition, String mimetype) {

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2 &&
                ContextCompat.checkSelfPermission(this,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    STORAGE_PERMISSION_CODE
            );
            return;
        }

        try {
            DownloadManager.Request request =
                    new DownloadManager.Request(Uri.parse(url));

            request.setMimeType(mimetype);
            request.setTitle(URLUtil.guessFileName(url, contentDisposition, mimetype));
            request.setDescription("Downloading...");
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

            String cookies = CookieManager.getInstance().getCookie(url);
            request.addRequestHeader("Cookie", cookies);
            request.addRequestHeader("User-Agent",
                    webView.getSettings().getUserAgentString());

            request.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    URLUtil.guessFileName(url, contentDisposition, mimetype)
            );

            DownloadManager dm =
                    (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            dm.enqueue(request);

            Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            Toast.makeText(this,
                    "Download failed: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    /* -------------------- Network -------------------- */

    private boolean isNetworkAvailable() {
        ConnectivityManager cm =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        if (cm == null) return false;

        Network network = cm.getActiveNetwork();
        if (network == null) return false;

        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        return caps != null &&
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
    }

    private void showNoInternetDialog() {
        new AlertDialog.Builder(this)
                .setTitle("No Internet")
                .setMessage("Check your connection and try again.")
                .setCancelable(false)
                .setPositiveButton("Retry", (d, w) -> recreate())
                .setNegativeButton("Exit", (d, w) -> finish())
                .show();
    }

    /* -------------------- Permissions -------------------- */

    private void checkStoragePermission() {
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    STORAGE_PERMISSION_CODE
            );
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults) {

        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == STORAGE_PERMISSION_CODE &&
                grantResults.length > 0 &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {

            Toast.makeText(this,
                    "Permission granted",
                    Toast.LENGTH_SHORT).show();
        }
    }

    /* -------------------- Lifecycle -------------------- */

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.loadUrl("about:blank");
            webView.clearHistory();
            webView.removeAllViews();
            webView.destroy();
        }
        super.onDestroy();
    }
}