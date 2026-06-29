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
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
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
import android.widget.TextView;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String APP_URL = "https://thewallapp.pages.dev/";
    private static final String OFFLINE_URL = "file:///android_asset/offline.html";
    private static final String WALLAPP_DOWNLOAD_DIR = "WallApp";
    private static final long EXIT_BACK_PRESS_INTERVAL_MS = 2000;

    private String pendingDownloadUrl;
    private String pendingDownloadContent;
    private String pendingDownloadMime;

    private WebView webView;
    private ProgressBar progressBar;
    private SwipeRefreshLayout swipeRefreshLayout;
    private long lastBackPressedAt;
    private Typeface instrumentSerifTypeface;

    private ActivityResultLauncher<String> requestPermissionLauncher;
    private ActivityResultLauncher<Intent> cropActivityResultLauncher;

    private interface WallpaperFileCallback {
        void onReady(File imageFile);
    }

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
            String initialUrl = getInitialUrl(getIntent());
            if (isNetworkAvailable()) {
                webView.loadUrl(initialUrl);
            } else {
                webView.loadUrl(OFFLINE_URL);
            }
        }

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            checkStoragePermission();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String url = getInitialUrl(intent);
        if (webView != null && isNetworkAvailable()) {
            webView.loadUrl(url);
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
                    long now = System.currentTimeMillis();
                    if (now - lastBackPressedAt < EXIT_BACK_PRESS_INTERVAL_MS) {
                        setEnabled(false);
                        getOnBackPressedDispatcher().onBackPressed();
                    } else {
                        lastBackPressedAt = now;
                        Toast.makeText(MainActivity.this, R.string.press_back_again_to_exit, Toast.LENGTH_SHORT).show();
                    }
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
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setAllowContentAccess(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }
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
                runOnUiThread(() -> showSetWallpaperDialogWithCurrentWebTheme(url));
            }

            @android.webkit.JavascriptInterface
            public void setWallpaperTheme(String url, boolean isDarkMode) {
                runOnUiThread(() -> showSetWallpaperDialog(url, isDarkMode));
            }

            @android.webkit.JavascriptInterface
            public void sharePage(String url, String title) {
                runOnUiThread(() -> sharePageLink(url, title));
            }

            @android.webkit.JavascriptInterface
            public void openExternal(String url) {
                runOnUiThread(() -> openExternalUrl(url));
            }

            @android.webkit.JavascriptInterface
            public void copyLink(String url) {
                runOnUiThread(() -> copyLinkToClipboard(url));
            }

            @android.webkit.JavascriptInterface
            public void retryOnline() {
                runOnUiThread(() -> loadOnlineHomeOrToast());
            }

            @android.webkit.JavascriptInterface
            public void showAppActions() {
                runOnUiThread(() -> showAppActionsDialog());
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
                if (isTrustedInternalUrl(url)) return false;
                
                openExternalUrl(url);
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
                if (request.isForMainFrame() && !isNetworkAvailable()) {
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
                "    AndroidDownloader.downloadImage(url, name || '');" +
                "  };" +
                "  window.nativeShare = function(url, name) {" +
                "    AndroidDownloader.shareImage(url, name || 'wallpaper.jpg');" +
                "  };" +
                "  function wallAppIsDarkMode() {" +
                "    return !!((document.querySelector('.app') && document.querySelector('.app').classList.contains('dark-mode')) || document.body.classList.contains('dark-mode'));" +
                "  }" +
                "  window.nativeSetWallpaper = function(url) {" +
                "    AndroidDownloader.setWallpaperTheme(url, wallAppIsDarkMode());" +
                "  };" +
                "  window.nativeSharePage = function(url, title) {" +
                "    AndroidDownloader.sharePage(url || location.href, title || document.title || 'WallApp');" +
                "  };" +
                "  window.nativeOpenExternal = function(url) {" +
                "    AndroidDownloader.openExternal(url || location.href);" +
                "  };" +
                "  window.nativeCopyLink = function(url) {" +
                "    AndroidDownloader.copyLink(url || location.href);" +
                "  };" +
                "  window.nativeShowAppActions = function() {" +
                "    AndroidDownloader.showAppActions();" +
                "  };" +
                "  document.addEventListener('click', function(e) {" +
                "    let t = e.target;" +
                "    while (t && t.tagName !== 'A') t = t.parentElement;" +
                "    if (t && t.tagName === 'A') {" +
                "      const href = t.href;" +
                "      if (t.hasAttribute('download') && href && (href.includes('backblazeb2.com') || href.includes('res.cloudinary.com'))) {" +
                "        e.preventDefault(); e.stopPropagation();" +
                "        AndroidDownloader.downloadImage(href, t.download || '');" +
                "      }" +
                "    }" +
                "  }, true);" +
                "  const _origOpen = window.open;" +
                "  window.open = function(url, target) {" +
                "    if (url && (url.includes('backblazeb2.com') || url.includes('res.cloudinary.com'))) {" +
                "      AndroidDownloader.downloadImage(url, '');" +
                "      return null;" +
                "    }" +
                "    return _origOpen.call(window, url, target);" +
                "  };" +
                "  document.querySelectorAll('[data-download-url]').forEach(function(el) {" +
                "    el.addEventListener('click', function(e) { e.stopPropagation();" +
                "      AndroidDownloader.downloadImage(el.dataset.downloadUrl, el.dataset.downloadName || '');" +
                "    });" +
                "  });" +
                "  document.querySelectorAll('[data-share-url]').forEach(function(el) {" +
                "    el.addEventListener('click', function(e) { e.stopPropagation();" +
                "      AndroidDownloader.shareImage(el.dataset.shareUrl, el.dataset.shareName || 'wallpaper.jpg');" +
                "    });" +
                "  });" +
                "  document.querySelectorAll('[data-setwallpaper-url]').forEach(function(el) {" +
                "    el.addEventListener('click', function(e) { e.stopPropagation();" +
                "      AndroidDownloader.setWallpaperTheme(el.dataset.setwallpaperUrl, wallAppIsDarkMode());" +
                "    });" +
                "  });" +
                "  document.querySelectorAll('[data-share-page-url]').forEach(function(el) {" +
                "    el.addEventListener('click', function(e) { e.stopPropagation();" +
                "      AndroidDownloader.sharePage(el.dataset.sharePageUrl || location.href, el.dataset.shareTitle || document.title || 'WallApp');" +
                "    });" +
                "  });" +
                "  document.querySelectorAll('[data-native-actions]').forEach(function(el) {" +
                "    el.addEventListener('click', function(e) { e.stopPropagation();" +
                "      AndroidDownloader.showAppActions();" +
                "    });" +
                "  });" +
                "})();";
        webView.evaluateJavascript(script, null);
    }

    private void downloadFile(String url, String contentDisposition, String mimetype) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
            pendingDownloadUrl = url;
            pendingDownloadContent = contentDisposition;
            pendingDownloadMime = mimetype;
            requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            return;
        }

        try {
            String filename = buildDownloadFileName(url, contentDisposition, mimetype);

            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setMimeType(mimetype);
            request.setTitle(filename);
            request.setDescription("Saving to Pictures/WallApp");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null) request.addRequestHeader("Cookie", cookies);
            request.addRequestHeader("User-Agent", webView.getSettings().getUserAgentString());
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_PICTURES,
                    WALLAPP_DOWNLOAD_DIR + File.separator + filename);

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

    private void showSetWallpaperDialog(String url, Boolean isDarkMode) {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        View content = getLayoutInflater().inflate(R.layout.bottom_sheet_set_wallpaper, null);
        applyWallpaperSheetFont(content);
        if (isDarkMode != null) {
            applyWallpaperSheetTheme(content, isDarkMode);
        }
        sheet.setContentView(content);

        content.findViewById(R.id.homeScreenButton).setOnClickListener(v -> {
            sheet.dismiss();
            applyWallpaperDirectly(url, WallpaperManager.FLAG_SYSTEM);
        });
        content.findViewById(R.id.lockScreenButton).setOnClickListener(v -> {
            sheet.dismiss();
            applyWallpaperDirectly(url, WallpaperManager.FLAG_LOCK);
        });
        content.findViewById(R.id.bothScreensButton).setOnClickListener(v -> {
            sheet.dismiss();
            applyWallpaperDirectly(url, WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK);
        });
        content.findViewById(R.id.setAsButton).setOnClickListener(v -> {
            sheet.dismiss();
            applyWallpaperWithSystemPicker(url);
        });

        sheet.setOnShowListener(dialog -> {
            if (sheet.getWindow() != null) {
                sheet.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            }
            View bottomSheet = sheet.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                bottomSheet.setBackgroundColor(Color.TRANSPARENT);
            }
        });
        sheet.show();
    }

    private void showSetWallpaperDialogWithCurrentWebTheme(String url) {
        String script = "(function() {" +
                "var app = document.querySelector('.app');" +
                "return !!((app && app.classList.contains('dark-mode')) || document.body.classList.contains('dark-mode'));" +
                "})();";
        webView.evaluateJavascript(script, value -> showSetWallpaperDialog(url, "true".equals(value)));
    }

    private void applyWallpaperSheetFont(View content) {
        Typeface typeface = getInstrumentSerifTypeface();
        if (typeface == null) return;

        TextView title = content.findViewById(R.id.wallpaperSheetTitle);
        title.setTypeface(typeface, Typeface.BOLD);
        setButtonTypeface(content.findViewById(R.id.homeScreenButton), typeface);
        setButtonTypeface(content.findViewById(R.id.lockScreenButton), typeface);
        setButtonTypeface(content.findViewById(R.id.bothScreensButton), typeface);
        setButtonTypeface(content.findViewById(R.id.setAsButton), typeface);
    }

    private void setButtonTypeface(MaterialButton button, Typeface typeface) {
        button.setTypeface(typeface, Typeface.NORMAL);
    }

    private Typeface getInstrumentSerifTypeface() {
        if (instrumentSerifTypeface == null) {
            try {
                instrumentSerifTypeface = Typeface.createFromAsset(
                        getAssets(), "fonts/InstrumentSerif-Regular.ttf");
            } catch (Exception ignored) {
                instrumentSerifTypeface = Typeface.SERIF;
            }
        }
        return instrumentSerifTypeface;
    }

    private void applyWallpaperSheetTheme(View content, boolean isDarkMode) {
        int bgPrimary = Color.parseColor(isDarkMode ? "#000000" : "#FFFFFF");
        int bgSecondary = Color.parseColor(isDarkMode ? "#1A1A1A" : "#F5F5F5");
        int textPrimary = Color.parseColor(isDarkMode ? "#FFFFFF" : "#000000");
        int border = Color.parseColor(isDarkMode ? "#333333" : "#E0E0E0");
        int handle = Color.parseColor(isDarkMode ? "#5A5A5A" : "#BDBDBD");

        content.setBackground(createRoundedTopDrawable(bgPrimary, border));
        View handleView = content.findViewById(R.id.wallpaperSheetHandle);
        if (handleView != null) {
            GradientDrawable handleDrawable = new GradientDrawable();
            handleDrawable.setColor(handle);
            handleDrawable.setCornerRadius(dp(3));
            handleView.setBackground(handleDrawable);
        }

        TextView title = content.findViewById(R.id.wallpaperSheetTitle);
        title.setTextColor(textPrimary);
        styleWallpaperSheetButton(content.findViewById(R.id.homeScreenButton), bgSecondary, textPrimary, border);
        styleWallpaperSheetButton(content.findViewById(R.id.lockScreenButton), bgSecondary, textPrimary, border);
        styleWallpaperSheetButton(content.findViewById(R.id.bothScreensButton), bgSecondary, textPrimary, border);
        styleWallpaperSheetButton(content.findViewById(R.id.setAsButton), bgSecondary, textPrimary, border);
    }

    private GradientDrawable createRoundedTopDrawable(int fill, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setStroke(dp(1), stroke);
        float radius = dp(24);
        drawable.setCornerRadii(new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
        return drawable;
    }

    private void styleWallpaperSheetButton(MaterialButton button, int background, int text, int border) {
        ColorStateList textColor = ColorStateList.valueOf(text);
        button.setBackgroundTintList(ColorStateList.valueOf(background));
        button.setTextColor(text);
        button.setIconTint(textColor);
        button.setStrokeColor(ColorStateList.valueOf(border));
        button.setStrokeWidth(dp(1));
        button.setCornerRadius(dp(14));
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int pendingWallpaperFlags = WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK;

    private void applyWallpaperDirectly(String url, int flags) {
        pendingWallpaperFlags = flags;
        downloadWallpaperFile(url, imageFile -> setWallpaperDirectly(imageFile, flags));
    }

    private void applyWallpaperWithSystemPicker(String url) {
        pendingWallpaperFlags = WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK;
        downloadWallpaperFile(url, this::launchCropIntent);
    }

    private void downloadWallpaperFile(String url, WallpaperFileCallback callback) {
        Toast.makeText(this, "Downloading image…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                File cacheDir = new File(getCacheDir(), "wallpapers");
                if (!cacheDir.exists()) cacheDir.mkdirs();
                File tmpFile = new File(cacheDir, "setwallpaper_tmp" + getImageExtensionFromUrl(url));

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
                runOnUiThread(() -> callback.onReady(tmpFile));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Failed to download: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void setWallpaperDirectly(File imageFile, int flags) {
        Toast.makeText(this, "Setting wallpaper…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try (InputStream in = getContentResolver().openInputStream(
                    FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", imageFile))) {
                Bitmap bmp = BitmapFactory.decodeStream(in);
                if (bmp == null) {
                    throw new IllegalStateException("Unable to decode wallpaper");
                }
                WallpaperManager wallpaperManager = WallpaperManager.getInstance(this);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    wallpaperManager.setBitmap(bmp, null, true, flags);
                } else {
                    wallpaperManager.setBitmap(bmp);
                }
                runOnUiThread(() -> Toast.makeText(this, "Wallpaper set!", Toast.LENGTH_SHORT).show());
            } catch (Exception ex) {
                runOnUiThread(() -> Toast.makeText(this, "Failed: " + ex.getMessage(), Toast.LENGTH_LONG).show());
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
            setWallpaperDirectly(imageFile, pendingWallpaperFlags);
        }
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        }
        Network network = cm.getActiveNetwork();
        if (network == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        return caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
    }

    private String getInitialUrl(Intent intent) {
        Uri data = intent != null ? intent.getData() : null;
        if (data != null && "thewallapp.pages.dev".equalsIgnoreCase(data.getHost())) {
            return data.toString();
        }
        return APP_URL;
    }

    private boolean isTrustedInternalUrl(String url) {
        return url.startsWith(APP_URL) ||
                url.contains("backblazeb2.com") ||
                url.contains("res.cloudinary.com") ||
                url.startsWith("file:///android_asset/");
    }

    private String buildDownloadFileName(String url, String contentDisposition, String mimetype) {
        String providedName = getPlainProvidedFileName(contentDisposition);
        String guessed = providedName != null
                ? providedName
                : URLUtil.guessFileName(url, contentDisposition, mimetype);
        String urlName = getFileNameFromUrl(url);
        if (isGenericDownloadName(guessed) && urlName != null) {
            guessed = urlName;
        }

        String safeName = guessed.replaceAll("[^a-zA-Z0-9._\\-]", "_");
        if (safeName.trim().isEmpty()) {
            safeName = "wallpaper";
        }
        String lower = safeName.toLowerCase(Locale.US);
        if (!lower.matches(".*\\.(jpg|jpeg|png|webp)$")) {
            if (mimetype != null && mimetype.contains("png")) {
                safeName += ".png";
            } else if (mimetype != null && mimetype.contains("webp")) {
                safeName += ".webp";
            } else {
                safeName += ".jpg";
            }
        }
        return safeName;
    }

    private String getPlainProvidedFileName(String contentDisposition) {
        if (contentDisposition == null) return null;
        String value = contentDisposition.trim();
        if (value.isEmpty()) return null;
        String lower = value.toLowerCase(Locale.US);
        if (lower.contains("filename=") || lower.startsWith("attachment") || lower.startsWith("inline")) {
            return null;
        }
        return value;
    }

    private String getFileNameFromUrl(String url) {
        try {
            String lastPathSegment = Uri.parse(url).getLastPathSegment();
            if (lastPathSegment == null || lastPathSegment.trim().isEmpty()) return null;
            String candidate = lastPathSegment.replaceAll("[^a-zA-Z0-9._\\-]", "_");
            return candidate.trim().isEmpty() ? null : candidate;
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isGenericDownloadName(String filename) {
        if (filename == null) return true;
        String lower = filename.toLowerCase(Locale.US);
        return lower.equals("download") ||
                lower.equals("download.jpg") ||
                lower.equals("wallapp_download") ||
                lower.equals("wallapp_download.jpg") ||
                lower.startsWith("wallpaper_");
    }

    private String getImageExtensionFromUrl(String url) {
        String name = getFileNameFromUrl(url);
        if (name == null) return ".jpg";
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".png")) return ".png";
        if (lower.endsWith(".webp")) return ".webp";
        if (lower.endsWith(".jpeg")) return ".jpeg";
        return ".jpg";
    }

    private void sharePageLink(String url, String title) {
        String link = (url == null || url.trim().isEmpty()) ? webView.getUrl() : url;
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, title == null ? "WallApp" : title);
        intent.putExtra(Intent.EXTRA_TEXT, (title == null ? "WallApp" : title) + "\n" + link);
        startActivity(Intent.createChooser(intent, "Share WallApp"));
    }

    private void openExternalUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "No app found to open this link", Toast.LENGTH_SHORT).show();
        }
    }

    private void copyLinkToClipboard(String url) {
        String link = (url == null || url.trim().isEmpty()) ? webView.getUrl() : url;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null && link != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("WallApp link", link));
            Toast.makeText(this, R.string.link_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private void loadOnlineHomeOrToast() {
        if (isNetworkAvailable()) {
            webView.loadUrl(APP_URL);
        } else {
            Toast.makeText(this, R.string.no_internet, Toast.LENGTH_SHORT).show();
        }
    }

    private void showAppActionsDialog() {
        String[] actions = new String[]{
                getString(R.string.share_page),
                getString(R.string.open_in_browser),
                getString(R.string.copy_link),
                getString(R.string.clear_cache),
                getString(R.string.about_wallapp)
        };

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.app_actions)
                .setItems(actions, (dialog, which) -> {
                    String currentUrl = webView.getUrl() == null ? APP_URL : webView.getUrl();
                    switch (which) {
                        case 0:
                            sharePageLink(currentUrl, webView.getTitle());
                            break;
                        case 1:
                            openExternalUrl(currentUrl);
                            break;
                        case 2:
                            copyLinkToClipboard(currentUrl);
                            break;
                        case 3:
                            webView.clearCache(true);
                            CookieManager.getInstance().flush();
                            Toast.makeText(this, R.string.cache_cleared, Toast.LENGTH_SHORT).show();
                            break;
                        case 4:
                            showAboutDialog();
                            break;
                        default:
                            break;
                    }
                })
                .show();
    }

    private void showAboutDialog() {
        String version = "1.1.1";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
        }

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.about_wallapp)
                .setMessage("WallApp " + version + "\n\nhttps://thewallapp.pages.dev")
                .setPositiveButton(android.R.string.ok, null)
                .show();
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
