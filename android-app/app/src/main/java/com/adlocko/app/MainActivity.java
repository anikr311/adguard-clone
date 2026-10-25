package com.adlocko.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * AdLocko main screen.
 *
 * Security notes:
 * - The UI is served through WebViewAssetLoader from the app's own assets
 *   under a fixed https origin. File access and content access are OFF, so
 *   web content can never read device files.
 * - Navigation is locked to that origin; any other link is handed to the
 *   system browser, where the JavaScript bridge does not exist.
 * - The bridge exposes only AdLocko's own controls and measured stats.
 */
public class MainActivity extends Activity {

    private static final int VPN_REQUEST_CODE = 1000;
    private static final int NOTIFY_REQUEST_CODE = 1001;
    private static final String UI_URL = "https://appassets.androidplatform.net/www/index.html";

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        if (window != null) {
            window.setStatusBarColor(Color.parseColor("#0B1220"));
            window.setNavigationBarColor(Color.parseColor("#0B1220"));
        }

        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/www/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView = new WebView(this);
        webView.setBackgroundColor(Color.parseColor("#0B1220"));

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }
        settings.setSupportZoom(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if (url != null && "appassets.androidplatform.net".equals(url.getHost())) {
                    return false; // our own UI assets
                }
                // Anything else leaves the app: open in the system browser,
                // where the AdLocko bridge is not present.
                if (url != null) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, url));
                    } catch (Exception ignored) {}
                }
                return true;
            }
        });

        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
        setContentView(webView);
        webView.loadUrl(UI_URL);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                startLocalVpnService();
                notifyUiState(true);
            } else {
                // The old clone ignored this and lied "protection enabled".
                getSharedPreferences(LocalVpnService.PREFS, MODE_PRIVATE).edit()
                        .putBoolean(LocalVpnService.KEY_PROTECTION, false).apply();
                Toast.makeText(this,
                        "VPN permission was not given, protection stays off",
                        Toast.LENGTH_LONG).show();
                notifyUiState(false);
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    private void notifyUiState(boolean enabled) {
        if (webView != null) {
            webView.evaluateJavascript(
                    "window.AdLockoUI && window.AdLockoUI.onProtectionStateChanged(" + enabled + ")",
                    null);
        }
    }

    private void startLocalVpnService() {
        Intent intent = new Intent(this, LocalVpnService.class);
        intent.setAction(LocalVpnService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        requestNotificationPermissionIfNeeded();
    }

    private void stopLocalVpnService() {
        Intent intent = new Intent(this, LocalVpnService.class);
        intent.setAction(LocalVpnService.ACTION_STOP);
        startService(intent);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        NOTIFY_REQUEST_CODE);
            }
        }
    }

    // ------------------------------------------------------------------
    // Bridge used by the bundled UI only
    // ------------------------------------------------------------------

    public class AndroidBridge {

        @JavascriptInterface
        public boolean isProtectionEnabled() {
            return getSharedPreferences(LocalVpnService.PREFS, MODE_PRIVATE)
                    .getBoolean(LocalVpnService.KEY_PROTECTION, false);
        }

        @JavascriptInterface
        public void setProtectionEnabled(final boolean enabled) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (enabled) {
                        Intent prepIntent = VpnService.prepare(MainActivity.this);
                        if (prepIntent != null) {
                            startActivityForResult(prepIntent, VPN_REQUEST_CODE);
                        } else {
                            startLocalVpnService();
                            notifyUiState(true);
                        }
                    } else {
                        stopLocalVpnService();
                        notifyUiState(false);
                    }
                }
            });
        }

        /** Real measured counters for today, as a JSON string. */
        @JavascriptInterface
        public String getStats() {
            SharedPreferences stats = getSharedPreferences(LocalVpnService.STATS_PREFS, MODE_PRIVATE);
            JSONObject json = new JSONObject();
            try {
                json.put("day", stats.getString("day", ""));
                json.put("queries", stats.getInt("queries_today", 0));
                json.put("blocked", stats.getInt("blocked_today", 0));
            } catch (Exception ignored) {}
            return json.toString();
        }

        /** Recent blocked domains, newest first: JSON array of {domain, time}. */
        @JavascriptInterface
        public String getBlockedLog() {
            SharedPreferences stats = getSharedPreferences(LocalVpnService.STATS_PREFS, MODE_PRIVATE);
            String raw = stats.getString("blocked_log", "");
            JSONArray array = new JSONArray();
            SimpleDateFormat fmt = new SimpleDateFormat("HH:mm", Locale.US);
            if (!raw.isEmpty()) {
                for (String line : raw.split("\n")) {
                    int sep = line.indexOf('|');
                    if (sep <= 0) continue;
                    try {
                        long when = Long.parseLong(line.substring(0, sep));
                        JSONObject item = new JSONObject();
                        item.put("domain", line.substring(sep + 1));
                        item.put("time", fmt.format(new Date(when)));
                        array.put(item);
                    } catch (Exception ignored) {}
                }
            }
            return array.toString();
        }

        @JavascriptInterface
        public String getDnsProvider() {
            return getSharedPreferences(LocalVpnService.PREFS, MODE_PRIVATE)
                    .getString(LocalVpnService.KEY_DNS_PROVIDER, "adguard");
        }

        @JavascriptInterface
        public void setDnsProvider(final String provider) {
            if (!"adguard".equals(provider) && !"cloudflare".equals(provider)
                    && !"google".equals(provider)) {
                return;
            }
            getSharedPreferences(LocalVpnService.PREFS, MODE_PRIVATE).edit()
                    .putString(LocalVpnService.KEY_DNS_PROVIDER, provider).apply();
            // No restart needed: the service reads the provider from
            // preferences for every forwarded query, so this applies live.
        }

        /** Blocklist facts: bundled/updated counts and last update time. */
        @JavascriptInterface
        public String getBlocklistInfo() {
            SharedPreferences prefs = getSharedPreferences(LocalVpnService.PREFS, MODE_PRIVATE);
            JSONObject json = new JSONObject();
            try {
                json.put("updatedAt", prefs.getLong(BlocklistUpdater.KEY_UPDATED_AT, 0));
                json.put("updatedCount", prefs.getInt(BlocklistUpdater.KEY_UPDATED_COUNT, 0));
                json.put("bundledCount", 71491);
            } catch (Exception ignored) {}
            return json.toString();
        }

        @JavascriptInterface
        public void updateBlocklist() {
            BlocklistUpdater.update(MainActivity.this, new BlocklistUpdater.Callback() {
                @Override
                public void onDone(final boolean success, final int count, final String message) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                            if (webView != null) {
                                webView.evaluateJavascript(
                                        "window.AdLockoUI && window.AdLockoUI.onBlocklistUpdated("
                                                + success + "," + count + ")", null);
                            }
                        }
                    });
                }
            });
        }
    }
}
