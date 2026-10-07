"use strict";
(() => {
  // ---------- small helpers ----------
  const $ = (id) => document.getElementById(id);
  /** Builds an element. Text is always set with textContent, never parsed as HTML. */
  function h(tag, props, ...children) {
    const el = document.createElement(tag);
    for (const [k, v] of Object.entries(props || {})) {
      if (v === undefined || v === null || v === false) continue;
      if (k === "class") el.className = v;
      else if (k === "text") el.textContent = v;
      else if (k.startsWith("on")) el.addEventListener(k.slice(2), v);
      else if (k === "hidden" || k === "disabled" || k === "checked") el[k] = !!v;
      else el.setAttribute(k, v);
    }
    for (const c of children.flat()) {
      if (c === null || c === undefined || c === false) continue;
      el.append(c instanceof Node ? c : document.createTextNode(String(c)));
    }
    return el;
  }
  const field = (label, value, mono) =>
    h("div", { class: "field" }, h("span", { text: label }), h("span", { class: mono ? "mono" : "", text: value ?? "—" }));
  const mb = (bytes) => (bytes / 1e6).toFixed(1) + " MB";

  let state = null;
  let lastStateText = "";
  let lastSerial = -1;
  let uploading = false;

  // ---------- API ----------
  async function api(method, path, body) {
    const init = { method, headers: {} };
    if (body !== undefined) {
      init.headers["Content-Type"] = "application/json";
      init.body = JSON.stringify(body);
    }
    let res;
    try {
      res = await fetch(path, init);
    } catch (e) {
      setOffline(true);
      throw e;
    }
    setOffline(false);
    const type = res.headers.get("Content-Type") || "";
    const data = type.includes("application/json") ? await res.json() : { ok: res.ok, error: await res.text() };
    return { status: res.status, data };
  }

  /** Runs an action and shows a refusal right away; progress follows through /api/state. */
  async function act(path, body) {
    try {
      const { status, data } = await api("POST", path, body);
      if (!data.ok) toast(data.error || "The phone refused (" + status + ").", true);
      poll(true);
      return data.ok;
    } catch (e) {
      toast("Could not reach the phone: " + e.message, true);
      return false;
    }
  }

  function setOffline(on) { $("offline").hidden = !on; }

  // ---------- toasts ----------
  const recent = new Map();
  function toast(text, isError) {
    if (!text) return;
    const now = Date.now();
    if (recent.has(text) && now - recent.get(text) < 5000) return;
    recent.set(text, now);
    const close = h("button", { "aria-label": "Close", text: "✕" });
    const el = h("div", { class: "toast" + (isError ? " error" : ""), role: isError ? "alert" : "status" }, h("span", { text }), close);
    close.addEventListener("click", () => el.remove());
    $("toasts").append(el);
    setTimeout(() => el.remove(), isError ? 12000 : 5000);
  }

  // ---------- views ----------
  const views = {};
  const tabs = ["Device", "Install", "Apps", "Account", "Logs", "Settings"];
  let current = (location.hash || "#Device").slice(1);
  if (!tabs.includes(current)) current = "Device";

  function buildTabs() {
    const nav = $("tabs");
    nav.replaceChildren(...tabs.map((name) =>
      h("button", { "data-tab": name, text: name, onclick: () => select(name) })));
    for (const name of tabs) {
      const section = h("section", { class: "view", id: "view-" + name });
      $("views").append(section);
      views[name] = section;
    }
    buildDevice(); buildInstall(); buildApps(); buildAccount(); buildLogs(); buildSettings();
    select(current);
  }
  function select(name) {
    current = name;
    history.replaceState(null, "", "#" + name);
    for (const b of $("tabs").children) b.classList.toggle("active", b.dataset.tab === name);
    for (const [k, v] of Object.entries(views)) v.hidden = k !== name;
    if (name === "Logs") pollLogs();
  }

  const isBusy = () => !state || !!state.busy;
  const isReady = () => state && state.connection === "READY" && state.account && !state.busy;
  /** Buttons that start an operation are disabled while one is running. */
  function actionButton(label, onclick, cls, extraDisabled) {
    const b = h("button", { class: cls || "", text: label, onclick });
    b.dataset.act = "1";
    if (extraDisabled) b.dataset.need = extraDisabled;
    return b;
  }
  function refreshButtons() {
    for (const b of document.querySelectorAll("button[data-act]")) {
      let disabled = isBusy() || uploading;
      if (b.dataset.need === "ready") disabled = disabled || !isReady();
      if (b.dataset.need === "connected") disabled = disabled || !state || state.connection !== "READY";
      if (b.dataset.need === "ipa") disabled = disabled || !isReady() || !state.selectedIpa;
      b.disabled = disabled;
    }
  }

  const CONNECTION = {
    DISCONNECTED: "Not connected", DISCOVERING: "Looking for devices", CONNECTING: "Connecting…",
    CONNECTED: "Connected", PAIRING_REQUIRED: "Pairing needed", PAIRING: "Waiting for Trust on the iPhone",
    PAIRED: "Paired", LOCKDOWN_CONNECTED: "Connected, checking pairing", READY: "Ready", ERROR: "Connection failed"
  };

  // Device
  let wirelessInput;
  const dyn = {};
  function buildDevice() {
    const v = views.Device;
    dyn.status = h("div", { class: "card" });
    dyn.found = h("div", { class: "list" });
    wirelessInput = h("input", { type: "text", inputmode: "decimal", placeholder: "iPhone IP, e.g. 192.168.1.23", "aria-label": "iPhone IP address" });
    wirelessInput.addEventListener("keydown", (e) => { if (e.key === "Enter") connectWireless(); });
    dyn.info = h("div", { class: "card", hidden: true });
    v.append(
      dyn.status,
      h("div", { class: "card" },
        h("div", { class: "row spread" }, h("h2", { text: "Devices found" }),
          h("button", { text: "Refresh", onclick: () => act("/api/devices/refresh") })),
        h("p", { class: "muted", text: "Wired: plug the iPhone into the Android phone with a cable. The first time, tap Allow in the USB dialog on the Android phone. Wireless: iPhones already paired with this app and with Wi-Fi sync on show up here by themselves." }),
        dyn.found),
      h("div", { class: "card" },
        h("h2", { text: "Wireless mode (no cable)" }),
        h("p", { class: "muted", text: "Enter the iPhone's address from Settings > Wi-Fi > (i) on the iPhone. It must be on the same network as the Android phone. The iPhone asks to Trust this phone the first time. iOS 27 only pairs wirelessly through Remote Pairing, which this app does not implement yet; there, pair once with a cable." }),
        h("div", { class: "row" }, h("div", { class: "grow" }, wirelessInput), actionButton("Connect", connectWireless, "primary"))),
      dyn.info);
  }
  function connectWireless() { act("/api/connect-wireless", { address: wirelessInput.value.trim() }); }

  function renderDevice() {
    const s = state;
    const connected = s.connection !== "DISCONNECTED" && s.connection !== "ERROR";
    dyn.status.replaceChildren(
      h("div", { class: "row spread" }, h("h2", { text: "Connection" }),
        connected ? h("button", { class: "danger", text: "Disconnect", onclick: () => act("/api/disconnect") }) : null),
      field("State", CONNECTION[s.connection] || s.connection),
      s.transport ? field("Link", s.transport) : null,
      s.pairingHint ? h("div", { class: "hint", text: s.pairingHint }) : null);
    dyn.found.replaceChildren(...(s.discovered.length ? s.discovered.map((d) =>
      h("div", { class: "item" },
        h("div", {}, h("span", { class: "title", text: d.name }),
          h("span", { class: "tag", text: d.kind === "usb" ? "Wired (USB)" : "Wireless" }),
          d.address ? h("div", { class: "muted mono", text: d.address }) : null),
        actionButton("Connect", () => act("/api/connect", { id: d.id }), "primary"))) :
      [h("p", { class: "muted", text: "No iPhone is attached or advertised right now." })]));
    dyn.info.hidden = !s.device;
    if (s.device) {
      const d = s.device;
      dyn.info.replaceChildren(h("h2", { text: d.name }),
        field("Model", d.productType), field("iOS", d.productVersion + " (" + d.buildVersion + ")"),
        field("UDID", d.udid, true), field("Architecture", d.cpuArchitecture),
        d.wifiAddress ? field("Wi-Fi MAC", d.wifiAddress, true) : null);
    }
  }

  // Install
  let fileInput, uploadBar, uploadLabel;
  function buildInstall() {
    const v = views.Install;
    dyn.before = h("div", { class: "card" });
    dyn.sources = h("div", { class: "row" });
    fileInput = h("input", { type: "file", accept: ".ipa,application/octet-stream", "aria-label": "IPA file" });
    uploadBar = h("div", { style: "width:0%" });
    uploadLabel = h("p", { class: "muted" });
    dyn.uploadBox = h("div", { hidden: true }, h("div", { class: "progress" }, uploadBar), uploadLabel);
    dyn.ipa = h("div");
    dyn.progress = h("div", { class: "card", hidden: true });
    dyn.outcome = h("div", { class: "card", hidden: true });
    v.append(dyn.before,
      h("div", { class: "card" }, h("h2", { text: "SideStore + LiveContainer" }),
        h("p", { class: "muted", text: "Downloads the latest official release, signs it with your Apple ID, installs it, and gives SideStore the pairing file so it can refresh itself and your apps on the iPhone with LocalDevVPN — the same setup SideInstaller makes." }),
        dyn.sources),
      h("div", { class: "card" }, h("h2", { text: "Custom IPA" }),
        h("p", { class: "muted", text: "Upload an .ipa from this computer to the phone, check it, then sign and install it." }),
        h("div", { class: "row" }, h("div", { class: "grow" }, fileInput), actionButton("Upload", upload)),
        dyn.uploadBox, dyn.ipa,
        h("div", { class: "row" }, actionButton("Sign and install", () => act("/api/install"), "primary", "ipa"))),
      dyn.progress, dyn.outcome);
  }

  function upload() {
    const file = fileInput.files && fileInput.files[0];
    if (!file) { toast("Choose an .ipa file first.", true); return; }
    if (file.size > 8e9) { toast("That file is larger than 8 GB, more than the phone accepts.", true); return; }
    if (!/\.ipa$/i.test(file.name)) toast("That file does not end in .ipa; the phone will check it anyway.", true);
    const xhr = new XMLHttpRequest();
    xhr.open("POST", "/api/ipa");
    xhr.setRequestHeader("Content-Type", "application/octet-stream");
    uploading = true;
    dyn.uploadBox.hidden = false;
    refreshButtons();
    xhr.upload.onprogress = (e) => {
      if (!e.lengthComputable) return;
      const pct = Math.round((e.loaded / e.total) * 100);
      uploadBar.style.width = pct + "%";
      uploadLabel.textContent = "Uploading " + file.name + ": " + mb(e.loaded) + " of " + mb(e.total);
    };
    const finish = (ok, message) => {
      uploading = false;
      dyn.uploadBox.hidden = true;
      uploadBar.style.width = "0%";
      if (!ok) toast(message, true); else toast("Uploaded " + file.name + "; the phone is checking it.");
      refreshButtons();
      poll(true);
    };
    xhr.onload = () => {
      let data = {};
      try { data = JSON.parse(xhr.responseText); } catch (_) { data = { ok: false, error: xhr.responseText }; }
      finish(xhr.status < 300 && data.ok, data.error || ("Upload failed (" + xhr.status + ")."));
    };
    xhr.onerror = () => finish(false, "The upload broke off. Is the phone still on the network?");
    xhr.send(file);
  }

  const STEPS_SIDESTORE = (o) => [
    "Install LocalDevVPN from the App Store and connect it.",
    (o.special === "SIDESTORE_LIVECONTAINER" ? "Open LiveContainer, then SideStore inside it" : "Open SideStore") + " and sign in with the same Apple ID.",
    "Refresh in SideStore with LocalDevVPN connected, ideally every day, so nothing expires."
  ];

  function renderInstall() {
    const s = state;
    const needs = [];
    if (s.connection !== "READY") needs.push("Connect and pair the iPhone on the Device tab.");
    if (!s.account) needs.push("Sign in with your Apple ID on the Account tab; the signature comes from a real certificate Apple issues to it.");
    dyn.before.hidden = needs.length === 0;
    dyn.before.replaceChildren(h("h2", { text: "Before installing" }), ...needs.map((t) => h("p", { class: "muted", text: t })));
    if (!dyn.sources.childElementCount) {
      dyn.sources.append(...s.sources.map((src, i) =>
        actionButton("Install " + src.title, () => act("/api/install-source", { source: src.id }), i === 0 ? "primary" : "", "ready")));
    }
    const ipa = s.selectedIpa;
    dyn.ipa.replaceChildren(...(ipa ? [field("Name", ipa.name), field("Bundle id", ipa.bundleId, true),
      field("Version", ipa.shortVersion + " (" + ipa.version + ")"), field("Minimum iOS", ipa.minimumOsVersion),
      field("Frameworks", String(ipa.frameworkCount)), field("Extensions", ipa.hasExtensions ? "yes" : "no"),
      field("Size", mb(ipa.sizeBytes))] : [h("p", { class: "muted", text: "No IPA has been chosen yet." })]));
    dyn.progress.hidden = !s.step;
    if (s.step) {
      const pct = s.step.percent;
      dyn.progress.replaceChildren(h("h2", { text: "Progress" }), h("p", { text: s.step.label }),
        h("div", { class: "progress" }, pct === null ? h("div", { class: "indeterminate", style: "position:relative;width:40%" }) : h("div", { style: "width:" + pct + "%" })));
    }
    const o = s.lastOutcome;
    dyn.outcome.hidden = !o;
    if (o) {
      const steps = ["Settings > General > VPN & Device Management: trust your Apple ID's developer app.",
        "Settings > Privacy & Security > Developer Mode: turn it on and restart (iOS 16 and later)."];
      if (o.sideStoreFamily) steps.push(...STEPS_SIDESTORE(o));
      dyn.outcome.replaceChildren(h("h2", { text: "Finish on the iPhone" }),
        h("p", { text: o.name + " is installed as " + o.bundleId + ", valid for " + o.expiresInDays + " days." }),
        h("ol", {}, ...steps.map((t) => h("li", { text: t }))),
        o.sideStoreFamily && !o.pairingHandedOff ? h("p", { class: "error", text: "The pairing file could not be handed to SideStore; see Logs." }) : null);
    }
  }

  // Apps
  function buildApps() {
    dyn.apps = h("div", { class: "list" });
    views.Apps.append(h("div", { class: "card" },
      h("div", { class: "row spread" }, h("h2", { text: "Installed by you" }),
        actionButton("Refresh", () => act("/api/apps/refresh"), "", "connected")),
      dyn.apps));
  }
  function renderApps() {
    const s = state;
    if (s.connection !== "READY") {
      dyn.apps.replaceChildren(h("p", { class: "muted", text: "Connect the iPhone to see its sideloaded apps." }));
      return;
    }
    dyn.apps.replaceChildren(...(s.apps.length ? s.apps.map((a) => h("div", { class: "item" },
      h("div", {}, h("div", { class: "title", text: a.name }), h("div", { class: "muted mono", text: a.bundleId + " · " + a.shortVersion })),
      actionButton("Remove", () => { if (confirm("Remove " + a.name + " from the iPhone? Its data is deleted too.")) act("/api/apps/uninstall", { bundleId: a.bundleId }); }, "danger"))) :
      [h("p", { class: "muted", text: "No sideloaded apps were reported by the device." })]));
  }

  // Account
  let appleIdInput, passwordInput, codeInput;
  function buildAccount() {
    appleIdInput = h("input", { type: "email", autocomplete: "username", placeholder: "Apple ID", "aria-label": "Apple ID" });
    passwordInput = h("input", { type: "password", autocomplete: "current-password", placeholder: "Password", "aria-label": "Password" });
    codeInput = h("input", { type: "text", inputmode: "numeric", autocomplete: "one-time-code", maxlength: "6", placeholder: "6-digit code", "aria-label": "Verification code" });
    const signIn = () => {
      act("/api/signin", { appleId: appleIdInput.value.trim(), password: passwordInput.value }).then(() => { passwordInput.value = ""; });
    };
    passwordInput.addEventListener("keydown", (e) => { if (e.key === "Enter") signIn(); });
    const sendCode = () => act("/api/2fa/code", { code: codeInput.value.trim() }).then((ok) => { if (ok) codeInput.value = ""; });
    codeInput.addEventListener("keydown", (e) => { if (e.key === "Enter") sendCode(); });
    dyn.signin = h("div", { class: "card" }, h("h2", { text: "Sign in with your Apple ID" }),
      h("p", { class: "muted", text: "A free Apple ID works. The password goes from the phone straight to Apple and is not stored." }),
      appleIdInput, passwordInput, h("div", { class: "row" }, actionButton("Sign in", signIn, "primary")));
    dyn.sms = h("div", { class: "row" });
    dyn.twofa = h("div", { class: "card" }, h("h2", { text: "Two-factor code" }),
      h("p", { class: "muted", id: "twofa-hint" }), codeInput,
      h("div", { class: "row" }, actionButton("Verify", sendCode, "primary")), dyn.sms);
    dyn.account = h("div", { class: "card" });
    views.Account.append(dyn.signin, dyn.twofa, dyn.account);
  }
  let appleIdPrefilled = false;
  function renderAccount() {
    const s = state;
    if (!appleIdPrefilled && s.settings.lastAppleId) { appleIdInput.value = s.settings.lastAppleId; appleIdPrefilled = true; }
    dyn.signin.hidden = !!s.account || !!s.twoFactor;
    dyn.twofa.hidden = !s.twoFactor || !!s.account;
    dyn.account.hidden = !s.account;
    if (s.twoFactor) {
      const tf = s.twoFactor;
      const chosen = tf.phoneNumbers.find((n) => n.id === tf.numberId);
      $("twofa-hint").textContent = chosen ? "Enter the code sent by SMS to " + chosen.masked + "."
        : tf.phoneNumbers.length ? "Apple wants a code sent by SMS. Pick the number to send it to."
        : "Enter the code shown on one of your Apple devices.";
      dyn.sms.replaceChildren(...tf.phoneNumbers.map((n) =>
        actionButton("Send SMS to " + n.masked, () => act("/api/2fa/sms", { numberId: n.id }))));
    }
    if (s.account) {
      const teams = s.teams.map((t) => {
        const radio = h("input", { type: "radio", name: "team", checked: t.teamId === s.selectedTeamId });
        radio.addEventListener("change", () => act("/api/team", { teamId: t.teamId }));
        return h("label", { class: "check" }, radio, h("span", { text: t.name + " (" + t.teamId + ")" + (t.free ? " · free" : "") }));
      });
      dyn.account.replaceChildren(h("h2", { text: "Signed in" }), field("Apple ID", s.account.appleId),
        h("p", { class: "muted", text: "Development team" }), ...(teams.length ? teams : [h("p", { class: "muted", text: "This account has no development team." })]),
        h("div", { class: "row" },
          actionButton("Sign out", () => act("/api/signout")),
          actionButton("Revoke certificate", () => {
            if (confirm("Revoke the development certificate? Every app signed with it stops launching until it is installed again.")) act("/api/revoke");
          }, "danger")));
    }
  }

  // Logs
  let logLatest = 0, logTimer = null, follow = true;
  const logLines = [];
  function buildLogs() {
    dyn.logs = h("div", { class: "logs", role: "log" });
    dyn.logs.addEventListener("scroll", () => {
      const el = dyn.logs;
      follow = el.scrollTop + el.clientHeight >= el.scrollHeight - 8;
    });
    views.Logs.append(h("div", { class: "card" },
      h("div", { class: "row spread" }, h("h2", { text: "Logs" }),
        h("div", { class: "row" }, h("button", { text: "Download", onclick: downloadLogs }),
          h("button", { text: "Clear view", onclick: () => { logLines.length = 0; dyn.logs.replaceChildren(); } }))),
      h("p", { class: "muted", text: "Passwords, tokens, keys and serial numbers are removed before a line is logged." }),
      dyn.logs));
  }
  async function pollLogs() {
    clearTimeout(logTimer);
    if (current !== "Logs") return;
    try {
      const { data } = await api("GET", "/api/logs?after=" + logLatest);
      if (data.lines && data.lines.length) {
        logLatest = data.latest;
        const frag = document.createDocumentFragment();
        for (const l of data.lines) {
          const t = new Date(l.at).toLocaleTimeString();
          frag.append(h("div", { class: l.level, text: t + " [" + l.level + "] [" + l.tag + "] " + l.message }));
          logLines.push(l);
        }
        dyn.logs.append(frag);
        while (dyn.logs.childElementCount > 2000) dyn.logs.firstChild.remove();
        if (follow) dyn.logs.scrollTop = dyn.logs.scrollHeight;
      }
    } catch (_) { /* shown by the offline banner */ }
    logTimer = setTimeout(pollLogs, 2000);
  }
  async function downloadLogs() {
    try {
      const res = await fetch("/api/logs/export");
      const blob = await res.blob();
      const a = h("a", { href: URL.createObjectURL(blob), download: "AppleSideload-log.txt" });
      document.body.append(a); a.click(); a.remove();
      setTimeout(() => URL.revokeObjectURL(a.href), 10000);
    } catch (e) { toast("Could not download the log: " + e.message, true); }
  }

  // Settings
  let anisetteInput, wifiCheck;
  function buildSettings() {
    anisetteInput = h("input", { type: "text", placeholder: "https://ani.sidestore.io", "aria-label": "Attestation address" });
    dyn.chips = h("div", { class: "chips" });
    wifiCheck = h("input", { type: "checkbox" });
    wifiCheck.addEventListener("change", () => act("/api/settings", { wifiDiscovery: wifiCheck.checked }));
    const save = () => act("/api/settings", { anisetteAddress: anisetteInput.value.trim() }).then((ok) => { if (ok) toast("Attestation source saved."); });
    views.Settings.append(
      h("div", { class: "card" }, h("h2", { text: "Attestation source" }),
        h("p", { class: "muted", text: "Apple's sign-in needs attestation headers that only Apple's own code can make. The phone asks this server for them; it never sees your Apple ID or password. Leave empty for the default." }),
        h("div", { class: "row" }, h("div", { class: "grow" }, anisetteInput), h("button", { class: "primary", text: "Save", onclick: save })),
        dyn.chips),
      h("div", { class: "card" }, h("h2", { text: "Discovery" }),
        h("label", { class: "check" }, wifiCheck, h("span", { text: "Look for iPhones over Wi-Fi" })),
        h("p", { class: "muted", text: "Only iPhones already paired with this app and with Wi-Fi sync on advertise themselves." })),
      h("div", { class: "card" }, h("h2", { text: "Web controller" }),
        h("p", { class: "muted", text: "To stop the web controller, turn it off under Settings > Web controller in the app or tap Stop in its notification." })));
  }
  let anisettePrefilled = false;
  function renderSettings() {
    const st = state.settings;
    if (!anisettePrefilled) { anisetteInput.value = st.anisetteAddress; anisettePrefilled = true; }
    if (document.activeElement !== wifiCheck) wifiCheck.checked = st.wifiDiscovery;
    dyn.chips.replaceChildren(...state.anisetteServers.map((srv) =>
      h("button", { class: srv.address === st.effectiveAnisetteAddress ? "on" : "", text: srv.name, title: srv.address,
        onclick: () => { anisetteInput.value = srv.address; act("/api/settings", { anisetteAddress: srv.address }); } })));
    if (!wirelessInput.value && st.lastWirelessAddress) wirelessInput.value = st.lastWirelessAddress;
  }

  // ---------- state loop ----------
  function render() {
    const s = state;
    const pill = $("status-pill");
    pill.textContent = s.device ? s.device.name + " · " + (CONNECTION[s.connection] || s.connection) : (CONNECTION[s.connection] || s.connection);
    pill.className = "pill" + (s.connection === "READY" ? " ready" : s.connection === "ERROR" ? " error" : s.busy ? " working" : "");
    $("busy").hidden = !s.busy;
    $("busy-label").textContent = s.busy || "";
    renderDevice(); renderInstall(); renderApps(); renderAccount(); renderSettings();
    refreshButtons();
  }

  let pollTimer = null;
  async function poll(soon) {
    clearTimeout(pollTimer);
    if (soon) { pollTimer = setTimeout(() => poll(false), 250); return; }
    try {
      const { data } = await api("GET", "/api/state");
      const text = JSON.stringify(data);
      if (lastSerial >= 0 && data.messageSerial !== lastSerial) {
        if (data.lastMessage) toast(data.lastMessage, data.lastMessageIsError);
      }
      lastSerial = data.messageSerial;
      if (text !== lastStateText) {
        lastStateText = text;
        state = data;
        render();
      }
    } catch (_) { /* shown by the offline banner */ }
    pollTimer = setTimeout(() => poll(false), state && state.busy ? 1000 : 2000);
  }

  buildTabs();
  poll(false);
})();
