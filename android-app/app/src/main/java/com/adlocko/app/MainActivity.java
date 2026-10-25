package com.adlocko.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int VPN_REQUEST_CODE = 1000;
    private WebView webView;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        if (window != null) {
            window.setStatusBarColor(Color.parseColor("#13171D"));
            window.setNavigationBarColor(Color.parseColor("#181D25"));
        }

        webView = new WebView(this);
        webView.setBackgroundColor(Color.parseColor("#13171D"));

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(false);

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }
        });

        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
        setContentView(webView);
        webView.loadUrl("file:///android_asset/www/index.html");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_REQUEST_CODE) {
            startLocalVpnService();
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

    private void startLocalVpnService() {
        Intent intent = new Intent(this, LocalVpnService.class);
        intent.setAction(LocalVpnService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void stopLocalVpnService() {
        Intent intent = new Intent(this, LocalVpnService.class);
        intent.setAction(LocalVpnService.ACTION_STOP);
        startService(intent);
    }

    public class AndroidBridge {
        @JavascriptInterface
        public void setProtectionEnabled(final boolean enabled) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    SharedPreferences prefs = getSharedPreferences("adlocko_prefs", MODE_PRIVATE);
                    prefs.edit().putBoolean("protection_enabled", enabled).apply();
                    if (enabled) {
                        Intent prepIntent = VpnService.prepare(MainActivity.this);
                        if (prepIntent != null) {
                            startActivityForResult(prepIntent, VPN_REQUEST_CODE);
                        } else {
                            startLocalVpnService();
                        }
                        Toast.makeText(
                            MainActivity.this,
                            "AdLocko Local VPN & DNS Filtering Enabled",
                            Toast.LENGTH_SHORT
                        ).show();
                    } else {
                        stopLocalVpnService();
                        Toast.makeText(
                            MainActivity.this,
                            "AdLocko Protection Paused",
                            Toast.LENGTH_SHORT
                        ).show();
                    }
                }
            });
        }
    }
}
