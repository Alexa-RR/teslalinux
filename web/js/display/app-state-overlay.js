// teslalinux: what the display shows while the app is not drawing yet (README.teslalinux.md). The
// device reports the app's start (GET /api/appState "start", services/api/api.py): ART preparing the
// app's code (dex2oat, minutes on a Pi after an app or runtime update), the app starting, the app
// stopped and restarted by its service. This draws a panel over the display area that says which, with
// the real progress of a compile (its CPU time against the last compile of the same file on this
// device; elapsed time only when there is none yet) and the start's steps, and takes it away at the
// first display frame after the app's first picture. The panel never covers a working app: it shows
// only while the device says the app is not drawing, and goes away when the device cannot be asked.
(function (global) {
  "use strict";

  const RETRY_MS = 3000;
  const FRAME_GRACE_MS = 1500; // the first picture logged, no display frame yet: hidden after this
  const PHASES = ["compiling", "starting", "restarting", "stopped"];
  // The display itself says the app is drawing: the compositor sends a frame only when something on
  // screen changed, so several frames in a short time while the device still says "starting" are the
  // app's own screens (a fresh install's welcome and terms screens come before the map, whose first
  // picture is what the device waits for, and an app may not log one at all). The panel then goes
  // and stays away for that start.
  const DRAWING_FRAMES = 3;
  const DRAWING_WINDOW_MS = 4000;

  function fmtClock(s) {
    s = Math.max(0, Math.round(s));
    const m = Math.floor(s / 60);
    return m + ":" + String(s % 60).padStart(2, "0");
  }

  /** an honest "time left": rounded up to the minute above a minute, never a countdown to the second */
  function fmtLeft(s) {
    if (s < 45) return "Less than a minute left";
    const m = Math.round(s / 60);
    return m <= 1 ? "About a minute left" : "About " + m + " minutes left";
  }

  class AppStateOverlay {
    constructor(stage) {
      this.stage = stage;
      this.seq = null;
      this.state = null;
      this.shownAt = 0;
      this.waitFrame = 0; // the time the app's first picture was learnt, while waiting for a display frame
      this.hideTimer = null;
      this.frameTimes = []; // recent display frames, for "the app is drawing"
      this.drawingFor = null; // the start (its "started" time) the display showed drawing
      this.el = document.createElement("div");
      this.el.id = "app-state-overlay";
      this.el.hidden = true;
      this.el.setAttribute("role", "status");
      this.el.setAttribute("aria-live", "polite");
      this.el.innerHTML =
        '<div class="aso-card">' +
        '<div class="aso-head"><span class="aso-icon material-symbols-rounded" aria-hidden="true"></span>' +
        '<div class="aso-titles"><div class="aso-title"></div><div class="aso-sub"></div></div></div>' +
        '<div class="aso-progress"><div class="aso-bar"><div class="aso-fill"></div></div>' +
        '<div class="aso-meta"><span class="aso-left"></span><span class="aso-elapsed"></span></div></div>' +
        '<ol class="aso-steps"></ol>' +
        "</div>";
      stage.appendChild(this.el);
      this.q = (sel) => this.el.querySelector(sel);
      global.matchMedia && global.matchMedia("(prefers-color-scheme: dark)").addEventListener("change", () => this.applyTheme());
    }

    start() {
      this.poll();
    }

    async poll() {
      for (;;) {
        try {
          const base = global.TAHtml.createFlavor().apiBaseUrl;
          const url = base + "/appState" + (this.seq === null ? "?wait=0" : "?wait=" + this.seq);
          const r = await fetch(url, { cache: "no-store" });
          if (!r.ok) throw new Error("HTTP " + r.status);
          const body = await r.json();
          const st = body && body.start;
          if (!st || typeof st.seq !== "number") throw new Error("no start state");
          this.seq = st.seq;
          this.update(st);
        } catch (e) {
          // the device cannot be asked (or is too old to say): never cover the display for that
          this.seq = null;
          this.hide();
          await new Promise((res) => setTimeout(res, RETRY_MS));
        }
      }
    }

    /** a display frame arrived (js/core/app.js): after the app's first picture, the panel goes */
    onDisplayFrame() {
      const now = Date.now();
      this.frameTimes.push(now);
      while (this.frameTimes.length && now - this.frameTimes[0] > DRAWING_WINDOW_MS) this.frameTimes.shift();
      if (this.waitFrame && now > this.waitFrame) this.hide();
      else if (this.appIsDrawing(this.state)) {
        this.drawingFor = this.state.started;
        this.hide();
      }
    }

    /** a start the device still calls "starting" whose app process runs while the display keeps changing */
    appIsDrawing(st) {
      return !!(st && st.phase === "starting" && st.steps && st.steps.process && st.started
        && this.frameTimes.length >= DRAWING_FRAMES);
    }

    update(st) {
      this.state = st;
      if (st.phase === "starting" && st.started && (st.started === this.drawingFor || this.appIsDrawing(st))) {
        this.drawingFor = st.started;
        this.hide();
        return;
      }
      if (PHASES.indexOf(st.phase) < 0) {
        // running (the app's first picture logged), or not known: hidden at the next display frame
        if (!this.el.hidden && !this.waitFrame) {
          this.waitFrame = Date.now();
          clearTimeout(this.hideTimer);
          this.hideTimer = setTimeout(() => this.hide(), FRAME_GRACE_MS);
        }
        return;
      }
      this.waitFrame = 0;
      clearTimeout(this.hideTimer);
      this.render(st);
      if (this.el.hidden) {
        this.el.hidden = false;
        this.shownAt = Date.now();
      }
      this.applyTheme();
    }

    hide() {
      clearTimeout(this.hideTimer);
      this.waitFrame = 0;
      this.el.hidden = true;
    }

    /** the settings page's day and night: the car interface's, else the browser's */
    applyTheme() {
      const car = global.TLCarApp;
      let night;
      if (car && car.mode === "car" && typeof car.isNight === "function") night = car.isNight();
      else night = !!(global.matchMedia && global.matchMedia("(prefers-color-scheme: dark)").matches);
      this.el.dataset.theme = night ? "night" : "day";
    }

    render(st) {
      const c = st.compile;
      let icon, title, sub;
      if (st.phase === "compiling") {
        icon = "memory";
        title = "Preparing Waze for this device";
        sub = "One-time, after an update or a mode change";
      } else if (st.phase === "restarting" || (st.phase === "starting" && st.crashed)) {
        icon = "restart_alt";
        title = "Waze stopped, restarting";
        sub = "It comes back by itself";
      } else if (st.phase === "stopped") {
        icon = "pause_circle";
        title = "Waze is not running";
        sub = "Settings > Waze can start it again";
      } else {
        icon = "hourglass_top";
        title = "Starting Waze";
        sub = st.steps && st.steps.compile === "done" ? "Its code is ready" : "Usually under a minute";
      }
      this.q(".aso-icon").textContent = icon;
      this.q(".aso-title").textContent = title;
      this.q(".aso-sub").textContent = sub;

      // the compile's progress: real when the last compile of the same file was measured here
      const prog = this.q(".aso-progress");
      const fill = this.q(".aso-fill");
      prog.hidden = st.phase !== "compiling" || !c;
      if (st.phase === "compiling" && c) {
        const known = typeof c.fraction === "number";
        prog.classList.toggle("indeterminate", !known);
        fill.style.width = known ? Math.round(c.fraction * 100) + "%" : "";
        let left;
        if (c.overdue) left = "Taking longer than last time";
        else if (known && typeof c.remaining === "number") left = fmtLeft(c.remaining);
        else left = "The first time on this device takes a few minutes";
        this.q(".aso-left").textContent = left;
        this.q(".aso-elapsed").textContent = fmtClock(c.elapsed) + (c.estimate && c.estimate.wall ? " of about " + fmtClock(c.estimate.wall) : "");
      }

      // the start's steps: the code (when it had to be prepared), the app's process, its first picture
      const steps = [];
      const s = st.steps || {};
      if (s.compile === "running" || s.compile === "done") steps.push(["Preparing the code", s.compile === "done" ? "done" : "now"]);
      if (st.phase !== "restarting" && st.phase !== "stopped") {
        // the app's process starts its framework and its map, then draws its first picture
        steps.push(["Starting Waze", s.compile === "running" ? "wait" : s.firstFrame ? "done" : "now"]);
        steps.push(["Showing the map", s.firstFrame ? "done" : "wait"]);
      }
      const ol = this.q(".aso-steps");
      ol.hidden = steps.length === 0;
      ol.replaceChildren(
        ...steps.map(([label, state]) => {
          const li = document.createElement("li");
          li.className = "aso-step " + state;
          const mark = document.createElement("span");
          // the step under way: a spinner (CSS); done: a tick; to come: an empty circle
          mark.className = state === "now" ? "aso-mark aso-spin" : "aso-mark material-symbols-rounded";
          mark.setAttribute("aria-hidden", "true");
          mark.textContent = state === "done" ? "check" : state === "now" ? "" : "radio_button_unchecked";
          const text = document.createElement("span");
          text.textContent = label;
          li.append(mark, text);
          return li;
        }),
      );
    }
  }

  global.TLAppStateOverlay = AppStateOverlay;
  global.addEventListener("DOMContentLoaded", () => {
    const stage = document.getElementById("home-stage");
    if (!stage) return;
    const overlay = new AppStateOverlay(stage);
    global.TLAppState = overlay;
    overlay.start();
  });
})(window);
