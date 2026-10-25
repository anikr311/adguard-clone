package com.adlocko.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * AdLocko's real protection engine: a local VPN that captures the device's
 * DNS traffic only (a single /32 route to a virtual DNS address) and filters
 * it against the domain blocklist.
 *
 * - Blocked domains get a local sinkhole answer (A -> 0.0.0.0, AAAA -> ::,
 *   everything else an empty NOERROR), so the ad/tracker host never resolves.
 * - Allowed queries are forwarded, over a protected socket, to the upstream
 *   DNS provider the user picked, and the real answer is written back.
 * - All other traffic never enters the tunnel.
 *
 * Counters and a short blocked-domain log are kept in SharedPreferences so
 * the UI can show honest, measured numbers.
 */
public class LocalVpnService extends VpnService {

    public static final String ACTION_START = "com.adlocko.app.START_VPN";
    public static final String ACTION_STOP = "com.adlocko.app.STOP_VPN";

    public static final String PREFS = "adlocko_prefs";
    public static final String KEY_PROTECTION = "protection_enabled";
    public static final String KEY_DNS_PROVIDER = "dns_provider";

    public static final String STATS_PREFS = "adlocko_stats";
    private static final String KEY_DAY = "day";
    private static final String KEY_QUERIES = "queries_today";
    private static final String KEY_BLOCKED = "blocked_today";
    private static final String KEY_LOG = "blocked_log"; // lines "epochMillis|domain", newest first

    private static final String CHANNEL_ID = "adlocko_protection_channel";
    private static final int NOTIFICATION_ID = 1000;
    private static final String TAG = "AdLockoVpn";

    private static final String TUN_ADDRESS = "10.8.0.2";
    private static final String VIRTUAL_DNS = "10.8.0.3";

    private volatile boolean running = false;
    private ParcelFileDescriptor tun;
    private Thread worker;
    private Blocklist blocklist;

