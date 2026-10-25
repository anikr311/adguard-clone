package com.adlocko.app;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Domain blocklist for DNS-level filtering.
 *
 * Loads the bundled list (assets/blocklist.txt, built from the StevenBlack
 * unified hosts file) and, when present, a newer list downloaded by
 * {@link BlocklistUpdater} into internal storage. Matching is exact-domain
 * plus any parent domain, so blocking "example.com" also blocks
 * "ads.example.com" no matter how deep the queried name is.
 */
public final class Blocklist {

    public static final String UPDATED_FILE_NAME = "blocklist-updated.txt";

    private final Set<String> domains = new HashSet<>();
    private final int bundledCount;
    private final boolean usingUpdatedList;

    private Blocklist(Set<String> loaded, int bundled, boolean updated) {
        domains.addAll(loaded);
        bundledCount = bundled;
        usingUpdatedList = updated;
    }

    public static Blocklist load(Context context) {
        Set<String> bundled = new HashSet<>();
        // The bundled list ships in two parts (blocklist-1/-2.txt) so each
        // file stays a manageable size; together they are one set.
        readInto(context, bundled, true);
        File updatedFile = new File(context.getFilesDir(), UPDATED_FILE_NAME);
        if (updatedFile.isFile() && updatedFile.length() > 0) {
            Set<String> updated = new HashSet<>();
            readInto(context, updated, false);
            if (!updated.isEmpty()) {
                return new Blocklist(updated, bundled.size(), true);
            }
        }
        return new Blocklist(bundled, bundled.size(), false);
    }

    private static void readInto(Context context, Set<String> out, boolean bundled) {
        InputStream in = null;
        try {
            if (bundled) {
                in = new java.io.SequenceInputStream(
                        context.getAssets().open("blocklist-1.txt"),
                        context.getAssets().open("blocklist-2.txt"));
            } else {
                in = new FileInputStream(new File(context.getFilesDir(), UPDATED_FILE_NAME));
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim().toLowerCase(Locale.US);
                if (!line.isEmpty() && line.indexOf('.') > 0) {
                    out.add(line);
                }
            }
            reader.close();
        } catch (Exception ignored) {
            // A missing or unreadable list simply means no domains are blocked.
        } finally {
            if (in != null) {
                try { in.close(); } catch (Exception ignored) {}
            }
        }
    }

    /** True if the domain or any of its parent domains is on the list. */
    public boolean isBlocked(String domain) {
        if (domain == null) return false;
        String d = domain.toLowerCase(Locale.US);
        if (d.endsWith(".")) d = d.substring(0, d.length() - 1);
        while (!d.isEmpty()) {
            if (domains.contains(d)) return true;
            int dot = d.indexOf('.');
            if (dot < 0) break;
            d = d.substring(dot + 1);
        }
        return false;
    }

    public int size() {
        return domains.size();
    }

    public int bundledCount() {
        return bundledCount;
    }

    public boolean isUsingUpdatedList() {
        return usingUpdatedList;
    }
}
