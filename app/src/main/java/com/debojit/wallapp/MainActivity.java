package com.debojit.wallapp;

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

    // Pending download info, stored when permission is needed mid-download
    private String pendingDownloadUrl;
    private String pendingDownloadContent;
    private String pendingDownloadMime;

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

        // FIX: Restore WebView state across rotations instead of reloading
        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(APP_URL);
        }

        // FIX: Only request storage permission on Android <= 12 (API 32 / S_V2)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            checkStoragePermission();
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        // FIX: Save WebView state so back/rotation don't reload from scratch
        if (webView != null) {
            webView.saveState(outState);
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

        // FIX: MIXED_CONTENT_ALWAYS_ALLOW is available from API 21+ (our minSdk),
        // so the redundant SDK version guard is removed.
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        s.setUserAgentString(s.getUserAgentString() + " WallApp/1.0");

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        // FIX: setAcceptThirdPartyCookies is available from API 21+, guard removed.
        cm.setAcceptThirdPartyCookies(webView, true);

        // Native download bridge exposed to JavaScript
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
                injectDownloadHandler();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();

                // Allow same-domain navigation
                if (url.startsWith(APP_URL)) {
                    return false;
                }

                // Allow Cloudinary image URLs within the WebView
                if (url.contains("res.cloudinary.com")) {
                    return false;
                }

                // FIX: Wrap external intent in try/catch to handle cases where
                // no browser app is installed, preventing an ActivityNotFoundException crash.
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this,
                            "No app found to open this link", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int progress) {
                progressBar.setProgress(progress);
                // FIX: Keep the bar visible while loading; gone only at 100%
                progressBar.setVisibility(progress < 100 ? View.VISIBLE : View.GONE);
            }
        });

        // FIX: DownloadListener cast was redundant/confusing; lambda is sufficient.
        webView.setDownloadListener(
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
            "    document.addEventListener('click', function(e) {" +
            "        let target = e.target;" +
            "        while (target && target.tagName !== 'A') {" +
            "            target = target.parentElement;" +
            "        }" +
            "        if (target && target.tagName === 'A') {" +
            "            const href = target.href;" +
            "            const hasDownload = target.hasAttribute('download');" +
            "            if (hasDownload && href && href.includes('res.cloudinary.com')) {" +
            "                e.preventDefault();" +
            "                e.stopPropagation();" +
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

            // FIX: Save download details so they can be retried after permission grant
            pendingDownloadUrl = url;
            pendingDownloadContent = contentDisposition;
            pendingDownloadMime = mimetype;

            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    STORAGE_PERMISSION_CODE
            );
            return;
        }

        try {
            String filename = URLUtil.guessFileName(url, contentDisposition, mimetype);

            // FIX: Ensure the filename has an extension so the file is usable
            if (!filename.contains(".")) {
                String ext = mimetype != null && mimetype.contains("png") ? ".png" : ".jpg";
                filename = filename + ext;
            }

            DownloadManager.Request request =
                    new DownloadManager.Request(Uri.parse(url));

            request.setMimeType(mimetype);
            request.setTitle(filename);
            request.setDescription("Downloading wallpaper…");
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null) {
                request.addRequestHeader("Cookie", cookies);
            }
            request.addRequestHeader("User-Agent",
                    webView.getSettings().getUserAgentString());

            request.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_PICTURES, filename);

            // FIX: Use Environment.DIRECTORY_PICTURES instead of DOWNLOADS so
            // wallpapers appear in the gallery immediately.

            DownloadManager dm =
                    (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (dm != null) {
                dm.enqueue(request);
                Toast.makeText(this, getString(R.string.download_started),
                        Toast.LENGTH_SHORT).show();
            }

        } catch (Exception e) {
            Toast.makeText(this,
                    getString(R.string.download_failed) + ": " + e.getMessage(),
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
        // FIX: Also allow ETHERNET transport (e.g. Chromebooks, USB tethering)
        return caps != null &&
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                        || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
    }

    private void showNoInternetDialog() {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.no_internet))
                .setMessage("Check your connection and try again.")
                .setCancelable(false)
                .setPositiveButton(getString(R.string.retry), (d, w) -> recreate())
                .setNegativeButton(getString(R.string.exit), (d, w) -> finish())
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

        if (requestCode == STORAGE_PERMISSION_CODE) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {

                // FIX: Retry any download that was blocked waiting for permission
                if (pendingDownloadUrl != null) {
                    downloadFile(pendingDownloadUrl, pendingDownloadContent, pendingDownloadMime);
                    pendingDownloadUrl = null;
                    pendingDownloadContent = null;
                    pendingDownloadMime = null;
                }
            } else {
                Toast.makeText(this,
                        getString(R.string.permission_required),
                        Toast.LENGTH_LONG).show();
            }
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
    protected void onPause() {
        super.onPause();
        // FIX: Pause WebView timers to save battery when the app is backgrounded
        webView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.loadUrl("about:blank");
            webView.clearHistory();
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
