package com.sevoopos.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    // ─────────────────────────────────────────
    //  CONFIG — change LOCAL_IP if needed
    // ─────────────────────────────────────────
    private static final String ONLINE_URL = "https://davaster.online/login.php";
    private static final String LOCAL_URL  = "http://192.168.1.5/pos/login.php";
    private static final int    CHECK_INTERVAL_MS = 15000; // 15 seconds

    // ─────────────────────────────────────────
    //  UI
    // ─────────────────────────────────────────
    private WebView    webView;
    private TextView   statusBadge;
    private TextView   loadingText;
    private FrameLayout rootLayout;

    // ─────────────────────────────────────────
    //  STATE
    // ─────────────────────────────────────────
    private String  currentMode = null; // "online" | "local" | "none"
    private boolean isFirstLoad = true;

    private final Handler          mainHandler   = new Handler(Looper.getMainLooper());
    private final ExecutorService  executor      = Executors.newSingleThreadExecutor();
    private       Runnable         connectivityRunnable;

    // ─────────────────────────────────────────────────────────────────────────
    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Full screen
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        buildUI();
        setupWebView();
        showLoading("Connecting to server...");
        checkAndLoad(true);
    }

    // ─────────────────────────────────────────
    //  BUILD UI PROGRAMMATICALLY
    // ─────────────────────────────────────────
    private void buildUI() {
        rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(Color.parseColor("#f1f5f9"));

        // WebView
        webView = new WebView(this);
        webView.setVisibility(View.GONE);
        rootLayout.addView(webView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));

        // Loading text
        loadingText = new TextView(this);
        loadingText.setText("Connecting to server...");
        loadingText.setTextColor(Color.parseColor("#64748b"));
        loadingText.setTextSize(16f);
        loadingText.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams loadingParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        );
        rootLayout.addView(loadingText, loadingParams);

        // Status badge (bottom-left)
        statusBadge = new TextView(this);
        statusBadge.setTextColor(Color.WHITE);
        statusBadge.setTextSize(12f);
        statusBadge.setPadding(24, 10, 24, 10);
        statusBadge.setVisibility(View.GONE);
        statusBadge.setBackgroundColor(Color.parseColor("#22c55e"));
        FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        );
        badgeParams.gravity = Gravity.BOTTOM | Gravity.START;
        badgeParams.setMargins(24, 0, 0, 24);
        rootLayout.addView(statusBadge, badgeParams);

        setContentView(rootLayout);
    }

    // ─────────────────────────────────────────
    //  SETUP WEBVIEW
    // ─────────────────────────────────────────
    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(false);

        webView.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        webView.setWebChromeClient(new WebChromeClient());

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // Keep all navigation inside the WebView
                String url = request.getUrl().toString();
                if (url.startsWith("http://192.168.1.5") || url.startsWith("https://davaster.online")) {
                    return false; // load inside webview
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                hideLoading();
                updateBadge(currentMode);
                startConnectivityMonitor();
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                // Page failed — re-check and switch
                checkAndLoad(false);
            }
        });
    }

    // ─────────────────────────────────────────
    //  CHECK SERVERS AND LOAD BEST ONE
    // ─────────────────────────────────────────
    private void checkAndLoad(boolean isFirst) {
        executor.execute(() -> {
            boolean onlineOk = checkServer(ONLINE_URL, 5000);
            String  bestUrl;
            String  mode;

            if (onlineOk) {
                bestUrl = ONLINE_URL;
                mode    = "online";
            } else {
                boolean localOk = checkServer(LOCAL_URL, 3000);
                if (localOk) {
                    bestUrl = LOCAL_URL;
                    mode    = "local";
                } else {
                    bestUrl = null;
                    mode    = "none";
                }
            }

            final String finalUrl  = bestUrl;
            final String finalMode = mode;

            mainHandler.post(() -> {
                if (finalMode.equals("none")) {
                    currentMode = "none";
                    showNoServerPage();
                    return;
                }

                // Only reload if mode changed or first load
                if (isFirst || !finalMode.equals(currentMode)) {
                    if (!isFirst && !finalMode.equals(currentMode)) {
                        // Notify user of switch
                        String msg = finalMode.equals("online")
                            ? "🌐 Switched to Online server"
                            : "🏠 Switched to Local server";
                        Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                    }
                    currentMode = finalMode;
                    webView.setVisibility(View.VISIBLE);
                    loadingText.setVisibility(View.GONE);
                    webView.loadUrl(finalUrl);
                }

                updateBadge(finalMode);
            });
        });
    }

    // ─────────────────────────────────────────
    //  CONNECTIVITY MONITOR (every 15 seconds)
    // ─────────────────────────────────────────
    private void startConnectivityMonitor() {
        stopConnectivityMonitor();
        connectivityRunnable = new Runnable() {
            @Override
            public void run() {
                checkAndLoad(false);
                mainHandler.postDelayed(this, CHECK_INTERVAL_MS);
            }
        };
        mainHandler.postDelayed(connectivityRunnable, CHECK_INTERVAL_MS);
    }

    private void stopConnectivityMonitor() {
        if (connectivityRunnable != null) {
            mainHandler.removeCallbacks(connectivityRunnable);
            connectivityRunnable = null;
        }
    }

    // ─────────────────────────────────────────
    //  CHECK IF A SERVER IS REACHABLE
    // ─────────────────────────────────────────
    private boolean checkServer(String urlString, int timeoutMs) {
        try {
            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestMethod("HEAD");
            int code = conn.getResponseCode();
            conn.disconnect();
            return code < 500;
        } catch (IOException e) {
            return false;
        }
    }

    // ─────────────────────────────────────────
    //  STATUS BADGE
    // ─────────────────────────────────────────
    private void updateBadge(String mode) {
        if (mode == null || mode.equals("none")) {
            statusBadge.setVisibility(View.GONE);
            return;
        }
        statusBadge.setVisibility(View.VISIBLE);
        if (mode.equals("online")) {
            statusBadge.setText("🌐 Online");
            statusBadge.setBackgroundColor(Color.parseColor("#22c55e"));
        } else {
            statusBadge.setText("🏠 Local");
            statusBadge.setBackgroundColor(Color.parseColor("#f97316"));
        }
    }

    // ─────────────────────────────────────────
    //  LOADING / NO SERVER UI
    // ─────────────────────────────────────────
    private void showLoading(String message) {
        loadingText.setText(message);
        loadingText.setVisibility(View.VISIBLE);
        webView.setVisibility(View.GONE);
        statusBadge.setVisibility(View.GONE);
    }

    private void hideLoading() {
        loadingText.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
    }

    private void showNoServerPage() {
        stopConnectivityMonitor();
        webView.setVisibility(View.VISIBLE);
        loadingText.setVisibility(View.GONE);
        statusBadge.setVisibility(View.GONE);

        String html = "<!DOCTYPE html><html><head><meta charset='UTF-8'>"
            + "<meta name='viewport' content='width=device-width, initial-scale=1'>"
            + "<style>"
            + "* { margin:0; padding:0; box-sizing:border-box; }"
            + "body { font-family: sans-serif; background:#f1f5f9; display:flex;"
            + "align-items:center; justify-content:center; height:100vh;"
            + "flex-direction:column; gap:20px; padding:20px; text-align:center; }"
            + ".logo { font-size:2rem; font-weight:800; color:#f97316; }"
            + ".logo span { color:#64748b; font-weight:300; }"
            + ".box { background:#fff; border-radius:16px; padding:28px 24px;"
            + "max-width:400px; width:100%; box-shadow:0 4px 20px rgba(0,0,0,0.08); }"
            + ".icon { font-size:2.5rem; margin-bottom:12px; }"
            + "h2 { font-size:1.2rem; margin-bottom:10px; color:#0f172a; }"
            + "p { color:#64748b; font-size:0.88rem; line-height:1.7; margin-bottom:8px; }"
            + ".servers { background:#f8fafc; border-radius:8px; padding:12px 16px;"
            + "margin:14px 0; text-align:left; font-size:0.82rem; border:1px solid #e2e8f0; }"
            + ".servers div { padding:3px 0; color:#475569; }"
            + ".servers .err { color:#ef4444; font-weight:600; }"
            + ".servers .label { color:#94a3b8; font-size:0.75rem; }"
            + "button { background:#f97316; color:white; border:none; padding:13px 0;"
            + "border-radius:10px; font-size:1rem; font-weight:600; cursor:pointer;"
            + "margin-top:8px; width:100%; }"
            + ".cd { color:#94a3b8; font-size:0.8rem; margin-top:10px; }"
            + "</style></head><body>"
            + "<div class='logo'>Sevoo <span>POS</span></div>"
            + "<div class='box'>"
            + "<div class='icon'>📡</div>"
            + "<h2>No Server Found</h2>"
            + "<p>Could not connect to either server.</p>"
            + "<div class='servers'>"
            + "<div class='label'>Online server:</div>"
            + "<div>davaster.online <span class='err'>✗</span></div><br/>"
            + "<div class='label'>Local server:</div>"
            + "<div>192.168.1.5 <span class='err'>✗</span></div>"
            + "</div>"
            + "<p>Make sure internet is connected<br>or local PC (Laragon) is running.</p>"
            + "<button onclick='location.reload()'>🔄 Try Again</button>"
            + "<div class='cd'>Auto-retry in <b id='t'>15</b>s</div>"
            + "</div>"
            + "<script>"
            + "var s=15;"
            + "setInterval(function(){s--;document.getElementById('t').textContent=s;"
            + "if(s<=0){s=15;location.reload();}},1000);"
            + "</script>"
            + "</body></html>";

        webView.loadData(html, "text/html", "UTF-8");

        // Also retry from native side after 15 seconds
        mainHandler.postDelayed(() -> checkAndLoad(true), 15000);
    }

    // ─────────────────────────────────────────
    //  BACK BUTTON — navigate inside WebView
    // ─────────────────────────────────────────
    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            // Do nothing — don't close the POS app by accident
        }
    }

    // ─────────────────────────────────────────
    //  LIFECYCLE
    // ─────────────────────────────────────────
    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
        startConnectivityMonitor();
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
        stopConnectivityMonitor();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopConnectivityMonitor();
        executor.shutdown();
        webView.destroy();
    }
}