    private int queriesToday = 0;
    private int blockedToday = 0;
    private int eventsSinceNotify = 0;

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopProtection();
            return START_NOT_STICKY;
        }
        if (!running) {
            startProtection();
        }
        return START_STICKY;
    }

    @Override
    public void onRevoke() {
        stopProtection();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        stopProtection();
        super.onDestroy();
    }

    private void startProtection() {
        blocklist = Blocklist.load(this);
        loadStats();

        Builder builder = new Builder();
        builder.setSession("AdLocko");
        builder.setMtu(1500);
        builder.addAddress(TUN_ADDRESS, 32);
        builder.addDnsServer(VIRTUAL_DNS);
        builder.addRoute(VIRTUAL_DNS, 32);
        Intent configure = new Intent(this, MainActivity.class);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        builder.setConfigureIntent(PendingIntent.getActivity(this, 0, configure, piFlags));
        try {
            builder.addDisallowedApplication(getPackageName());
        } catch (Exception ignored) {
            // If this fails the tunnel still works; our sockets use protect().
        }

        tun = builder.establish();
        if (tun == null) {
            Log.e(TAG, "Could not establish VPN interface");
            stopSelf();
            return;
        }

        running = true;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_PROTECTION, true).apply();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        worker = new Thread(this::packetLoop, "adlocko-dns-loop");
        worker.setDaemon(true);
        worker.start();
        Log.i(TAG, "Protection started, blocklist size " + blocklist.size());
    }

    private synchronized void stopProtection() {
        running = false;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_PROTECTION, false).apply();
        if (tun != null) {
            try { tun.close(); } catch (IOException ignored) {}
            tun = null;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        stopSelf();
    }

    // ------------------------------------------------------------------
    // Packet loop: IPv4 / UDP / DNS only
    // ------------------------------------------------------------------

    private void packetLoop() {
        FileInputStream in = new FileInputStream(tun.getFileDescriptor());
        FileOutputStream out = new FileOutputStream(tun.getFileDescriptor());
        byte[] buffer = new byte[32767];
        while (running) {
            int length;
            try {
                length = in.read(buffer);
            } catch (IOException e) {
                break;
            }
            if (length <= 0) continue;
            try {
                handlePacket(buffer, length, out);
            } catch (Exception e) {
                Log.w(TAG, "Packet handling failed", e);
            }
        }
        try { in.close(); } catch (IOException ignored) {}
        try { out.close(); } catch (IOException ignored) {}
    }

    private void handlePacket(byte[] packet, int length, FileOutputStream out) throws IOException {
        if (length < 28) return;
        int version = (packet[0] >> 4) & 0xF;
        if (version != 4) return; // IPv6-outer packets are not routed to us.
        int ihl = (packet[0] & 0xF) * 4;
        if (length < ihl + 8) return;
        int protocol = packet[9] & 0xFF;
        if (protocol != 17) return; // UDP only

        int ipTotalLength = ((packet[2] & 0xFF) << 8) | (packet[3] & 0xFF);
        if (ipTotalLength > length) ipTotalLength = length;

        int udpOffset = ihl;
        int srcPort = ((packet[udpOffset] & 0xFF) << 8) | (packet[udpOffset + 1] & 0xFF);
        int dstPort = ((packet[udpOffset + 2] & 0xFF) << 8) | (packet[udpOffset + 3] & 0xFF);
        int udpLength = ((packet[udpOffset + 4] & 0xFF) << 8) | (packet[udpOffset + 5] & 0xFF);
        if (dstPort != 53) return;

        int dnsOffset = udpOffset + 8;
        int dnsLength = udpLength - 8;
        if (dnsLength < 12 || dnsOffset + dnsLength > ipTotalLength) return;

        byte[] query = new byte[dnsLength];
        System.arraycopy(packet, dnsOffset, query, 0, dnsLength);

        DnsQuestion question = DnsQuestion.parse(query);
        byte[] responsePayload;
        recordQuery(question != null ? question.qname : null);
        if (question != null && blocklist.isBlocked(question.qname)) {
            responsePayload = buildSinkholeResponse(query, question);
            recordBlocked(question.qname);
        } else {
            responsePayload = forwardQuery(query);
            if (responsePayload == null) return; // upstream timeout: drop
        }

        byte[] responsePacket = wrapUdpResponse(packet, srcPort, responsePayload);
        synchronized (out) {
            out.write(responsePacket);
            out.flush();
        }
        maybeUpdateNotification();
    }

    /** Sends the raw DNS query to the chosen upstream resolver. */
    private byte[] forwardQuery(byte[] query) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            protect(socket);
            socket.setSoTimeout(3000);
            InetAddress upstream = InetAddress.getByName(upstreamDnsIp());
            DatagramPacket request = new DatagramPacket(query, query.length, upstream, 53);
            socket.send(request);
            byte[] buf = new byte[8192];
            DatagramPacket response = new DatagramPacket(buf, buf.length);
            socket.receive(response);
            byte[] data = new byte[response.getLength()];
            System.arraycopy(buf, 0, data, 0, response.getLength());
            return data;
        } catch (IOException e) {
            return null;
        } finally {
            if (socket != null) socket.close();
        }
    }

    private String upstreamDnsIp() {
        String provider = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_DNS_PROVIDER, "adguard");
        if ("cloudflare".equals(provider)) return "1.1.1.1";
        if ("google".equals(provider)) return "8.8.8.8";
        return "94.140.14.14"; // AdGuard DNS (unfiltered resolver)
    }

    // ------------------------------------------------------------------
    // DNS helpers
    // ------------------------------------------------------------------

    private static final class DnsQuestion {
        final String qname;
        final int qtype;
        final int qclass;
        final int endOffset; // first byte after question section

        DnsQuestion(String qname, int qtype, int qclass, int endOffset) {
            this.qname = qname;
            this.qtype = qtype;
            this.qclass = qclass;
            this.endOffset = endOffset;
        }

        static DnsQuestion parse(byte[] dns) {
            try {
                if (dns.length < 12) return null;
                int qdcount = ((dns[4] & 0xFF) << 8) | (dns[5] & 0xFF);
                if (qdcount != 1) return null;
                StringBuilder name = new StringBuilder();
                int i = 12;
                while (i < dns.length) {
                    int len = dns[i] & 0xFF;
                    if (len == 0) { i++; break; }
                    if (len > 63 || i + 1 + len > dns.length) return null;
                    if (name.length() > 0) name.append('.');
                    name.append(new String(dns, i + 1, len, StandardCharsets.UTF_8));
                    i += 1 + len;
                }
                if (i + 4 > dns.length) return null;
                int qtype = ((dns[i] & 0xFF) << 8) | (dns[i + 1] & 0xFF);
                int qclass = ((dns[i + 2] & 0xFF) << 8) | (dns[i + 3] & 0xFF);
                return new DnsQuestion(name.toString(), qtype, qclass, i + 4);
            } catch (Exception e) {
                return null;
            }
        }
    }

    private byte[] buildSinkholeResponse(byte[] query, DnsQuestion question) {
        boolean hasAnswer = question.qtype == 1 || question.qtype == 28; // A or AAAA
        int answerDataLength = question.qtype == 1 ? 4 : 16;
        int questionLength = question.endOffset - 12;
        int total = 12 + questionLength + (hasAnswer ? 12 + answerDataLength : 0);
        byte[] resp = new byte[total];
        // Header
        resp[0] = query[0]; resp[1] = query[1];           // transaction id
        resp[2] = (byte) 0x81; resp[3] = (byte) 0x80;     // QR, RD, RA, NOERROR
        resp[4] = 0; resp[5] = 1;                          // QDCOUNT
        resp[6] = 0; resp[7] = (byte) (hasAnswer ? 1 : 0); // ANCOUNT
        resp[8] = 0; resp[9] = 0;
        resp[10] = 0; resp[11] = 0;
        // Question section verbatim
        System.arraycopy(query, 12, resp, 12, questionLength);
        if (hasAnswer) {
            int a = 12 + questionLength;
            resp[a] = (byte) 0xC0; resp[a + 1] = 0x0C;                 // name pointer
            resp[a + 2] = (byte) (question.qtype >> 8);
            resp[a + 3] = (byte) question.qtype;
            resp[a + 4] = (byte) (question.qclass >> 8);
            resp[a + 5] = (byte) question.qclass;
            resp[a + 6] = 0; resp[a + 7] = 0; resp[a + 8] = 0; resp[a + 9] = 60; // TTL 60
            resp[a + 10] = (byte) (answerDataLength >> 8);
            resp[a + 11] = (byte) answerDataLength;
            // RDATA stays all zero: 0.0.0.0 for A, :: for AAAA.
        }
        return resp;
    }

    /** Wraps a DNS payload in a fresh IPv4/UDP packet back to the asker. */
    private byte[] wrapUdpResponse(byte[] original, int clientPort, byte[] payload) {
        int totalLength = 20 + 8 + payload.length;
        byte[] packet = new byte[totalLength];
        packet[0] = 0x45; // IPv4, IHL 5
        packet[1] = 0;
        packet[2] = (byte) (totalLength >> 8);
        packet[3] = (byte) totalLength;
        packet[4] = original[4]; packet[5] = original[5]; // keep IP id
        packet[6] = 0; packet[7] = 0;
        packet[8] = 64; // TTL
        packet[9] = 17; // UDP
        // src = original dst (the virtual DNS), dst = original src (the app)
        System.arraycopy(original, 16, packet, 12, 4);
        System.arraycopy(original, 12, packet, 16, 4);
        int ipChecksum = checksum(packet, 0, 20);
        packet[10] = (byte) (ipChecksum >> 8);
        packet[11] = (byte) ipChecksum;

        int udpLength = 8 + payload.length;
        packet[20] = 0; packet[21] = 53; // src port 53
        packet[22] = (byte) (clientPort >> 8);
        packet[23] = (byte) clientPort;
        packet[24] = (byte) (udpLength >> 8);
        packet[25] = (byte) udpLength;
        packet[26] = 0; packet[27] = 0; // checksum filled below
        System.arraycopy(payload, 0, packet, 28, payload.length);

        int udpChecksum = udpChecksum(packet, udpLength);
        if (udpChecksum == 0) udpChecksum = 0xFFFF;
        packet[26] = (byte) (udpChecksum >> 8);
        packet[27] = (byte) udpChecksum;
        return packet;
    }

    private static int checksum(byte[] data, int offset, int length) {
        long sum = 0;
        int i = offset;
        int remaining = length;
        while (remaining > 1) {
            sum += ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);
            i += 2;
            remaining -= 2;
        }
        if (remaining == 1) {
            sum += (data[i] & 0xFF) << 8;
        }
        while ((sum >> 16) != 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        return (int) (~sum & 0xFFFF);
    }

    private static int udpChecksum(byte[] packet, int udpLength) {
        long sum = 0;
        // Pseudo header: src ip, dst ip, zero, protocol, udp length
        for (int i = 12; i < 20; i += 2) {
            sum += ((packet[i] & 0xFF) << 8) | (packet[i + 1] & 0xFF);
        }
        sum += 17;
        sum += udpLength;
        int i = 20;
        int remaining = udpLength;
        while (remaining > 1) {
            sum += ((packet[i] & 0xFF) << 8) | (packet[i + 1] & 0xFF);
            i += 2;
            remaining -= 2;
        }
        if (remaining == 1) {
            sum += (packet[i] & 0xFF) << 8;
        }
        while ((sum >> 16) != 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        return (int) (~sum & 0xFFFF);
    }

    // ------------------------------------------------------------------
    // Stats (honest, measured) + blocked log
    // ------------------------------------------------------------------

    private void loadStats() {
        SharedPreferences stats = getSharedPreferences(STATS_PREFS, MODE_PRIVATE);
        String today = todayKey();
        if (!today.equals(stats.getString(KEY_DAY, ""))) {
            stats.edit().putString(KEY_DAY, today)
                    .putInt(KEY_QUERIES, 0).putInt(KEY_BLOCKED, 0).apply();
            queriesToday = 0;
            blockedToday = 0;
        } else {
            queriesToday = stats.getInt(KEY_QUERIES, 0);
            blockedToday = stats.getInt(KEY_BLOCKED, 0);
        }
    }

    private void recordQuery(String qname) {
        queriesToday++;
        persistStats();
    }

    private void recordBlocked(String qname) {
        blockedToday++;
        SharedPreferences stats = getSharedPreferences(STATS_PREFS, MODE_PRIVATE);
        String old = stats.getString(KEY_LOG, "");
        String entry = System.currentTimeMillis() + "|" + qname;
        StringBuilder sb = new StringBuilder(entry);
        int lines = 1;
        if (!old.isEmpty()) {
            for (String line : old.split("\n")) {
                if (lines >= 50) break;
                if (!line.isEmpty()) {
                    sb.append('\n').append(line);
                    lines++;
                }
            }
        }
        stats.edit().putString(KEY_LOG, sb.toString()).apply();
        persistStats();
    }

    private void persistStats() {
        getSharedPreferences(STATS_PREFS, MODE_PRIVATE).edit()
                .putString(KEY_DAY, todayKey())
                .putInt(KEY_QUERIES, queriesToday)
                .putInt(KEY_BLOCKED, blockedToday)
                .apply();
    }

    private static String todayKey() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    // ------------------------------------------------------------------
    // Notification (shows real counters)
    // ------------------------------------------------------------------

    private void maybeUpdateNotification() {
        eventsSinceNotify++;
        if (eventsSinceNotify >= 10) {
            eventsSinceNotify = 0;
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.notify(NOTIFICATION_ID, buildNotification());
            }
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "AdLocko Protection Status",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shows live DNS filtering counters while protection is on");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, openIntent, flags);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        String text = blockedToday + " blocked of " + queriesToday + " DNS queries today";
        return builder
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("AdLocko protection is on")
                .setContentText(text)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }
}
