// teslalinux: PalGate gates on the car page (services/gates/gates.py through /api/gates*,
// docs/GATES.md; README.teslalinux.md, "Gates"). A gate button stands among the page's own map
// buttons (#home-controls, both interfaces): with one gate shown it opens that gate at once, with
// more it opens a tray of them, each a one-tap open. Every open shows what happens (Sending,
// Opened, or why it failed) on the button or row and in a notice at the top; an auto-open the
// device makes near a gate shows the same notice (the events long poll). The settings page's
// Gates section (js/settings/settings-gates.js) uses this module's API calls and its state.
(function (global) {
  "use strict";

  const doc = global.document;
  const BASE = "/api";
  const STATE_EVERY_MS = 60000;
  const NOTICE_MS = 4500;

  /** {status, body}; body is the JSON answer or null; never throws */
  async function call(method, route, payload) {
    try {
      const r = await fetch(BASE + "/" + route, {
        method,
        cache: "no-store",
        credentials: "same-origin",
        headers: payload ? { "Content-Type": "application/json" } : undefined,
        body: payload ? JSON.stringify(payload) : undefined,
      });
      let body = null;
      try {
        body = await r.json();
      } catch (_e) {
        body = null;
      }
      return { status: r.status, body };
    } catch (_e) {
      return { status: 0, body: null };
    }
  }

  /** the reason of a failed answer, in words for the driver */
  function why(res) {
    if (res.body && res.body.message) return String(res.body.message);
    if (res.status === 0) return "The device did not answer.";
    if (res.status === 503) return "The gates service is not running.";
    return "Error " + res.status + ".";
  }

  const icon = (name, cls) => {
    const s = doc.createElement("span");
    s.className = "material-symbols-rounded" + (cls ? " " + cls : "");
    s.setAttribute("aria-hidden", "true");
    s.textContent = name;
    return s;
  };

  function el(tag, cls, text) {
    const e = doc.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }

  const gateName = (g) => (g && (g.label || g.name)) || "Gate";

  class GatesController {
    constructor() {
      this.state = null;
      this.listeners = new Set();
      this.button = null;
      this.tray = null;
      this.notice = null;
      this.seq = null;
      this.mine = new Set(); // events of opens this page asked for (their notice is already shown)
      this.busy = new Map(); // gate id -> "sending" | "ok" | "err"
      this._noticeTimer = null;
      this._trayTimer = null;
    }

    initialize() {
      this.button = doc.getElementById("home-gates-button");
      if (this.button) this.button.addEventListener("click", () => this.onButton());
      this.notice = el("div", "gates-notice");
      this.notice.hidden = true;
      this.notice.setAttribute("role", "status");
      this.notice.setAttribute("aria-live", "assertive");
      doc.body.append(this.notice);
      doc.addEventListener("pointerdown", (e) => {
        if (this.tray && !this.tray.hidden && !this.tray.contains(e.target) && !(this.button && this.button.contains(e.target))) this.closeTray();
      });
      doc.addEventListener("keydown", (e) => {
        if (e.key === "Escape") this.closeTray();
      });
      doc.addEventListener("visibilitychange", () => {
        if (!doc.hidden) this.refresh();
      });
      this.refresh();
      global.setInterval(() => this.refresh(), STATE_EVERY_MS);
      this._watchEvents();
    }

    // ------------------------------------------------------------ state

    async refresh() {
      const res = await call("GET", "gates");
      if (res.status === 200 && res.body) this._setState(res.body);
      else if (res.status === 404 || res.status === 503) this._setState(null);
      return this.state;
    }

    _setState(st) {
      this.state = st;
      if (st && this.seq === null) this.seq = st.seq || 0;
      this._drawButton();
      if (this.tray && !this.tray.hidden) this._drawTray();
      this.listeners.forEach((f) => {
        try {
          f(st);
        } catch (_e) {
          // a listener's error is its own
        }
      });
    }

    onChange(f) {
      this.listeners.add(f);
      return () => this.listeners.delete(f);
    }

    /** the gates shown on the map, in the driver's order */
    shownGates() {
      const st = this.state;
      if (!st || !st.linked || !Array.isArray(st.gates)) return [];
      return st.gates.filter((g) => g.shown);
    }

    // ------------------------------------------------------------ the API, for the settings page too

    async startLink() {
      const r = await call("POST", "gatesLink", { action: "start" });
      if (r.status !== 200) throw new Error(why(r));
      return r.body;
    }
    async linkState() {
      const r = await call("GET", "gatesLink");
      if (r.status !== 200) throw new Error(why(r));
      return r.body;
    }
    async cancelLink() {
      await call("POST", "gatesLink", { action: "cancel" });
    }
    async unlink() {
      const r = await call("POST", "gatesUnlink", {});
      if (r.status !== 200) throw new Error(why(r));
      await this.refresh();
    }
    async refreshList() {
      const r = await call("POST", "gatesRefresh", {});
      await this.refresh();
      if (r.status !== 200) throw new Error(why(r));
      return r.body;
    }
    async saveSettings(changes) {
      const r = await call("POST", "gatesSettings", changes);
      if (r.status !== 200) throw new Error(why(r));
      this._setState(r.body);
      return r.body;
    }
    async takePosition(id) {
      const r = await call("POST", "gatesPosition", { id });
      if (r.status !== 200) throw new Error(why(r));
      await this.refresh();
      return r.body;
    }

    /** open a gate: resolves {ok, message}; draws the feedback on the button, the tray and the notice */
    async open(gate) {
      if (this.busy.get(gate.id) === "sending") return { ok: true, message: "Already sending." };
      this._busy(gate.id, "sending");
      this.showNotice("sending", "Opening " + gateName(gate) + "…");
      const r = await call("POST", "gatesOpen", { id: gate.id });
      const ok = r.status === 200 && r.body && r.body.ok;
      if (r.body && r.body.event) this.mine.add(r.body.event.seq);
      const message = ok ? gateName(gate) + " opened" : gateName(gate) + " did not open: " + why(r);
      this._busy(gate.id, ok ? "ok" : "err", ok ? null : why(r));
      this.showNotice(ok ? "ok" : "err", message);
      if (ok && this.tray && !this.tray.hidden) {
        global.clearTimeout(this._trayTimer);
        this._trayTimer = global.setTimeout(() => this.closeTray(), 2500);
      }
      return { ok, message: ok ? "Opened" : why(r) };
    }

    _busy(id, st, reason) {
      this.busy.set(id, st);
      if (reason) this.busy.set(id + "\u0000why", reason);
      global.clearTimeout(this["_t" + id]);
      if (st !== "sending") {
        this["_t" + id] = global.setTimeout(() => {
          this.busy.delete(id);
          this.busy.delete(id + "\u0000why");
          this._drawButton();
          if (this.tray && !this.tray.hidden) this._drawTray();
        }, st === "ok" ? 4000 : 8000);
      }
      this._drawButton();
      if (this.tray && !this.tray.hidden) this._drawTray();
    }

    // ------------------------------------------------------------ the button and the tray

    _drawButton() {
      const b = this.button;
      if (!b) return;
      const gates = this.shownGates();
      b.hidden = gates.length === 0;
      if (!gates.length) {
        this.closeTray();
        return;
      }
      const one = gates.length === 1 ? gates[0] : null;
      const st = one ? this.busy.get(one.id) : null;
      b.classList.toggle("is-sending", st === "sending");
      b.classList.toggle("is-ok", st === "ok");
      b.classList.toggle("is-err", st === "err");
      const label = one ? "Open " + gateName(one) : "Gates";
      b.title = label;
      b.setAttribute("aria-label", label);
      b.setAttribute("aria-expanded", one ? null : String(Boolean(this.tray && !this.tray.hidden)));
      const ic = b.querySelector(".home-control-icon");
      if (ic) ic.textContent = st === "ok" ? "check" : st === "err" ? "priority_high" : "garage";
    }

    onButton() {
      const gates = this.shownGates();
      if (gates.length === 1) {
        this.open(gates[0]);
        return;
      }
      if (this.tray && !this.tray.hidden) this.closeTray();
      else this.openTray();
    }

    openTray() {
      if (!this.tray) {
        this.tray = el("div", "gates-tray");
        this.tray.setAttribute("role", "dialog");
        this.tray.setAttribute("aria-label", "Gates");
        const stage = doc.getElementById("home-stage") || doc.body;
        stage.append(this.tray);
      }
      this.tray.hidden = false;
      this._drawTray();
      this._placeTray();
      this._drawButton();
    }

    closeTray() {
      global.clearTimeout(this._trayTimer);
      if (this.tray && !this.tray.hidden) {
        this.tray.hidden = true;
        this._drawButton();
      }
    }

    /** under the page's buttons, at their right edge */
    _placeTray() {
      const controls = doc.getElementById("home-controls");
      if (!controls || !this.tray || !this.tray.offsetParent) return;
      const stage = this.tray.offsetParent.getBoundingClientRect();
      const b = controls.getBoundingClientRect();
      this.tray.style.top = Math.round(b.bottom - stage.top + 10) + "px";
      this.tray.style.right = Math.round(stage.right - b.right) + "px";
      const size = parseFloat(getComputedStyle(controls).getPropertyValue("--hc-size")) || 56;
      this.tray.style.setProperty("--gt-row", Math.max(56, size) + "px");
    }

    _drawTray() {
      const t = this.tray;
      if (!t) return;
      const gates = this.shownGates();
      const head = el("div", "gates-tray-head");
      head.append(el("span", "gates-tray-title", "Gates"));
      const close = el("button", "gates-tray-close");
      close.type = "button";
      close.setAttribute("aria-label", "Close");
      close.append(icon("close"));
      close.addEventListener("click", () => this.closeTray());
      head.append(close);
      const list = el("div", "gates-tray-list");
      for (const g of gates) {
        const st = this.busy.get(g.id);
        const row = el("button", "gates-tray-gate" + (st ? " is-" + st : ""));
        row.type = "button";
        row.setAttribute("aria-label", "Open " + gateName(g));
        const lead = el("span", "gates-tray-lead");
        lead.append(st === "sending" ? el("span", "gates-spin") : icon(st === "ok" ? "check" : st === "err" ? "priority_high" : "garage"));
        const text = el("span", "gates-tray-text");
        text.append(el("span", "gates-tray-name", gateName(g)));
        const line = st === "sending" ? "Sending…" : st === "ok" ? "Opened" : st === "err" ? this.busy.get(g.id + "\u0000why") || "Did not open" : g.auto && g.auto.enabled ? "Opens by itself nearby" : "Tap to open";
        text.append(el("span", "gates-tray-line", line));
        row.append(lead, text);
        row.addEventListener("click", () => this.open(g));
        list.append(row);
      }
      t.replaceChildren(head, list);
    }

    // ------------------------------------------------------------ notices

    showNotice(kind, text) {
      const n = this.notice;
      if (!n) return;
      global.clearTimeout(this._noticeTimer);
      n.className = "gates-notice is-" + kind + (doc.body.classList.contains("carapp-page-graphite") ? " graphite" : "") +
        (doc.body.classList.contains("carapp-page-night") ? " night" : "");
      n.replaceChildren(kind === "sending" ? el("span", "gates-spin") : icon(kind === "ok" ? "check_circle" : kind === "err" ? "error_outline" : "garage"), el("span", null, text));
      n.hidden = false;
      if (kind !== "sending") this._noticeTimer = global.setTimeout(() => (n.hidden = true), kind === "err" ? NOTICE_MS * 2 : NOTICE_MS);
    }

    /** opens made by the device (auto-open) or by another page: the events long poll */
    async _watchEvents() {
      for (;;) {
        if (this.seq === null || !this.state || !this.state.linked) {
          await new Promise((r) => global.setTimeout(r, 5000));
          continue;
        }
        const r = await call("GET", "gatesEvents?after=" + this.seq + "&wait=20");
        if (r.status !== 200 || !r.body) {
          await new Promise((res) => global.setTimeout(res, 5000));
          continue;
        }
        for (const ev of r.body.events || []) {
          if (this.mine.delete(ev.seq)) continue;
          const name = ev.name || "Gate";
          if (ev.kind === "auto") this.showNotice(ev.ok ? "ok" : "err", ev.ok ? name + " opened automatically" : name + " did not open automatically: " + ev.message);
          else this.showNotice(ev.ok ? "ok" : "err", ev.ok ? name + " opened" : name + " did not open: " + ev.message);
          if (ev.gate) this._busy(ev.gate, ev.ok ? "ok" : "err", ev.ok ? null : ev.message);
        }
        // the service's own count (it starts again from 0 when the service restarts)
        if (typeof r.body.seq === "number") this.seq = r.body.seq;
      }
    }
  }

  const gates = new GatesController();
  global.TLGates = gates;
  doc.addEventListener("DOMContentLoaded", () => {
    if (global.TLScreenEmulation && global.TLScreenEmulation.leaving) return;
    gates.initialize();
  });
})(window);
