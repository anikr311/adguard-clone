package com.adlocko.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Downloads the latest StevenBlack unified hosts file and converts it into
 * AdLocko's plain domain list in internal storage. The VPN service picks the
 * updated list up on its next start; a running service keeps its loaded list.
 *
 * Only HTTPS is used and the download is capped so a bad response cannot
 * fill storage. Nothing is uploaded anywhere.
 */
public final class BlocklistUpdater {

    public static final String SOURCE_URL =
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts";
    public static final String PREFS = "adlocko_prefs";
    public static final String KEY_UPDATED_AT = "blocklist_updated_at";
    public static final String KEY_UPDATED_COUNT = "blocklist_updated_count";

    private static final int MAX_BYTES = 16 * 1024 * 1024;

    public interface Callback {
        void onDone(boolean success, int domainCount, String message);
    }

    private BlocklistUpdater() {}

    public static void update(final Context context, final Callback callback) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    URL url = new URL(SOURCE_URL);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(30000);
                    conn.setRequestProperty("User-Agent", "AdLocko-Android");
                    int code = conn.getResponseCode();
                    if (code != HttpURLConnection.HTTP_OK) {
                        callback.onDone(false, 0, "Download failed (HTTP " + code + ")");
                        return;
                    }
                    Set<String> domains = new TreeSet<>();
                    InputStream in = conn.getInputStream();
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(in, StandardCharsets.UTF_8));
                    String line;
                    long read = 0;
                    while ((line = reader.readLine()) != null) {
                        read += line.length();
                        if (read > MAX_BYTES) {
                            callback.onDone(false, 0, "List too large, update stopped");
                            return;
                        }
                        parseHostsLine(line, domains);
                    }
                    reader.close();
                    if (domains.size() < 1000) {
                        callback.onDone(false, 0, "Downloaded list looked incomplete");
                        return;
                    }
                    File tmp = new File(context.getFilesDir(), Blocklist.UPDATED_FILE_NAME + ".tmp");
                    FileOutputStream out = new FileOutputStream(tmp);
                    for (String d : domains) {
                        out.write((d + "\n").getBytes(StandardCharsets.UTF_8));
                    }
                    out.flush();
                    out.close();
                    File target = new File(context.getFilesDir(), Blocklist.UPDATED_FILE_NAME);
                    if (target.exists() && !target.delete()) {
                        callback.onDone(false, 0, "Could not replace the old list");
                        return;
                    }
                    if (!tmp.renameTo(target)) {
                        callback.onDone(false, 0, "Could not save the new list");
                        return;
                    }
                    SharedPreferences prefs =
                            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                    prefs.edit()
                            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
                            .putInt(KEY_UPDATED_COUNT, domains.size())
                            .apply();
                    callback.onDone(true, domains.size(),
                            "Blocklist updated. Restart protection to use it.");
                } catch (Exception e) {
                    callback.onDone(false, 0, "Update failed: " + e.getClass().getSimpleName());
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        }, "adlocko-blocklist-update");
        thread.setDaemon(true);
        thread.start();
    }

    private static void parseHostsLine(String line, Set<String> out) {
        String t = line.trim();
        if (t.isEmpty() || t.startsWith("#")) return;
        String[] parts = t.split("\\s+");
        if (parts.length < 2) return;
        if (!"0.0.0.0".equals(parts[0]) && !"127.0.0.1".equals(parts[0])) return;
        String host = parts[1].toLowerCase(java.util.Locale.US);
        if (host.indexOf('.') <= 0) return;
        if (host.equals("localhost") || host.equals("localhost.localdomain")
                || host.equals("local") || host.equals("broadcasthost")) return;
        if (host.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) return;
        if (!host.matches("[a-z0-9\\-\\.]+")) return;
        out.add(host);
    }

    // Kept for API stability if a future list format needs the raw set type.
    static Set<String> newSet() {
        return new HashSet<>();
    }
}
