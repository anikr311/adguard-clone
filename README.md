# AdGuard 1:1 Local Clone (Reverse-Engineered with REA)

This repository is a complete, self-contained **1:1 local reconstruction** of [`https://adguard.com/en/welcome.html`](https://adguard.com/en/welcome.html), reverse-engineered and verified using **REA (`rea-agents` v6.7.0)**.

## Quick Start

```bash
npm start
# Server starts at http://127.0.0.1:3000/en/welcome.html
```

## REA Reverse-Engineering Evidence Ledger

| Phase | REA Operation | Evidence ID | Key Findings |
| :--- | :--- | :--- | :--- |
| **1. Live Runtime & DOM Capture** | `capture_browser_scenario` | `ev_52135e32c3321ffa8eca2f8f4b253a20199cbcf9f65745a937809902a7ba052d` | Captured 370,734 bytes of live DOM (`sha256: b3038136...`), 14,004 bytes of accessibility tree, 51 network events across `adguard.com`, `st2.adguardcdn.com`, and `cdn.adguardcdn.com`, and 3 cookies (`exid`, `lang`, `user`). |
| **2. Static JS Bundle Reconstruction** | `analyze_javascript_application` | `ev_2845735e53b7a9eca90ab2df7e935bfce9bc83c55bb8c34d140a29092169cf28` | Parsed `main.min.js` + `vendor.min.js` (1,217,512 bytes, 339,998 AST nodes, 0 parse failures). Reconstructed **337 JavaScript modules**, **4 chunks**, **9 Web Storage nodes** (`theme-name`, `DEBUG`, `localStorage`/`sessionStorage`), **24 endpoint/route nodes**, and **601 static findings**. |
| **3. Local Clone Runtime Verification** | `capture_browser_scenario` | `ev_5c5d02d1ab56adcdfdd698733098d9ec99f9224b41036537f6d11962d41ec09e` | Verified 102 local HTTP responses with **0 failed requests** (`status >= 400: 0`). |
| **4. Pixel-Exact Diff Verification** | `compare_web_screenshots` | `ev_1b26b06fd5686f6ce56b7b85e00e55f18fccbed7d88e331b72b216c8a4ad5d83` | `status: "identical"` — **0 changed pixels out of 1,296,000 pixels** (`1440x900`, `channel_threshold: 0`). Both PNG renders share the exact SHA-256 digest `313867a1ad729cfe6dda273857a52b408a07b1e2ade04e3c0417b73ef3b3cc6d`. |

## Project Structure

- `index.html` & `en/welcome.html` — Reconstructed 1:1 HTML entrypoints with all asset references rewritten to local paths.
- `server.mjs` — Zero-dependency Node.js local static & mock-telemetry server (`/click.json`, `/-/logs`, `/-/metrics`).
- `css/modern-enhancements.css` — Modern CSS progressive enhancements (`color-scheme: light dark`, `interpolate-size: allow-keywords`, `prefers-reduced-motion` safety).
- `assets/adguard.com/` — Local Roboto font stylesheet (`css/fonts/roboto.css`).
- `assets/st2.adguardcdn.com/` — Core release bundle (`adguard-website-1d45fb03fce8e8af26028bb2896fdeec68253766`), including:
  - `css/main.min.css`
  - `js/vendor.min.js` & `js/main.min.js`
  - `img/main.min.svg` (SVG icon sprite)
  - `fonts/roboto/` (WOFF2/WOFF font files)
  - `favicons/adguard/`
- `assets/cdn.adguardcdn.com/` — All 600+ product illustrations, Agnar vector assets (`agnar-v4.svg`, `agnar-top-line-v2.svg`), light/dark OS screenshots (Windows, Mac, Android, iOS, Browser Extension), and media files.
