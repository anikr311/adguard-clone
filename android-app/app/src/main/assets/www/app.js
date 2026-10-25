/* AdLocko UI logic. All numbers come from the native engine via the bridge;
   there are no demo or preset values — without the bridge everything is 0. */
(function () {
  "use strict";
  var bridge = window.AndroidBridge || null;
  var state = { on: false, queries: 0, blocked: 0 };

  var $ = function (id) { return document.getElementById(id); };

  // ---------- navigation ----------
  var tabs = document.querySelectorAll(".tab");
  tabs.forEach(function (tab) {
    tab.addEventListener("click", function () {
      tabs.forEach(function (t) { t.classList.remove("active"); });
      tab.classList.add("active");
      document.querySelectorAll(".screen").forEach(function (s) { s.classList.remove("active"); });
      $("screen-" + tab.getAttribute("data-screen")).classList.add("active");
      if (tab.getAttribute("data-screen") === "activity") refreshLog();
      refreshAll();
    });
  });

  // ---------- protection toggle ----------
  function setProtection(enabled) {
    if (bridge) {
      bridge.setProtectionEnabled(enabled);
      // Native calls back through onProtectionStateChanged after the real
      // VPN consent outcome is known. Do not fake the state here.
    } else {
      applyState(enabled);
    }
  }
  $("orbBtn").addEventListener("click", function () { setProtection(!state.on); });
  $("toggleBtn").addEventListener("click", function () { setProtection(!state.on); });

  function applyState(on) {
    state.on = on;
    document.body.classList.toggle("protection-on", on);
    $("statusTitle").textContent = on ? "Protection is on" : "Protection is off";
    $("statusSub").textContent = on
      ? "DNS filtering is running on this device"
      : "Tap the shield to start DNS filtering";
    $("toggleBtn").textContent = on ? "Turn off protection" : "Turn on protection";
  }

  // ---------- live stats ----------
  function refreshStats() {
    if (!bridge) return;
    try {
      var s = JSON.parse(bridge.getStats());
      state.queries = s.queries || 0;
      state.blocked = s.blocked || 0;
      $("statBlocked").textContent = state.blocked.toLocaleString();
      $("statQueries").textContent = state.queries.toLocaleString();
      var rate = state.queries > 0 ? Math.round((state.blocked / state.queries) * 100) : 0;
      $("statRate").textContent = rate + "%";
    } catch (e) { /* keep last shown values */ }
  }

  function refreshLog() {
    if (!bridge) return;
    try {
      var items = JSON.parse(bridge.getBlockedLog());
      var list = $("logList");
      list.innerHTML = "";
      items.forEach(function (item) {
        var row = document.createElement("div");
        row.className = "log-row";
        var d = document.createElement("span");
        d.className = "log-domain";
        d.textContent = item.domain;
        var t = document.createElement("span");
        t.className = "log-time";
        t.textContent = item.time;
        row.appendChild(d);
        row.appendChild(t);
        list.appendChild(row);
      });
      $("logEmpty").style.display = items.length ? "none" : "block";
    } catch (e) { /* ignore */ }
  }

  function refreshBlocklistInfo() {
    if (!bridge) return;
    try {
      var info = JSON.parse(bridge.getBlocklistInfo());
      var count = info.updatedCount > 0 ? info.updatedCount : info.bundledCount;
      $("blocklistCount").textContent = count.toLocaleString() + " domains";
      if (info.updatedAt > 0) {
        var d = new Date(info.updatedAt);
        $("blocklistInfo").textContent =
          "Updated list in use, downloaded " + d.toLocaleDateString() + " " + d.toLocaleTimeString() + ".";
      } else {
        $("blocklistInfo").textContent =
          "Built-in list of ad, tracker and malware domains (StevenBlack unified).";
      }
    } catch (e) { /* ignore */ }
  }

  function refreshAll() {
    refreshStats();
    refreshBlocklistInfo();
  }

  // ---------- DNS provider ----------
  var dnsRadios = document.querySelectorAll('input[name="dns"]');
  function syncDnsRadios() {
    var current = bridge ? bridge.getDnsProvider() : "adguard";
    dnsRadios.forEach(function (r) { r.checked = r.value === current; });
  }
  dnsRadios.forEach(function (r) {
    r.addEventListener("change", function () {
      if (bridge && r.checked) bridge.setDnsProvider(r.value);
    });
  });

  // ---------- blocklist update ----------
  $("updateListBtn").addEventListener("click", function () {
    if (!bridge) return;
    this.disabled = true;
    this.textContent = "Updating…";
    bridge.updateBlocklist();
  });

  // ---------- native callbacks ----------
  window.AdLockoUI = {
    onProtectionStateChanged: function (enabled) {
      applyState(Boolean(enabled));
      refreshAll();
    },
    onBlocklistUpdated: function (success, count) {
      var btn = $("updateListBtn");
      btn.disabled = false;
      btn.textContent = "Update blocklist";
      refreshBlocklistInfo();
    }
  };

  // ---------- init ----------
  applyState(bridge ? Boolean(bridge.isProtectionEnabled()) : false);
  syncDnsRadios();
  refreshAll();
  setInterval(function () {
    refreshStats();
    if ($("screen-activity").classList.contains("active")) refreshLog();
  }, 3000);
})();
