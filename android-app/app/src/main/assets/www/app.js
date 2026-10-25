(function () {
  const presets = window.ADGUARD_APK_PRESETS || { groups: [], filters: [], dnsProviders: [] };

  const state = {
    protectionEnabled: true,
    pausedUntil: null,
    selectedPeriod: "24h",
    selectedDnsProviderId: 10001, // AdGuard DNS
    modules: {
      adblocking: { name: "Ad blocking", desc: "Blocks ads in browsers and apps using 86 filter lists", enabled: true },
      tracking: { name: "Tracking protection (Stealth Mode)", desc: "Blocks web trackers, third-party cookies & DPI inspection", enabled: true },
      annoyances: { name: "Annoyances blocking", desc: "Blocks cookie notices, pop-ups & mobile app banners", enabled: true },
      dns: { name: "DNS protection", desc: "Encrypts DNS queries and blocks ad/tracker domains system-wide", enabled: true },
      browsingSecurity: { name: "Browsing security", desc: "Protects against phishing and malware domains", enabled: true },
      firewall: { name: "Firewall", desc: "Controls Wi-Fi and mobile data access for installed apps", enabled: true },
    },
    apps: [
      { pkg: "com.android.chrome", name: "Google Chrome", routed: true, adblock: true, wifi: true, cell: true, blocked: 642 },
      { pkg: "com.google.android.youtube", name: "YouTube", routed: true, adblock: true, wifi: true, cell: true, blocked: 418 },
      { pkg: "com.instagram.android", name: "Instagram", routed: true, adblock: true, wifi: true, cell: true, blocked: 295 },
      { pkg: "com.zhiliaoapp.musically", name: "TikTok", routed: true, adblock: true, wifi: true, cell: true, blocked: 512 },
      { pkg: "com.reddit.frontpage", name: "Reddit", routed: true, adblock: true, wifi: true, cell: true, blocked: 194 },
      { pkg: "com.spotify.music", name: "Spotify", routed: true, adblock: true, wifi: true, cell: true, blocked: 147 },
      { pkg: "org.telegram.messenger", name: "Telegram", routed: true, adblock: false, wifi: true, cell: true, blocked: 12 },
      { pkg: "com.twitter.android", name: "X (Twitter)", routed: true, adblock: true, wifi: true, cell: true, blocked: 238 },
    ],
    periodStats: {
      "24h": { ads: "1,428", trackers: "892", data: "48.6 MB" },
      "7d": { ads: "9,840", trackers: "6,312", data: "312.4 MB" },
      "30d": { ads: "41,290", trackers: "28,450", data: "1.34 GB" },
      "all": { ads: "194,820", trackers: "132,110", data: "6.42 GB" },
    },
    recentLogs: [
      { time: "Just now", domain: "googleads.g.doubleclick.net", app: "Google Chrome", company: "Google", type: "HTTPS / Ad", status: "blocked", rule: "||doubleclick.net^" },
      { time: "4s ago", domain: "graph.facebook.com", app: "Instagram", company: "Meta", type: "DNS / Tracker", status: "blocked", rule: "||graph.facebook.com^$third-party" },
      { time: "9s ago", domain: "analytics.tiktok.com", app: "TikTok", company: "ByteDance", type: "DNS / Tracker", status: "blocked", rule: "||analytics.tiktok.com^" },
      { time: "15s ago", domain: "adservice.google.com", app: "YouTube", company: "Google", type: "HTTPS / Ad", status: "blocked", rule: "||adservice.google.com^" },
      { time: "22s ago", domain: "alb.reddit.com", app: "Reddit", company: "Reddit", type: "DNS / Tracker", status: "blocked", rule: "||alb.reddit.com^" },
      { time: "29s ago", domain: "adguard.com", app: "Google Chrome", company: "AdGuard", type: "HTTPS / A", status: "allowed", rule: "Allowed" },
      { time: "36s ago", domain: "criteo.com", app: "Google Chrome", company: "Criteo", type: "DNS / Ad", status: "blocked", rule: "||criteo.com^" },
    ],
    companies: [
      { name: "Google (DoubleClick & Analytics)", requests: 842, blocked: 694 },
      { name: "Meta Platforms (Facebook Graph)", requests: 412, blocked: 389 },
      { name: "ByteDance (TikTok Telemetry)", requests: 512, blocked: 512 },
      { name: "Amazon Advertising", requests: 264, blocked: 241 },
      { name: "Criteo Ad Network", requests: 189, blocked: 189 },
    ],
  };

  let pauseInterval = null;

  function showToast(msg) {
    const el = document.getElementById("toastBanner");
    el.textContent = msg;
    el.classList.add("visible");
    clearTimeout(el._timer);
    el._timer = setTimeout(() => el.classList.remove("visible"), 2400);
  }

  function notifyNativeAndroid(enabled) {
    if (window.AndroidBridge && typeof window.AndroidBridge.setProtectionEnabled === "function") {
      try {
        window.AndroidBridge.setProtectionEnabled(Boolean(enabled));
      } catch {}
    }
  }

  function renderHome() {
    const card = document.getElementById("heroShieldCard");
    const title = document.getElementById("protectionTitle");
    const sub = document.getElementById("protectionSubtitle");
    const btnText = document.getElementById("btnMasterToggleText");
    const shieldFill = document.getElementById("shieldPathFill");
    const pauseLabel = document.getElementById("pauseStatusLabel");

    card.classList.remove("paused", "disabled");
    if (state.pausedUntil && state.pausedUntil > Date.now()) {
      card.classList.add("paused");
      const remSec = Math.max(1, Math.ceil((state.pausedUntil - Date.now()) / 1000));
      const m = Math.floor(remSec / 60);
      const s = remSec % 60;
      title.textContent = "Protection is paused";
      sub.textContent = `Resumes automatically in ${m > 0 ? m + "m " : ""}${s}s (v4.15 Timer)`;
      btnText.textContent = "Resume protection now";
      shieldFill.setAttribute("fill", "#df9a2f");
      pauseLabel.textContent = "Paused (" + remSec + "s)";
    } else if (state.protectionEnabled) {
      title.textContent = "Protection is enabled";
      const dnsName = (presets.dnsProviders.find(p => p.providerId === state.selectedDnsProviderId) || { name: "AdGuard DNS" }).name;
      sub.textContent = `Local VPN active • ${dnsName}`;
      btnText.textContent = "Disable protection";
      shieldFill.setAttribute("fill", "#67B279");
      pauseLabel.textContent = "Pause on timer";
    } else {
      card.classList.add("disabled");
      title.textContent = "Protection is disabled";
      sub.textContent = "Ads and trackers are not being filtered";
      btnText.textContent = "Enable protection";
      shieldFill.setAttribute("fill", "#df5454");
      pauseLabel.textContent = "Pause on timer";
    }

    const metrics = state.periodStats[state.selectedPeriod];
    document.getElementById("metricAds").textContent = metrics.ads;
    document.getElementById("metricTrackers").textContent = metrics.trackers;
    document.getElementById("metricData").textContent = metrics.data;

    const dnsObj = presets.dnsProviders.find(p => p.providerId === state.selectedDnsProviderId);
    if (dnsObj) {
      document.getElementById("pillStateDns").textContent = dnsObj.name;
    }
  }

  function renderProtectionTab() {
    const modContainer = document.getElementById("protectionModulesList");
    modContainer.innerHTML = Object.entries(state.modules)
      .map(
        ([key, mod]) => `
        <div class="list-row" data-module-key="${key}">
          <div class="list-row-left">
            <div>
              <div class="list-row-title">${mod.name}</div>
              <div class="list-row-desc">${mod.desc}</div>
            </div>
          </div>
          <div class="ag-switch ${mod.enabled ? "on" : ""}" data-toggle-module="${key}"></div>
        </div>`
      )
      .join("");

    const dnsContainer = document.getElementById("dnsProvidersList");
    dnsContainer.innerHTML = presets.dnsProviders
      .map((p) => {
        const isSelected = p.providerId === state.selectedDnsProviderId;
        const upstream = p.servers?.[0]?.upstreams?.[0] || "System ISP";
        return `
        <div class="list-row" data-select-dns="${p.providerId}">
          <div class="list-row-left">
            <div>
              <div class="list-row-title">${p.name} ${isSelected ? '<span class="badge-pro">Active</span>' : ""}</div>
              <div class="list-row-desc">${p.description}<br><small style="color:var(--text-muted)">Upstream: ${upstream}</small></div>
            </div>
          </div>
          <input type="radio" name="dnsProvider" ${isSelected ? "checked" : ""} style="accent-color: var(--ag-green); width:18px; height:18px;">
        </div>`;
      })
      .join("");

    const groupsContainer = document.getElementById("filterGroupsList");
    groupsContainer.innerHTML = presets.groups
      .map(
        (g) => `
        <div class="list-row">
          <div class="list-row-left">
            <div>
              <div class="list-row-title">${g.name}</div>
              <div class="list-row-desc">${g.description}</div>
            </div>
          </div>
          <div class="ag-switch on"></div>
        </div>`
      )
      .join("");
  }

  function renderAppsTab(query = "") {
    const q = query.trim().toLowerCase();
    const list = document.getElementById("appsManagementList");
    list.innerHTML = state.apps
      .filter((a) => !q || a.name.toLowerCase().includes(q) || a.pkg.toLowerCase().includes(q))
      .map(
        (a, idx) => `
        <div class="list-row" data-app-idx="${idx}">
          <div class="list-row-left">
            <div class="module-icon">${a.name[0]}</div>
            <div>
              <div class="list-row-title">${a.name}</div>
              <div class="list-row-desc">${a.pkg} • <strong>${a.blocked}</strong> blocked</div>
            </div>
          </div>
          <div class="ag-switch ${a.routed ? "on" : ""}" data-toggle-app="${a.pkg}"></div>
        </div>`
      )
      .join("");
  }

  function renderStatsTab() {
    const logContainer = document.getElementById("recentActivityList");
    logContainer.innerHTML = state.recentLogs
      .map(
        (l, idx) => `
        <div class="list-row" data-log-idx="${idx}">
          <div class="list-row-left">
            <div>
              <div class="list-row-title">${l.domain}</div>
              <div class="list-row-desc">${l.app} • ${l.company} • ${l.time}<br><small style="color:var(--text-muted)">Rule: <code>${l.rule}</code></small></div>
            </div>
          </div>
          <span class="status-badge ${l.status}">${l.status}</span>
        </div>`
      )
      .join("");

    const compContainer = document.getElementById("companyStatsList");
    compContainer.innerHTML = state.companies
      .map(
        (c) => `
        <div class="list-row">
          <div class="list-row-left">
            <div>
              <div class="list-row-title">${c.name}</div>
              <div class="list-row-desc">${c.requests} total requests • ${c.blocked} blocked</div>
            </div>
          </div>
          <span style="color:var(--ag-red); font-weight:700; font-size:13px;">${Math.round((c.blocked / c.requests) * 100)}% blocked</span>
        </div>`
      )
      .join("");
  }

  function openModal(title, htmlContent) {
    document.getElementById("modalTitle").textContent = title;
    document.getElementById("modalBody").innerHTML = htmlContent;
    document.getElementById("modalBackdrop").classList.add("open");
  }

  function closeModal() {
    document.getElementById("modalBackdrop").classList.remove("open");
  }

  function openPauseTimerSheet() {
    openModal(
      "Pause protection on a timer (v4.15)",
      `
      <p style="font-size:13.5px; color:var(--text-secondary); margin-bottom:14px;">
        AdGuard for Android v4.15 lets you pause protection temporarily and automatically switches it back on when the timer expires.
      </p>
      <div class="card-list">
        <div class="list-row" data-pause-sec="30"><div class="list-row-title">Pause for 30 seconds</div><span style="color:var(--ag-green)">Select</span></div>
        <div class="list-row" data-pause-sec="120"><div class="list-row-title">Pause for 2 minutes</div><span style="color:var(--ag-green)">Select</span></div>
        <div class="list-row" data-pause-sec="300"><div class="list-row-title">Pause for 5 minutes</div><span style="color:var(--ag-green)">Select</span></div>
      </div>
    `
    );
  }

  function startPauseTimer(seconds) {
    state.pausedUntil = Date.now() + seconds * 1000;
    state.protectionEnabled = false;
    notifyNativeAndroid(false);
    clearInterval(pauseInterval);
    pauseInterval = setInterval(() => {
      if (!state.pausedUntil || Date.now() >= state.pausedUntil) {
        clearInterval(pauseInterval);
        state.pausedUntil = null;
        state.protectionEnabled = true;
        notifyNativeAndroid(true);
        showToast("Timer expired — AdGuard protection resumed automatically!");
      }
      renderHome();
    }, 1000);
    renderHome();
    closeModal();
    showToast(`Protection paused for ${seconds} seconds`);
  }

  function toggleMasterProtection() {
    if (state.pausedUntil) {
      state.pausedUntil = null;
      clearInterval(pauseInterval);
      state.protectionEnabled = true;
    } else {
      state.protectionEnabled = !state.protectionEnabled;
    }
    notifyNativeAndroid(state.protectionEnabled);
    renderHome();
    showToast(state.protectionEnabled ? "AdGuard Local VPN Protection enabled" : "AdGuard Protection disabled");
  }

  // Event Listeners
  document.getElementById("btnShieldOrb").addEventListener("click", toggleMasterProtection);
  document.getElementById("btnMasterToggle").addEventListener("click", toggleMasterProtection);
  document.getElementById("btnPauseTimer").addEventListener("click", openPauseTimerSheet);
  document.getElementById("btnQuickPause").addEventListener("click", openPauseTimerSheet);
  document.getElementById("btnCloseModal").addEventListener("click", closeModal);
  document.getElementById("modalBackdrop").addEventListener("click", (e) => {
    if (e.target.id === "modalBackdrop") closeModal();
  });

  document.getElementById("modalBody").addEventListener("click", (e) => {
    const row = e.target.closest("[data-pause-sec]");
    if (row) {
      startPauseTimer(Number(row.dataset.pauseSec));
    }
  });

  document.getElementById("btnThemeToggle").addEventListener("click", () => {
    const html = document.documentElement;
    const next = html.dataset.theme === "dark" ? "light" : "dark";
    html.dataset.theme = next;
    showToast(`Switched to ${next} theme`);
  });

  document.getElementById("homePeriodChips").addEventListener("click", (e) => {
    const btn = e.target.closest("[data-period]");
    if (!btn) return;
    document.querySelectorAll("#homePeriodChips .period-chip").forEach((c) => c.classList.remove("active"));
    btn.classList.add("active");
    state.selectedPeriod = btn.dataset.period;
    renderHome();
  });

  document.querySelectorAll("[data-open-module]").forEach((card) => {
    card.addEventListener("click", () => {
      document.querySelector('[data-tab="tab-protection"]').click();
    });
  });

  document.getElementById("btnOpenPrivateBrowser").addEventListener("click", () => {
    openModal(
      "AdGuard Private Browser",
      `
      <div style="margin-bottom:12px;">
        <div style="display:flex; gap:8px; margin-bottom:10px;">
          <input type="text" class="search-box" style="margin-bottom:0;" value="https://adguard.com/en/welcome.html" readonly>
        </div>
        <div style="padding:14px; border-radius:14px; background:var(--bg-elevated); font-size:13px; color:var(--text-secondary);">
          <strong style="color:var(--ag-green);">✓ Zero-Trace Session Active</strong><br>
          Ad blocking, WebRTC leak prevention, and Canvas fingerprinting protection are enforced in this PrivateBrowserActivity session.
        </div>
      </div>
    `
    );
  });

  document.getElementById("protectionModulesList").addEventListener("click", (e) => {
    const sw = e.target.closest("[data-toggle-module]");
    if (!sw) return;
    const key = sw.dataset.toggleModule;
    state.modules[key].enabled = !state.modules[key].enabled;
    renderProtectionTab();
    showToast(`${state.modules[key].name}: ${state.modules[key].enabled ? "Enabled" : "Disabled"}`);
  });

  document.getElementById("dnsProvidersList").addEventListener("click", (e) => {
    const row = e.target.closest("[data-select-dns]");
    if (!row) return;
    state.selectedDnsProviderId = Number(row.dataset.selectDns);
    renderProtectionTab();
    renderHome();
    const p = presets.dnsProviders.find((d) => d.providerId === state.selectedDnsProviderId);
    if (p) showToast(`DNS server switched to ${p.name}`);
  });

  document.getElementById("appSearchInput").addEventListener("input", (e) => {
    renderAppsTab(e.target.value);
  });

  document.getElementById("appsManagementList").addEventListener("click", (e) => {
    const sw = e.target.closest("[data-toggle-app]");
    if (!sw) return;
    const app = state.apps.find((a) => a.pkg === sw.dataset.toggleApp);
    if (app) {
      app.routed = !app.routed;
      renderAppsTab(document.getElementById("appSearchInput").value);
      showToast(`${app.name}: ${app.routed ? "Routed through AdGuard VPN" : "Excluded from AdGuard"}`);
    }
  });

  document.querySelectorAll("[data-setting-toggle]").forEach((sw) => {
    sw.addEventListener("click", () => {
      sw.classList.toggle("on");
      showToast("Setting updated");
    });
  });

  // Bottom Navigation Tabs
  document.querySelectorAll(".bottom-nav .nav-item").forEach((btn) => {
    btn.addEventListener("click", () => {
      document.querySelectorAll(".bottom-nav .nav-item").forEach((b) => b.classList.remove("active"));
      document.querySelectorAll(".tab-pane").forEach((p) => p.classList.remove("active"));
      btn.classList.add("active");
      document.getElementById(btn.dataset.tab).classList.add("active");
    });
  });

  // Initial Render
  renderHome();
  renderProtectionTab();
  renderAppsTab();
  renderStatsTab();
})();
