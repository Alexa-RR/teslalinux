// teslalinux: Settings > Grok (services/grok/grok.py, docs/GROK.md). The car's Grok assistant drives
// Waze through a remote MCP server on this device, reached through a Cloudflare Tunnel on the
// driver's own domain and Cloudflare Access: the switch (off by default), the tunnel's state, the
// connector address for grok.com, the automatic setup with a Cloudflare API token (used once, never
// stored), the manual settings (tunnel token, Access team domain, AUD tag), and the log of what Grok
// asked for. SettingsController installs it (install(controller)): one section and its search entries.
(function (global) {
  "use strict";

  const TAHtml = global.TAHtml;
  const UI = TAHtml.SettingsUI;
  const { h, icon } = UI;

  const API = {
    state: null,
    async get() {
      const r = await fetch("/api/grok", { cache: "no-store" });
      if (!r.ok) throw new Error("HTTP " + r.status);
      this.state = await r.json();
      return this.state;
    },
    async post(route, body) {
      let r;
      try {
        r = await fetch("/api/" + route, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body || {}) });
      } catch (_e) {
        throw UI.userError("The device did not answer.");
      }
      let out = {};
      try {
        out = await r.json();
      } catch (_e) {
        /* an empty or HTML error page */
      }
      if (!r.ok || !out.ok) throw UI.userError(out.message || "Not saved (HTTP " + r.status + ").");
      return out;
    },
  };
  TAHtml.TLGrok = API;

  const TOOL_NAMES = { navigate_to: "Navigate", search_places: "Search", trip_status: "Trip status", stop_navigation: "Stop",
    report: "Report", report_types: "Report types" };

  function setUpAlready(s) {
    return Boolean(s.tokenSet && s.teamDomain && s.aud);
  }

  function argsText(a) {
    if (!a || typeof a !== "object") return "";
    if (a.category) return [a.category, a.kind, a.option].filter(Boolean).join(" · ");
    if (a.destination) return a.destination;
    if (a.query) return a.query;
    if (a.resultId) return "a search result";
    if (a.latitude != null) return (a.name ? a.name + " · " : "") + a.latitude + ", " + a.longitude;
    return "";
  }

  /*
   * The Connect Cloudflare button in Cloudflare's look (the rest of the section keeps the settings
   * page's): Cloudflare's orange, a white bold label, their slightly rounded corners, 80 px tall. The
   * orange is a little darker than the brand's #F38020 (2.65:1 with white): #E06D10 gives the white
   * 22 px bold label 3.3:1, above WCAG's 3:1 for large text; pressed #C85A0A (4.3:1). Text only: Cloudflare's trademark guidelines (cloudflare.com/trademark) allow the word
   * "Cloudflare" to name its service but require written permission for its logos, so no cloud mark
   * and no Cloudflare fonts or other assets. The styles live here so the section needs no
   * stylesheet hook of its own.
   */
  // Every rule carries #page-settings too: the page's own "#page-settings button" rule (css/settings.css)
  // is more specific than a class alone and drew this button as a plain grey one.
  const CF = (sel) => "#page-settings " + sel + "," + sel;
  const CF_STYLE = CF(".grok-cf-btn") + "{display:inline-flex;align-items:center;justify-content:center;gap:10px;"
    + "min-height:56px;padding:0 24px;border:0;border-radius:6px;background:#e06d10;color:#fff;"
    + "font:700 18px/1.2 inherit;letter-spacing:.01em;white-space:nowrap;box-shadow:0 1px 2px rgba(0,0,0,.25);"
    + "cursor:pointer;touch-action:manipulation}"
    + CF(".grok-cf-btn--wide") + "{display:flex;width:100%;min-height:64px;font-size:20px}"
    + CF(".grok-cf-btn:active:not(:disabled)") + "{background:#c85a0a;box-shadow:inset 0 2px 4px rgba(0,0,0,.25);transform:translateY(1px)}"
    + CF(".grok-cf-btn:focus-visible") + "{outline:3px solid #fbad41;outline-offset:3px}"
    + CF(".grok-cf-btn:disabled") + "{background:#b9b9b9;color:#fff;box-shadow:none;cursor:not-allowed}"
    + CF(".grok-cf-btn.is-busy") + "{background:#c85a0a}";

  function cfButton(label, onClick, wide) {
    const doc = global.document;
    if (!doc.getElementById("grok-cf-style")) {
      doc.head.append(h("style", { id: "grok-cf-style" }, CF_STYLE));
    }
    return h("button", { class: "grok-cf-btn" + (wide ? " grok-cf-btn--wide" : ""), type: "button", onclick: onClick || null }, label);
  }

  /**
   * Connect Cloudflare: the device starts a sign-in (OAuth, PKCE) and this sheet opens Cloudflare's
   * consent page, here or on a phone on the hotspot (its QR code is the device's short link). Cloudflare
   * sends the browser back to the device, which sets everything up and revokes the sign-in; this sheet
   * watches /api/grok for the result.
   */
  // Laid out like Settings > Gates > Link (js/gates/gates.css: .gates-link, .gates-qr, .gates-steps,
  // .gates-link-status, .gates-fine): the code to scan on the left, the steps and the sign-in's state on
  // the right. The sign-in starts as the sheet opens; until its code arrives the code's place shows a
  // spinner on the page's own colours, never an empty white box.
  const QR_WAIT_STYLE = "#page-settings .gates-qr.grok-qr-wait{display:grid;place-items:center;background:var(--track);box-shadow:none}"
    + "#page-settings .gates-qr.grok-qr-wait .spin{width:36px;height:36px;border-width:3.5px}"
    + "#page-settings .grok-cf-here{margin-top:4px}";

  async function connectSheet(ctrl, token, st) {
    const doc = global.document;
    if (!doc.getElementById("grok-qr-style")) doc.head.append(h("style", { id: "grok-qr-style" }, QR_WAIT_STYLE));
    const status = h("div", { class: "gates-link-status", role: "status", "aria-live": "polite" });
    const qr = h("div", { class: "gates-qr", "aria-label": "Sign-in link for a phone on this car's Wi-Fi" });
    const again = UI.button("New code", null, "btn--primary", "refresh");
    again.hidden = true;
    const here = cfButton("Continue with Cloudflare here", null, false);
    here.classList.add("grok-cf-here");
    here.disabled = true;
    const steps = h(
      "ol",
      { class: "gates-steps" },
      h("li", null, "Join this car's Wi-Fi with your phone."),
      h("li", null, "Scan this code and sign in to Cloudflare."),
      h("li", null, "Allow Teslalinux. The device sets everything up by itself."),
    );
    const sheet = UI.openSheet({
      title: "Connect Cloudflare",
      body: [h("div", { class: "gates-link" }, qr, h("div", null, steps, status, again, here)),
        h("p", { class: "gates-fine" }, "Cloudflare lets the device in once: it makes the tunnel, the address and the Access sign-in on "
          + "your domain, then gives the permission back. Only the tunnel's token stays on the device. With several domains, "
          + "you pick one after signing in.")],
    });
    let timer = null;
    let tick = null;
    let url = null;
    let deadline = 0;
    let since = 0;
    const stop = () => {
      global.clearTimeout(timer);
      global.clearInterval(tick);
    };
    const loading = (text) => {
      qr.classList.add("grok-qr-wait");
      qr.classList.remove("is-stale");
      qr.replaceChildren(h("span", { class: "spin" }));
      status.className = "gates-link-status is-wait";
      status.replaceChildren(h("span", { class: "spin" }), h("span", null, text));
    };
    const failed = (text) => {
      stop();
      qr.classList.add("is-stale");
      status.className = "gates-link-status is-err";
      status.replaceChildren(icon("error_outline"), h("span", null, text));
      again.hidden = false;
      here.disabled = true;
    };
    const countdown = () => {
      const s = Math.max(0, Math.round(deadline - Date.now() / 1000));
      if (s === 0) return failed("The code expired. Make a new one.");
      status.className = "gates-link-status is-wait";
      status.replaceChildren(h("span", { class: "spin" }),
        h("span", null, "Waiting for the sign-in… " + Math.floor(s / 60) + ":" + String(s % 60).padStart(2, "0") + " left"));
    };
    const watch = async () => {
      let s = null;
      try {
        s = await API.get();
      } catch (_e) {
        s = null;
      }
      const last = s && s.connect && s.connect.last;
      if (last && last.at >= since) {
        if (!last.ok) return failed(last.message);
        stop();
        status.className = "gates-link-status is-ok";
        status.replaceChildren(icon("check_circle"), h("span", null, last.message));
        global.setTimeout(() => sheet.finish(true), 1500);
        return;
      }
      timer = global.setTimeout(watch, 2000);
    };
    const start = async () => {
      stop();
      again.hidden = true;
      here.disabled = true;
      loading("Making a code…");
      try {
        const out = await API.post("grokOAuthStart", { domain: "" });
        since = Date.now() / 1000 - 1;
        url = out.url;
        deadline = Date.now() / 1000 + (out.expiresIn || 600);
        qr.classList.remove("grok-qr-wait");
        if (out.qr) qr.innerHTML = out.qr;
        else qr.replaceChildren(h("span", { class: "gates-fine" }, "No code: use the button."));
        here.disabled = false;
        countdown();
        tick = global.setInterval(countdown, 1000);
        timer = global.setTimeout(watch, 2000);
      } catch (e) {
        qr.classList.remove("grok-qr-wait");
        qr.replaceChildren();
        failed(e.userMessage || String(e.message || e));
      }
    };
    again.addEventListener("click", start);
    here.addEventListener("click", () => {
      if (url) global.open(url, "_blank", "noopener");
    });
    start();
    await sheet;
    stop();
    if (token === ctrl._renderToken) ctrl._rerender();
  }

  /** Advanced: the automatic setup with an API token: the driver's domain, the hostname, who may sign in */
  async function setupSheet(ctrl, token, st) {
    const field = (id, label, type, placeholder, help, value) => {
      const input = h("input", { id, type, placeholder: placeholder || "", class: "settings-field-input", autocomplete: type === "password" ? "new-password" : "off",
        spellcheck: "false", autocapitalize: "off", autocorrect: "off" });
      input.value = value || "";
      return { input, el: h("div", { class: "field" }, h("label", { for: id }, label), input, help ? h("div", { class: "field-help" }, help) : null) };
    };
    const s = st.settings;
    const domain = field("grok-domain", "Your domain on Cloudflare", "text", "example.com", null, s.hostname ? s.hostname.split(".").slice(-2).join(".") : "");
    const host = field("grok-host", "Hostname for the car (optional)", "text", "mcp.example.com", "Empty: mcp. in front of your domain.", s.hostname || "");
    const email = field("grok-email", "Your email address", "email", "you@example.com", "Only this address can sign in when Grok connects.", s.email || "");
    const tok = field("grok-token", "Cloudflare API token", "password", "", null, "");
    const perms = h("ul", { class: "grok-perms" }, (st.tokenPermissions || []).map((p) => h("li", null, p)));
    const err = h("div", { class: "field-err", "aria-live": "assertive" });
    const steps = h("ol", { class: "grok-steps" });
    const go = h("button", { class: "btn btn--primary", type: "button" }, "Set up");
    const sheet = UI.openSheet({
      title: "Set up with Cloudflare",
      body: [
        h("p", { class: "group-note" }, "The device makes the tunnel, the address and the Access sign-in in your Cloudflare account. The token is used for this once and is not kept."),
        domain.el, host.el, email.el, tok.el,
        h("div", { class: "field-help" }, "Make a custom API token in Cloudflare (My Profile > API Tokens) with exactly these permissions, for your account and this domain:"),
        perms, err, steps,
      ],
      actions: [h("button", { class: "btn", type: "button", onclick: () => sheet.finish(null) }, "Cancel"), go],
    });
    go.addEventListener("click", async () => {
      err.textContent = "";
      steps.replaceChildren();
      if (!domain.input.value.trim() || !email.input.value.trim() || !tok.input.value.trim()) {
        err.textContent = "Fill in the domain, your email address and the token.";
        return;
      }
      go.disabled = true;
      go.replaceChildren(h("span", { class: "spin" }), h("span", null, "Setting up…"));
      try {
        const out = await API.post("grokSetup", { domain: domain.input.value.trim(), hostname: host.input.value.trim(), email: email.input.value.trim(), apiToken: tok.input.value.trim() });
        tok.input.value = "";
        (out.steps || []).forEach((t) => steps.append(h("li", null, t)));
        steps.append(h("li", { class: "is-ok" }, out.message));
        if (out.note) steps.append(h("li", null, out.note));
        go.replaceChildren(h("span", null, "Done"));
        go.disabled = false;
        go.onclick = () => sheet.finish(true);
      } catch (e) {
        err.textContent = e.userMessage || String(e.message || e);
        go.disabled = false;
        go.replaceChildren(h("span", null, "Try again"));
      }
    });
    await sheet;
    tok.input.value = "";
    if (token === ctrl._renderToken) ctrl._rerender();
  }

  async function render(token) {
    let st;
    try {
      st = await API.get();
    } catch (_e) {
      return [UI.group("Grok", [UI.warnbox("The Grok service is not running on the device. Restart the device, or update it if this keeps happening.", "error_outline")])];
    }
    const s = st.settings;
    const t = st.tunnel || {};
    const ready = setUpAlready(s);
    const groups = [];

    // on / off and the state
    const sw = UI.row({ id: "grok.enabled", title: "Let Grok control Waze", detail: ready ? "Navigate, stops, routes, search, reports, Waze's sound and map, the trip" : "Set it up first (below)",
      more: ["Grok in the car reaches this device through your own Cloudflare domain: it can do in Waze what Waze's car screen offers (navigate, add a stop, choose a route, search, report, its sound and map buttons) and read what Waze shows (the trip, the next turn, alerts, the speed), the car's position and the device's connection. Gates only with the switch below.",
        "Off, the tunnel is stopped and the device is not reachable from the internet at all."] });
    const tog = UI.toggle(s.enabled, async (v) => {
      const ok = await UI.save(sw, () => API.post("grokSettings", { enabled: v }), v ? "On" : "Off");
      if (ok && token === this._renderToken) this._rerender();
      return ok;
    }, "Let Grok control Waze");
    tog.querySelector("input").disabled = !ready && !s.enabled;
    UI.setControl(sw, tog);
    const facts = UI.facts([
      ["Tunnel", !s.enabled ? "Off" : t.connected ? "Connected (" + t.connections + ")" : t.unit === "active" ? "Connecting…" : "Not running", !s.enabled ? null : t.connected ? "ok" : "bad"],
      st.endpoint ? ["Connector address", st.endpoint] : null,
      ["Last request", st.lastRequest ? UI.fmt.ago(st.lastRequest.t) + (st.lastRequest.who ? " · " + st.lastRequest.who : "") + (st.lastRequest.ok ? "" : " · refused (" + st.lastRequest.status + ")") : "None yet",
        st.lastRequest && !st.lastRequest.ok ? "warn" : null],
      ["Waze interface", st.interface === "car" ? "Car (every tool)" : "Phone (navigate only; Car mode for the rest)"],
    ]);
    // gates: a physical action, so a switch of its own, off until the driver turns it on
    const gates = UI.row({ id: "grok.gates", title: "Let Grok open gates", detail: s.gates ? "On: Grok may open the PalGate gates shown on the map" : "Off",
      more: ["With this on, Grok can open a PalGate gate you show on the map (Settings > Gates), when you ask it to: it has to confirm each open, and every open is in the log below.",
        "Off, Grok can list the gates but never open one."] });
    UI.setControl(gates, UI.toggle(!!s.gates, async (v) => {
      const ok = await UI.save(gates, () => API.post("grokSettings", { gates: v }), v ? "On" : "Off");
      if (ok && token === this._renderToken) this._rerender();
      return ok;
    }, "Let Grok open gates"));
    groups.push(UI.group("Grok remote control", [sw, gates, facts],
      ready ? "On grok.com: Connectors > New Connector > Custom, and the connector address above." : null));

    // the automatic setup
    const cn = st.connect || {};
    const connect = UI.row({ id: "grok.connect", title: ready ? "Connect Cloudflare again" : "Connect Cloudflare",
      detail: cn.available ? "Sign in once; the device does the rest" : "Not available in this build (no Cloudflare OAuth client id): use Advanced" });
    const cbtn = cfButton("Connect", () => connectSheet(this, token, st));
    cbtn.disabled = !cn.available;
    UI.setControl(connect, cbtn);
    if (cn.last && !cn.last.ok) UI.status(connect).err(cn.last.message);
    const auto = UI.row({ id: "grok.setup", title: "Advanced: with an API token", detail: "Your domain and a Cloudflare API token you make" });
    UI.setControl(auto, UI.button("Set up", () => setupSheet(this, token, st), null, "vpn_key"));
    groups.push(UI.group("Setup", [connect, auto], "Needs a domain on your Cloudflare account and Zero Trust turned on there (its Free plan). docs/GROK.md has the steps by hand."));

    // by hand
    const tokRow = UI.row({ id: "grok.token", title: "Tunnel token", detail: s.tokenSet ? "Stored on the device, root only" + (s.tunnelId ? " · tunnel " + s.tunnelId.slice(0, 8) : "") : "Not set" });
    UI.setControl(tokRow, UI.tapValue(s.tokenSet ? "Replace" : "Enter", async () => {
      const v = await UI.editText({ title: "Tunnel token", label: "The tunnel's token (the long eyJ… text in its install command)", type: "password", maxLength: 4096,
        validate: (x) => (/^[A-Za-z0-9+/=_-]{40,4096}$/.test(x.trim()) ? "" : "That is not a tunnel token") });
      if (v == null) return;
      const ok = await UI.save(tokRow, () => API.post("grokToken", { token: v.trim() }), "Stored");
      if (ok && token === this._renderToken) this._rerender();
    }));
    const teamRow = UI.row({ id: "grok.team", title: "Access team domain", detail: s.teamDomain || "Not set" });
    UI.setControl(teamRow, UI.tapValue(s.teamDomain ? "Change" : "Enter", async () => {
      const v = await UI.editText({ title: "Access team domain", value: s.teamDomain, placeholder: "https://yourteam.cloudflareaccess.com", help: "Zero Trust > Settings: your team name. The name alone is enough.", maxLength: 200 });
      if (v == null) return;
      const ok = await UI.save(teamRow, () => API.post("grokSettings", { teamDomain: v.trim() }));
      if (ok && token === this._renderToken) this._rerender();
    }));
    const audRow = UI.row({ id: "grok.aud", title: "Access AUD tag", detail: s.aud ? s.aud.slice(0, 12) + "…" : "Not set" });
    UI.setControl(audRow, UI.tapValue(s.aud ? "Change" : "Enter", async () => {
      const v = await UI.editText({ title: "Access AUD tag", value: s.aud, help: "The Access application's Application Audience (AUD) Tag (Basic information).", maxLength: 64,
        transform: (x) => x.trim().toLowerCase(), validate: (x) => (!x || /^[0-9a-f]{64}$/.test(x) ? "" : "64 letters and digits (0-9, a-f)") });
      if (v == null) return;
      const ok = await UI.save(audRow, () => API.post("grokSettings", { aud: v }));
      if (ok && token === this._renderToken) this._rerender();
    }));
    const hostRow = UI.row({ id: "grok.host", title: "Hostname", detail: s.hostname || "Not set" });
    UI.setControl(hostRow, UI.tapValue(s.hostname ? "Change" : "Enter", async () => {
      const v = await UI.editText({ title: "Hostname", value: s.hostname, placeholder: "mcp.example.com", help: "The tunnel's public hostname.", maxLength: 253,
        transform: (x) => x.trim().toLowerCase() });
      if (v == null) return;
      const ok = await UI.save(hostRow, () => API.post("grokSettings", { hostname: v }));
      if (ok && token === this._renderToken) this._rerender();
    }));
    const kids = [tokRow, teamRow, audRow, hostRow];
    if (s.tokenSet) {
      const rm = UI.row({ id: "grok.forget", title: "Remove the token", detail: "The tunnel stops; Cloudflare keeps the tunnel until you delete it there" });
      UI.setControl(rm, UI.armed("Remove", "Tap again to remove", async (b) => {
        b.disabled = true;
        const ok = await UI.save(rm, () => API.post("grokToken", { clear: true }), "Removed");
        if (ok && token === this._renderToken) this._rerender();
        else b.disabled = false;
      }));
      kids.push(rm);
    }
    const [more, body] = UI.disclosure("By hand", UI.group(null, kids), false);
    groups.push(h("div", { class: "grok-manual" }, more, body));

    // what Grok did
    const acts = (st.actions || []).slice(0, 15).map((a) =>
      UI.listItem(a.ok ? "check_circle" : "error_outline", (TOOL_NAMES[a.tool] || a.tool) + (argsText(a.args) ? ": " + argsText(a.args) : ""),
        new Date(a.t * 1000).toLocaleString([], { dateStyle: "short", timeStyle: "short" }) + " · " + (a.result || "")));
    groups.push(UI.group("What Grok asked for", acts.length ? acts : [UI.row({ id: "grok.none", title: "Nothing yet" })],
      "Every request, kept on the device. PalGate gates are not offered to Grok."));
    return groups;
  }

  TAHtml.SettingsGrok = {
    install(ctrl) {
      const at = ctrl.sections.findIndex((x) => x.id === "gates");
      const after = at >= 0 ? at : ctrl.sections.findIndex((x) => x.id === "waze");
      ctrl.sections.splice(after + 1, 0, {
        id: "grok",
        title: "Grok",
        icon: "assistant",
        render,
        railValue() {
          const st = API.state;
          if (!st) return "";
          if (!st.settings.enabled) return "Off";
          return st.tunnel && st.tunnel.connected ? "Connected" : "Not connected";
        },
      });
      ctrl.index.push(
        ["grok.enabled", "grok", "Let Grok control Waze", "grok assistant voice remote mcp control connector ai"],
        ["grok.gates", "grok", "Let Grok open gates", "grok gates palgate open barrier remote"],
        ["grok.connect", "grok", "Connect Cloudflare", "grok cloudflare connect sign in login oauth setup domain"],
        ["grok.setup", "grok", "Set up Grok with an API token", "grok cloudflare tunnel domain access api token setup advanced"],
        ["grok.token", "grok", "Grok tunnel token", "grok cloudflare tunnel token cloudflared"],
      );
    },
  };
})(window);
