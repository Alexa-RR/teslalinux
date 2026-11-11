// teslalinux: the car interface of the app (Settings > Waze interface: Car). The app's car
// templates arrive on /sockets/carapp (ATL's car-app host, docs/UPSTREAM-NOTES.md "Car app
// templates") and are drawn by carapp-renderer.js in a layer exactly over the display stream,
// which shows the app's own map. Taps on the panels go back on the same socket; touches on the
// map fall through to the display's touch layer as before. Nothing here runs in Phone mode.
(function (global) {
  "use strict";

  const RETRY_MS = 3000;

  // The car interface's look (Settings > Waze interface): this browser's choice, kept in its
  // local storage like the page's other display preferences (it changes nothing on the device but
  // the day/night choice, which goes to the car-app host on every connection).
  const LOOK_STYLE_KEY = "teslalinux.carInterfaceStyle";
  const LOOK_DAY_NIGHT_KEY = "teslalinux.carDayNight";
  const LOOK_MAP_KEY = "teslalinux.carMapColours";
  const STYLES = ["rounded", "graphite"];
  const DAY_NIGHT = ["auto", "day", "night"];
  const MAP_COLOURS = ["muted", "app"];

  function readPref(key, allowed, fallback) {
    try {
      const v = global.localStorage.getItem(key);
      return allowed.indexOf(v) >= 0 ? v : fallback;
    } catch (e) {
      return fallback; // storage off (private mode): the defaults
    }
  }

  function writePref(key, value) {
    try {
      global.localStorage.setItem(key, value);
    } catch (e) {
      // storage off: the choice lasts for this page only
    }
  }

  class CarAppController {
    constructor() {
      this.socket = null;
      this.renderer = null;
      this.icons = new Map(); // id -> object URL
      this.mono = new Map(); // id -> single-colour glyph
      this.surface = { width: 0, height: 0, dpi: 160 };
      this.template = null;
      this.lastArea = "";
      this.retry = null;
      this.lookStyle = readPref(LOOK_STYLE_KEY, STYLES, "rounded");
      this.dayNight = readPref(LOOK_DAY_NIGHT_KEY, DAY_NIGHT, "auto");
      this.mapColours = readPref(LOOK_MAP_KEY, MAP_COLOURS, "muted");
      this.hostNight = null; // the car's night mode as the host reports it (null: not reported)
    }

    /** The car interface's look as chosen in the settings: {style, dayNight, mapColours}. */
    getLook() {
      return { style: this.lookStyle, dayNight: this.dayNight, mapColours: this.mapColours };
    }

    /**
     * A new choice of look: "rounded" or "graphite", and "auto", "day" or "night". The day/night
     * choice also goes to the car-app host, which passes it to the app as the car's night mode
     * (its map turns dark or light with it).
     */
    setLook(look) {
      if (look && STYLES.indexOf(look.style) >= 0) {
        this.lookStyle = look.style;
        writePref(LOOK_STYLE_KEY, look.style);
      }
      if (look && DAY_NIGHT.indexOf(look.dayNight) >= 0 && look.dayNight !== this.dayNight) {
        this.dayNight = look.dayNight;
        writePref(LOOK_DAY_NIGHT_KEY, look.dayNight);
        this.send({ type: "dayNight", mode: this.dayNight });
      }
      if (look && MAP_COLOURS.indexOf(look.mapColours) >= 0) {
        this.mapColours = look.mapColours;
        writePref(LOOK_MAP_KEY, look.mapColours);
      }
      this.applyLook();
    }

    /** night: the driver's choice, else the car's night mode from the host (day until it tells) */
    isNight() {
      if (this.dayNight === "night") return true;
      if (this.dayNight === "day") return false;
      return this.hostNight === true;
    }

    applyLook() {
      if (this.renderer) this.renderer.setLook(this.lookStyle, this.isNight());
      this.applyMapColours();
    }

    /**
     * The app's map in the Graphite look's muted colours (js/display/map-tone.js, a shader pass of
     * the H.264 renderers; the MJPEG one shows it as it is): only with the Graphite look, and only
     * while the page knows whether the app draws its day or its night map (the driver's choice, or
     * the host's night mode), since each has its own palette.
     */
    applyMapColours() {
      const known = this.dayNight !== "auto" || this.hostNight !== null;
      const tone = this.mode === "car" && this.lookStyle === "graphite" && this.mapColours === "muted" && known
        ? (this.isNight() ? "night" : "day") : null;
      global.displayMapTone = tone;
      if (typeof global.setDisplayMapTone === "function") global.setDisplayMapTone(tone);
    }

    async start() {
      let mode = "phone";
      try {
        const r = await fetch(TAHtml.createFlavor().apiBaseUrl + "/appInterface", { cache: "no-store" });
        if (!r.ok) return; // no API route: Phone mode, as upstream
        mode = (await r.json()).interface || "phone";
      } catch (e) {
        return;
      }
      this.mode = mode;
      // a switch made elsewhere: the page's own health checks carry the interface
      global.addEventListener("teslalinux-app-interface", (e) => {
        if (e.detail && e.detail !== this.mode) global.location.reload();
      });
      if (mode !== "car") return;
      // the map colours for the display's renderer, whether it starts before or after this
      this.applyMapColours();
      let style;
      try {
        style = await (await fetch("./js/carapp/host-style.json")).json();
      } catch (e) {
        TAHtml.log("car interface: no host style: " + e);
        return;
      }
      const displayRoot = document.getElementById("display-root");
      if (!displayRoot) return;
      this.overlay = document.createElement("div");
      this.overlay.id = "carapp-overlay";
      this.surfaceEl = document.createElement("div");
      this.surfaceEl.className = "carapp-surface";
      this.overlay.appendChild(this.surfaceEl);
      // toasts and alerts: above the templates, laid out on the same surface
      this.popupsEl = document.createElement("div");
      this.popupsEl.className = "carapp-surface carapp-popups";
      this.overlay.appendChild(this.popupsEl);
      displayRoot.appendChild(this.overlay);
      this.style = style;
      new ResizeObserver(() => this.fit()).observe(displayRoot);
      this.connect();
    }

    /** The car channel went away (the app restarting): if the interface changed, follow it now. */
    async checkModeOnce() {
      try {
        const r = await fetch(TAHtml.createFlavor().apiBaseUrl + "/appInterface", { cache: "no-store" });
        if (!r.ok) return;
        const now = (await r.json()).interface || "phone";
        if (now !== this.mode) global.location.reload();
      } catch (e) {
        // the API restarting too: the next health check tells
      }
    }

    connect() {
      const url = TAHtml.createFlavor().displayWebSocket.replace(/\/sockets\/display$/, "/sockets/carapp");
      const ws = new WebSocket(url);
      this.socket = ws;
      // the driver's day or night (the host starts on "auto", the sun, after each app start)
      ws.onopen = () => {
        if (this.dayNight !== "auto") this.send({ type: "dayNight", mode: this.dayNight });
      };
      ws.onmessage = (e) => this.onMessage(JSON.parse(e.data));
      ws.onclose = () => {
        if (this.socket === ws) this.socket = null;
        this.checkModeOnce();
        clearTimeout(this.retry);
        this.retry = setTimeout(() => this.connect(), RETRY_MS);
      };
    }

    send(msg) {
      if (this.socket && this.socket.readyState === WebSocket.OPEN) this.socket.send(JSON.stringify(msg));
    }

    onMessage(m) {
      switch (m.type) {
        case "surface":
          this.surface = { width: m.width, height: m.height, dpi: m.dpi };
          this.renderer = null; // a new density: new tokens
          this.fit();
          this.draw();
          break;
        case "icon": {
          const bytes = Uint8Array.from(atob(m.png), (c) => c.charCodeAt(0));
          const old = this.icons.get(m.id);
          if (old) URL.revokeObjectURL(old);
          const blob = new Blob([bytes], { type: "image/png" });
          this.icons.set(m.id, URL.createObjectURL(blob));
          this.classifyIcon(m.id, blob);
          break;
        }
        case "template":
          this.template = m.template;
          this.draw();
          break;
        case "navigation":
          this.navigating = m.active;
          if (this.renderer) {
            this.renderer.navigating = m.active;
            this.draw();
          }
          break;
        case "dayNight":
          // the car's night mode (state): what the app hears as CarContext.isDarkMode
          this.hostNight = !!m.night;
          this.applyLook();
          break;
        case "restrictions":
          // the car's driving restrictions (state): lists capped, typing off while moving
          this.restrictions = m;
          if (this.renderer) this.renderer.setRestrictions(m);
          break;
        case "toast":
          if (this.renderer) this.renderer.toast(this.popupsEl, m.text, m.long);
          break;
        case "alert":
          if (this.renderer) this.renderer.alert(this.popupsEl, m.alert);
          break;
        case "alertDismiss":
          if (this.renderer) this.renderer.removeAlert(this.popupsEl, m.id);
          break;
      }
    }

    /**
     * Whether an icon is a single-colour glyph (all its visible pixels one colour): such an icon
     * without a tint of its own takes the colour of the button it is on, as the host's default
     * tint does; a picture keeps its colours.
     */
    async classifyIcon(id, blob) {
      try {
        const img = await createImageBitmap(blob);
        const w = Math.min(img.width, 64);
        const h = Math.min(img.height, 64);
        const canvas = new OffscreenCanvas(w, h);
        const g = canvas.getContext("2d");
        g.drawImage(img, 0, 0, w, h);
        const d = g.getImageData(0, 0, w, h).data;
        let n = 0;
        let r0 = -1, g0 = 0, b0 = 0;
        let mono = true;
        for (let i = 0; i < d.length; i += 4) {
          if (d[i + 3] < 128) continue;
          n++;
          if (r0 < 0) {
            r0 = d[i];
            g0 = d[i + 1];
            b0 = d[i + 2];
          } else if (Math.abs(d[i] - r0) + Math.abs(d[i + 1] - g0) + Math.abs(d[i + 2] - b0) > 60) {
            mono = false;
            break;
          }
        }
        this.mono.set(id, mono && n > 0);
        if (mono && n > 0 && this.template && !this.redrawQueued) {
          this.redrawQueued = true;
          requestAnimationFrame(() => {
            this.redrawQueued = false;
            this.draw();
          });
        }
      } catch (e) {
        // not decodable here: drawn as it is
      }
    }

    /** the layer has the surface's size in display pixels, scaled onto the shown display */
    fit() {
      if (!this.surfaceEl || !this.surface.width) return;
      const box = this.overlay.getBoundingClientRect();
      for (const el of [this.surfaceEl, this.popupsEl]) {
        const s = el.style;
        s.width = this.surface.width + "px";
        s.height = this.surface.height + "px";
        s.transform = "scale(" + box.width / this.surface.width + "," + box.height / this.surface.height + ")";
      }
    }

    draw() {
      if (!this.template || !this.surface.width) return;
      if (!this.renderer) {
        this.surfaceEl.textContent = "";
        this.popupsEl.textContent = "";
        this.renderer = new TLCarAppRenderer(this.surfaceEl, this.style, {
          send: (msg) => this.send(msg),
          icon: (id) => this.icons.get(id) || null,
          iconIsGlyph: (id) => this.mono.get(id) === true,
          density: this.surface.dpi,
        });
        this.renderer.listen(this.popupsEl);
        this.renderer.setLook(this.lookStyle, this.isNight());
        this.renderer.navigating = !!this.navigating;
        if (this.restrictions) this.renderer.setRestrictions(this.restrictions);
      }
      this.renderer.render(this.template);
      // the map area the panels leave free goes to the app (it keeps its car and route there)
      requestAnimationFrame(() => {
        const a = this.renderer.visibleArea(this.surface.width, this.surface.height);
        if (!a) return;
        const key = JSON.stringify(a);
        if (key === this.lastArea) return;
        this.lastArea = key;
        this.send(Object.assign({ type: "visibleArea" }, a));
      });
    }
  }

  const controller = new CarAppController();
  global.TLCarApp = controller;
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", () => controller.start());
  else controller.start();
})(window);
