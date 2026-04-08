package com.sevoo.pos;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import android.widget.Toast;
import android.content.Context;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    // ─────────────────────────────────────────
    //  CONFIG — change these if needed
    // ─────────────────────────────────────────
    private static final String ONLINE_URL = "https://davaster.online/login.php";
    private static final String LOCAL_URL  = "http://192.168.1.5/pos/login.php";
    private static final int    CHECK_INTERVAL_MS = 15000; // 15 seconds

    // ─────────────────────────────────────────
    //  STATE
    // ─────────────────────────────────────────
    private WebView webView;
    private TextView statusBadge;
    private View noServerLayout;
    private String currentMode = null; // "online", "local", "none"
    private boolean isFirstLoad = true;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // ─────────────────────────────────────────
    //  LIFECYCLE
    // ─────────────────────────────────────────
    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Full screen — no title bar, no status bar
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        // Keep screen on — important for POS tablets
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_main);

        webView        = findViewById(R.id.webView);
        statusBadge    = findViewById(R.id.statusBadge);
        noServerLayout = findViewById(R.id.noServerLayout);

        setupWebView();

        // Retry button on no-server screen
        findViewById(R.id.retryButton).setOnClickListener(v -> checkAndLoad());

        // First load
        checkAndLoad();

        // Start background monitor
        startConnectivityMonitor();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        executor.shutdown();
    }

    // ─────────────────────────────────────────
    //  WEBVIEW SETUP
    // ─────────────────────────────────────────
    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(false);

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                showWebView();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (request.isForMainFrame()) {
                    // Main page failed — re-check servers
                    checkAndLoad();
                }
            }
        });
    }

    // ─────────────────────────────────────────
    //  CHECK SERVERS AND LOAD BEST ONE
    // ─────────────────────────────────────────
    private void checkAndLoad() {
        showLoading();
        executor.execute(() -> {
            boolean onlineOk = isServerReachable(ONLINE_URL, 5000);
            String url;
            String mode;

            if (onlineOk) {
                url  = ONLINE_URL;
                mode = "online";
            } else {
                boolean localOk = isServerReachable(LOCAL_URL, 3000);
                if (localOk) {
                    url  = LOCAL_URL;
                    mode = "local";
                } else {
                    url  = null;
                    mode = "none";
                }
            }

            final String finalUrl  = url;
            final String finalMode = mode;

            handler.post(() -> {
                if ("none".equals(finalMode)) {
                    currentMode = "none";
                    showNoServer();
                } else {
                    if (!finalMode.equals(currentMode) || isFirstLoad) {
                        isFirstLoad = false;
                        currentMode = finalMode;
                        webView.loadUrl(finalUrl);
                        updateStatusBadge(finalMode);
                    } else {
                        // Same mode, just update badge
                        updateStatusBadge(finalMode);
                        showWebView();
                    }
                }
            });
        });
    }

    // ─────────────────────────────────────────
    //  BACKGROUND CONNECTIVITY MONITOR
    // ─────────────────────────────────────────
    private void startConnectivityMonitor() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isFinishing()) {
                    executor.execute(() -> {
                        boolean onlineOk = isServerReachable(ONLINE_URL, 5000);
                        String newMode;
                        String newUrl;

                        if (onlineOk) {
                            newMode = "online";
                            newUrl  = ONLINE_URL;
                        } else {
                            boolean localOk = isServerReachable(LOCAL_URL, 3000);
                            if (localOk) {
                                newMode = "local";
                                newUrl  = LOCAL_URL;
                            } else {
                                newMode = "none";
                                newUrl  = null;
                            }
                        }

                        final String fm = newMode;
                        final String fu = newUrl;

                        handler.post(() -> {
                            if (!fm.equals(currentMode)) {
                                // Mode changed — switch!
                                currentMode = fm;
                                if ("none".equals(fm)) {
                                    showNoServer();
                                } else {
                                    webView.loadUrl(fu);
                                    updateStatusBadge(fm);
                                    showWebView();
                                    String msg = "online".equals(fm)
                                        ? "🌐 Switched to Online Server"
                                        : "🏠 Switched to Local Server";
                                    Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                                }
                            } else {
                                updateStatusBadge(fm);
                            }
                        });
                    });
                    handler.postDelayed(this, CHECK_INTERVAL_MS);
                }
            }
        }, CHECK_INTERVAL_MS);
    }

    // ─────────────────────────────────────────
    //  SERVER REACHABILITY CHECK
    // ─────────────────────────────────────────
    private boolean isServerReachable(String urlString, int timeoutMs) {
        try {
            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestMethod("HEAD");
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code < 500;
        } catch (IOException e) {
            return false;
        }
    }

    // ─────────────────────────────────────────
    //  UI STATE HELPERS
    // ─────────────────────────────────────────
    private void showWebView() {
        webView.setVisibility(View.VISIBLE);
        noServerLayout.setVisibility(View.GONE);
        statusBadge.setVisibility(View.VISIBLE);
    }

    private void showLoading() {
        // Keep whatever is showing, just hide no-server screen
        noServerLayout.setVisibility(View.GONE);
    }

    private void showNoServer() {
        webView.setVisibility(View.GONE);
        statusBadge.setVisibility(View.GONE);
        noServerLayout.setVisibility(View.VISIBLE);
    }

    private void updateStatusBadge(String mode) {
        if ("online".equals(mode)) {
            statusBadge.setText("🌐 Online");
            statusBadge.setBackgroundResource(R.drawable.badge_online);
        } else {
            statusBadge.setText("🏠 Local");
            statusBadge.setBackgroundResource(R.drawable.badge_local);
        }
        statusBadge.setVisibility(View.VISIBLE);
    }

    // ─────────────────────────────────────────
    //  BACK BUTTON — navigate WebView history
    // ─────────────────────────────────────────
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
