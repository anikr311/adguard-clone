# AdLocko for Android

AdLocko is a DNS-level ad and tracker blocker. It runs a local VPN that
carries **DNS traffic only** and filters it against a domain blocklist.

## How protection works

- `LocalVpnService` creates a TUN interface with a single /32 route to a
  virtual DNS address, so no app traffic other than DNS enters the tunnel.
- Every DNS question is checked against the blocklist (`Blocklist.java`).
  Blocked domains get a local sinkhole answer (A -> 0.0.0.0, AAAA -> ::).
- Allowed questions are forwarded over a protected socket to the resolver
  chosen in Settings (AdGuard DNS 94.140.14.14, Cloudflare 1.1.1.1, or
  Google 8.8.8.8) and the real answer is returned to the asking app.
- Counters and the recent blocked-domain log are real, measured values
  stored only on the device.

## Blocklist

- Bundled: `app/src/main/assets/blocklist.txt` — 71,491 domains built from
  the free StevenBlack unified hosts file (ads + malware).
- In the app, **Update blocklist** downloads the latest StevenBlack list
  over HTTPS and stores it in internal storage. It applies the next time
  protection starts.

## Building

CI (`.github/workflows/build-apk.yml`) builds debug and release APKs on
every push to `android-app/` and publishes them as the `adlocko-apks`
artifact.

Locally: JDK 17 + Gradle 8.9 + Android SDK 35, then in this folder:

```bash
gradle assembleDebug
```

## Signing

Release builds are **never** signed with the debug key. To sign a release,
set these environment variables to your own keystore first:

`ADLOCKO_KEYSTORE_PATH`, `ADLOCKO_KEYSTORE_PASSWORD`, `ADLOCKO_KEY_ALIAS`,
`ADLOCKO_KEY_PASSWORD`

Without them the release APK is unsigned (fine for development builds).

## Known limits (v1.1)

- Filtering is DNS-based: it cannot remove ads that are served from the
  same domain as content (for example in-app video ads), and it does no
  cosmetic/page filtering.
- DNS over TCP and DNS carried inside other VPNs are out of scope.
- IPv6 DNS servers of the underlying network are not intercepted; the
  virtual IPv4 DNS set by the VPN is what Android apps use while AdLocko
  is on.
