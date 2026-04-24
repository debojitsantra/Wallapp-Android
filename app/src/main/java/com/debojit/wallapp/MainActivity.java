/*
 * WallApp - A lightweight WebView wrapper for the TheWallApp wallpaper website.
 * Copyright (C) 2026 Debojit Santra
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.debojit.wallapp;

import android.Manifest;
import android.app.DownloadManager;
import android.app.WallpaperManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends AppCompatActivity {

    private static final String APP_URL = "https://thewallapp.pages.dev/";
    private static final String OFFLINE_URL = "file:///android_asset/offline.html";

    private String pendingDownloadUrl;
    private String pendingDownloadContent;
    private String pendingDownloadMime;

    private WebView webView;
    private ProgressBar progressBar;
    private SwipeRefreshLayout swipeRefreshLayout;

    private ActivityResultLauncher<String> requestPermissionLauncher;
    private ActivityResultLauncher<Intent> cropActivityResultLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        webView = findViewById(R.id.webView);
        progressBar = findViewById(R.id.progressBar);
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);

        swipeRefreshLayout.setEnabled(false);

        ViewCompat.setOnApplyWindowInsetsListener(swipeRefreshLayout, (v, insets) -> {
            int top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            int bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            v.setPadding(0, top, 0, bottom);
            return insets;
        });

        initActivityLaunchers();
        setupWebView();
        setupSwipeRefresh();
        setupBackPressed();

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            if (isNetworkAvailable()) {
                webView.loadUrl(APP_URL);
            } else {
                webView.loadUrl(OFFLINE_URL);
            }
        }

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            checkStoragePermission();
        }
    }

    private void initActivityLaunchers() {
        requestPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                    if (isGranted) {
                        if (pendingDownloadUrl != null) {
                            downloadFile(pendingDownloadUrl, pendingDownloadContent, pendingDownloadMime);
                            pendingDownloadUrl = null;
                        }
                    } else {
                        Toast.makeText(this, getString(R.string.permission_required), Toast.LENGTH_LONG).show();
                    }
                }
        );

        cropActivityResultLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK) {
                        Toast.makeText(this, "Wallpaper set!", Toast.LENGTH_SHORT).show();
                    }
                }
        );
    }

    private void setupBackPressed() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) webView.saveState(outState);
    }

    /* -------------------- WebView -------------------- */

    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setAllowFileAccess(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setUserAgentString(s.getUserAgentString() + " WallApp/1.1");

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void downloadImage(String url, String filename) {
                runOnUiThread(() -> downloadFile(url, filename, "image/jpeg"));
            }

            @android.webkit.JavascriptInterface
            public void shareImage(String url, String filename) {
                runOnUiThread(() -> shareImageFromUrl(url, filename));
            }

            @android.webkit.JavascriptInterface
            public void setWallpaper(String url) {
                runOnUiThread(() -> showSetWallpaperDialog(url));
            }

            @android.webkit.JavascriptInterface
            public boolean isAndroid() { return true; }
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
                injectBridge();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith(APP_URL) || url.contains("backblazeb2.com") || url.startsWith("file:///android_asset/")) return false;
                
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "No app found to open this link", Toast.LENGTH_SHORT).show();
                }
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
                if (!isNetworkAvailable()) {
                    view.loadUrl(OFFLINE_URL);
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int progress) {
                progressBar.setProgress(progress);
                progressBar.setVisibility(progress < 100 ? View.VISIBLE : View.GONE);
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) ->
                downloadFile(url, contentDisposition, mimetype));
    }

    private void setupSwipeRefresh() {
        swipeRefreshLayout.setOnRefreshListener(() -> webView.reload());
    }

    private void injectBridge() {
        String script = "(function() {" +
                "  if (window._wallAppBridgeInjected) return;" +
                "  window._wallAppBridgeInjected = true;" +
                "  window.nativeDownload = function(url, name) {" +
                "    AndroidDownloader.downloadImage(url, name || 'wallpaper.jpg');" +
                "  };" +
                "  window.nativeShare = function(url, name) {" +
                "    AndroidDownloader.shareImage(url, name || 'wallpaper.jpg');" +
                "  };" +
                "  window.nativeSetWallpaper = function(url) {" +
                "    AndroidDownloader.setWallpaper(url);" +
                "  };" +
                "  document.addEventListener('click', function(e) {" +
                "    let t = e.target;" +
                "    while (t && t.tagName !== 'A') t = t.parentElement;" +
                "    if (t && t.tagName === 'A') {" +
                "      const href = t.href;" +
                "      if (t.hasAttribute('download') && href && (href.includes('backblazeb2.com') || href.includes('res.cloudinary.com'))) {" +
                "        e.preventDefault(); e.stopPropagation();" +
                "        AndroidDownloader.downloadImage(href, t.download || 'wallpaper_' + Date.now() + '.jpg');" +
                "      }" +
                "    }" +
                "  }, true);" +
                "  const _origOpen = window.open;" +
                "  window.open = function(url, target) {" +
                "    if (url && (url.includes('backblazeb2.com') || url.includes('res.cloudinary.com'))) {" +
                "      AndroidDownloader.downloadImage(url, 'wallpaper_' + Date.now() + '.jpg');" +
                "      return null;" +
                "    }" +
                "    return _origOpen.call(window, url, target);" +
                "  };" +
                "  document.querySelectorAll('[data-download-url]').forEach(function(el) {" +
                "    el.addEventListener('click', function(e) { e.stopPropagation();" +
                "      AndroidDownloader.downloadImage(el.dataset.downloadUrl, el.dataset.downloadName || 'wallpaper.jpg');" +
                "    });" +
                "  });" +
                "  document.querySelectorAll('[data-share-url]').forEach(function(el) {" +
                "    el.addEventListener('click', function(e) { e.stopPropagation();" +
                "      AndroidDownloader.shareImage(el.dataset.shareUrl, el.dataset.shareName || 'wallpaper.jpg');" +
                "    });" +
                "  });" +
                "  document.querySelectorAll('[data-setwallpaper-url]').forEach(function(el) {" +
                "    el.addEventListener('click', function(e) { e.stopPropagation();" +
                "      AndroidDownloader.setWallpaper(el.dataset.setwallpaperUrl);" +
                "    });" +
                "  });" +
                "})();";
        webView.evaluateJavascript(script, null);
    }

    private void downloadFile(String url, String contentDisposition, String mimetype) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
            pendingDownloadUrl = url;
            pendingDownloadContent = contentDisposition;
            pendingDownloadMime = mimetype;
            requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            return;
        }

        try {
            String filename = URLUtil.guessFileName(url, contentDisposition, mimetype);
            if (!filename.contains("."))
                filename += (mimetype != null && mimetype.contains("png")) ? ".png" : ".jpg";

            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setMimeType(mimetype);
            request.setTitle(filename);
            request.setDescription("Downloading wallpaper…");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null) request.addRequestHeader("Cookie", cookies);
            request.addRequestHeader("User-Agent", webView.getSettings().getUserAgentString());
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_PICTURES, filename);

            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (dm != null) {
                dm.enqueue(request);
                Toast.makeText(this, getString(R.string.download_started), Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.download_failed) + ": " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void shareImageFromUrl(String url, String filename) {
        Toast.makeText(this, "Preparing share…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                File cacheDir = new File(getCacheDir(), "wallpapers");
                if (!cacheDir.exists()) cacheDir.mkdirs();
                String safeName = filename.replaceAll("[^a-zA-Z0-9._\\-]", "_");
                if (!safeName.matches(".*\\.(jpg|jpeg|png|webp)$")) safeName += ".jpg";
                File outFile = new File(cacheDir, safeName);

                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.connect();

                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(outFile)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) != -1) out.write(buf, 0, len);
                }

                runOnUiThread(() -> {
                    try {
                        Uri uri = FileProvider.getUriForFile(this,
                                getPackageName() + ".fileprovider", outFile);
                        Intent intent = new Intent(Intent.ACTION_SEND);
                        intent.setType("image/*");
                        intent.putExtra(Intent.EXTRA_STREAM, uri);
                        intent.putExtra(Intent.EXTRA_TEXT, "Wallpaper from WallApp");
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(intent, "Share wallpaper"));
                    } catch (Exception ex) {
                        Toast.makeText(this, "Share failed: " + ex.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Share failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void showSetWallpaperDialog(String url) {
        new AlertDialog.Builder(this)
                .setTitle("Set as wallpaper")
                .setItems(new String[]{"Home screen", "Lock screen", "Both"}, (dialog, which) -> {
                    int flag;
                    switch (which) {
                        case 0: flag = WallpaperManager.FLAG_SYSTEM; break;
                        case 1: flag = WallpaperManager.FLAG_LOCK; break;
                        default: flag = WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK;
                    }
                    applyWallpaper(url, flag);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private int pendingWallpaperFlags = WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK;

    private void applyWallpaper(String url, int flags) {
        pendingWallpaperFlags = flags;
        Toast.makeText(this, "Downloading image…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                File cacheDir = new File(getCacheDir(), "wallpapers");
                if (!cacheDir.exists()) cacheDir.mkdirs();
                File tmpFile = new File(cacheDir, "setwallpaper_tmp.jpg");

                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.connect();

                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(tmpFile)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) != -1) out.write(buf, 0, len);
                }
                runOnUiThread(() -> launchCropIntent(tmpFile));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Failed to download: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void launchCropIntent(File imageFile) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", imageFile);
            Intent cropIntent = new Intent("android.service.wallpaper.CROP_AND_SET_WALLPAPER");
            cropIntent.setDataAndType(uri, "image/*");
            cropIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            if (cropIntent.resolveActivity(getPackageManager()) != null) {
                cropActivityResultLauncher.launch(cropIntent);
                return;
            }

            Intent fallback = new Intent(Intent.ACTION_ATTACH_DATA);
            fallback.setDataAndType(uri, "image/*");
            fallback.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            fallback.putExtra("mimeType", "image/*");
            cropActivityResultLauncher.launch(Intent.createChooser(fallback, "Set as wallpaper"));
        } catch (Exception e) {
            Toast.makeText(this, "Setting wallpaper…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                try {
                    Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", imageFile);
                    InputStream in = getContentResolver().openInputStream(uri);
                    Bitmap bmp = BitmapFactory.decodeStream(in);
                    WallpaperManager.getInstance(this).setBitmap(bmp, null, true, pendingWallpaperFlags);
                    runOnUiThread(() -> Toast.makeText(this, "Wallpaper set!", Toast.LENGTH_SHORT).show());
                } catch (Exception ex) {
                    runOnUiThread(() -> Toast.makeText(this, "Failed: " + ex.getMessage(), Toast.LENGTH_LONG).show());
                }
            }).start();
        }
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Network network = cm.getActiveNetwork();
        if (network == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        return caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
    }

    private void checkStoragePermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
    }

    @Override protected void onPause() { super.onPause(); webView.onPause(); }
    @Override protected void onResume() {
        super.onResume();
        webView.onResume();
        if (OFFLINE_URL.equals(webView.getUrl()) && isNetworkAvailable()) {
            webView.loadUrl(APP_URL);
        }
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
