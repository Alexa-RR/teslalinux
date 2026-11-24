// teslalinux: the car page's notice of a Teslalinux release that is ready (docs/SYSTEM-UPDATES.md).
// The device makes a new release ready while Waze keeps running (host/teslalinux-release-update
// prepare); switching to it then takes about a minute with Waze stopped, which happens by itself
// when the car is not connected, or at the next start. While the car page is open (the car is
// connected) this says so once per release: in Drive (the car's own link, GET /api/carLink) it only
// tells; parked, or without the car's link, it also offers to switch now. "Later" hides it for that
// release; GET /api/releaseUpdate is read once a minute.
(function (global) {
  "use strict";

  const doc = global.document;
  const POLL_MS = 60000;
  const KEY = "teslalinux.updateNotice.dismissed";

  function api(path, opts) {
    return global.fetch("/api/" + path, Object.assign({ cache: "no-store", credentials: "same-origin" }, opts || {}))
      .then((r) => (r.ok ? r.json() : null))
      .catch(() => null);
  }

  function dismissed() {
    try {
      return JSON.parse(global.localStorage.getItem(KEY) || "[]");
    } catch (_e) {
      return [];
    }
  }

  function dismiss(tag) {
    try {
      const list = dismissed().filter((t) => t !== tag).concat([tag]).slice(-10);
      global.localStorage.setItem(KEY, JSON.stringify(list));
    } catch (_e) {
      // private window: hidden for this page only
    }
  }

  function el(tag, cls, text) {
    const e = doc.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }

  const name = (tag) => String(tag || "").replace(/^v/, "");

  class UpdateNotice {
    constructor() {
      this.box = null;
      this.shownFor = null;
      this.hiddenHere = new Set();
      this.busy = false;
    }

    start() {
      this.box = el("div", "tl-update-notice");
      this.box.hidden = true;
      this.box.setAttribute("role", "status");
      this.box.setAttribute("aria-live", "polite");
      doc.body.append(this.box);
      global.setTimeout(() => this.poll(), 8000);
    }

    async poll() {
      try {
        if (!doc.hidden && !this.busy) await this.refresh();
      } finally {
        global.setTimeout(() => this.poll(), POLL_MS);
      }
    }

    async refresh() {
      const ru = await api("releaseUpdate");
      const p = ru && ru.pending;
      const ready = p && p.prepared && ru.phase === "idle";
      if (!ready || this.hiddenHere.has(p.tag) || dismissed().indexOf(p.tag) >= 0) {
        this.hide();
        return;
      }
      const link = await api("carLink");
      const gear = link && link.car && link.car.link === "connected" && link.car.drive ? link.car.drive.gear : "";
      this.show(p.tag, gear === "D" || gear === "R", ru.settings && ru.settings.autoUpdate !== false);
    }

    show(tag, driving, auto) {
      const b = this.box;
      const look = doc.body.classList;
      b.className = "tl-update-notice" + (look.contains("carapp-page-graphite") ? " graphite" : "") + (look.contains("carapp-page-night") ? " night" : "");
      const icon = el("span", "material-symbols-rounded tl-update-icon", "system_update");
      const text = el("div", "tl-update-text");
      text.append(el("div", "tl-update-title", "Teslalinux " + name(tag) + " is ready"));
      text.append(el("div", "tl-update-detail", driving
        ? (auto ? "It switches after you park and leave the car, or at the next start. Waze keeps working until then." : "Install it from Settings > Updates when you are parked.")
        : "Switching takes about a minute: Waze stops, the device restarts, and the page reconnects by itself."));
      const actions = el("div", "tl-update-actions");
      const later = el("button", "tl-update-btn", driving ? "OK" : "Later");
      later.type = "button";
      later.addEventListener("click", () => {
        dismiss(tag);
        this.hide();
      });
      actions.append(later);
      if (!driving) {
        const now = el("button", "tl-update-btn primary", "Switch now");
        now.type = "button";
        now.addEventListener("click", async () => {
          if (!now.classList.contains("armed")) {
            now.classList.add("armed");
            now.textContent = "Tap again to switch";
            global.setTimeout(() => {
              now.classList.remove("armed");
              now.textContent = "Switch now";
            }, 4000);
            return;
          }
          this.busy = true;
          now.disabled = later.disabled = true;
          now.textContent = "Switching…";
          const r = await api("releaseUpdate", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ action: "installNow" }),
          });
          this.busy = false;
          if (!r || r.ok === false) {
            now.disabled = later.disabled = false;
            now.classList.remove("armed");
            now.textContent = "Switch now";
            text.lastChild.textContent = "The device did not take it" + (r && r.message ? ": " + r.message : "") + ". Try again from Settings > Updates.";
            return;
          }
          this.hiddenHere.add(tag);
          text.lastChild.textContent = "Switching. Waze is back in about a minute.";
          global.setTimeout(() => this.hide(), 8000);
        });
        actions.append(now);
      }
      b.replaceChildren(icon, text, actions);
      b.hidden = false;
      this.shownFor = tag;
    }

    hide() {
      if (this.box) this.box.hidden = true;
      this.shownFor = null;
    }
  }

  const notice = new UpdateNotice();
  global.TLUpdateNotice = notice;
  if (doc.readyState === "loading") doc.addEventListener("DOMContentLoaded", () => notice.start());
  else notice.start();
})(window);
