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

  const TOOL_NAMES = { navigate_to: "Navigate", search_places: "Search", trip_status: "Trip status", stop_navigation: "Stop" };

  function setUpAlready(s) {
    return Boolean(s.tokenSet && s.teamDomain && s.aud);
  }

  function argsText(a) {
    if (!a || typeof a !== "object") return "";
    if (a.destination) return a.destination;
    if (a.query) return a.query;
    if (a.resultId) return "a search result";
    if (a.latitude != null) return (a.name ? a.name + " · " : "") + a.latitude + ", " + a.longitude;
    return "";
  }

  /** the automatic setup: the driver's domain, the hostname in it, who may sign in, and an API token */
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
    const sw = UI.row({ id: "grok.enabled", title: "Let Grok control Waze", detail: ready ? "Navigate, search places, read the trip, stop" : "Set it up first (below)",
      more: ["Grok in the car reaches this device through your own Cloudflare domain: it can start and stop Waze's navigation, search places with Waze, and read the trip. Nothing else.",
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
    groups.push(UI.group("Grok remote control", [sw, facts],
      ready ? "On grok.com: Connectors > New Connector > Custom, and the connector address above." : null));

    // the automatic setup
    const auto = UI.row({ id: "grok.setup", title: ready ? "Set up again" : "Set up with Cloudflare", detail: "Your domain and a Cloudflare API token: the device does the rest" });
    UI.setControl(auto, UI.button(ready ? "Set up again" : "Set up", () => setupSheet(this, token, st), ready ? null : "btn--primary", "cloud"));
    groups.push(UI.group("Setup", [auto], "Needs a domain on your Cloudflare account and Zero Trust turned on there (its Free plan). docs/GROK.md has the steps by hand."));

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
        ["grok.setup", "grok", "Set up Grok with Cloudflare", "grok cloudflare tunnel domain access token setup"],
        ["grok.token", "grok", "Grok tunnel token", "grok cloudflare tunnel token cloudflared"],
      );
    },
  };
})(window);
