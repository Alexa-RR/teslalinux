(function (global) {
  "use strict";

  const TAHtml = global.TAHtml;
  const utils = TAHtml.utils;
  const constants = TAHtml.constants;
  const UI = TAHtml.SettingsUI;
  const { h, icon } = UI;

  const APP_WORDS = { active: "Running", activating: "Starting", restarting: "Restarting", inactive: "Stopped", failed: "Stopped after an error" };
  const appWord = (s) => APP_WORDS[s] || "Unknown";

  class SettingsController {
    constructor(options) {
      this.apiClient = options.apiClient;
      this.displayController = options.displayController;
      this.gpsController = options.gpsController;
      this.audioController = options.audioController;
      this.flavor = options.flavor;
      this.onSystemConfigurationChanged = options.onSystemConfigurationChanged;
      // teslalinux: the page's only way out: "Map" goes back to the display
      this.onClose = options.onClose || null;

      this.sidebar = options.sidebar;
      this.content = options.content;
      this.page = global.document.getElementById("page-settings");
      this.app = global.document.getElementById("settings-app");
      this.pane = global.document.getElementById("settings-pane");
      this.titleEl = global.document.getElementById("settings-title");

      // what the rail's live values, the overview and the search read (refreshed per section)
      this.cache = {};
      this._cacheAt = 0;
      // changes that restart Waze, waiting together in one bar: key -> {label, value, saved, apply}
      this.restart = new Map();
      // hotspot and LTE changes, waiting for Apply now / At next start (the backup Wi-Fi applies at once)
      this.netDraft = { hotspot: {}, lte: {}, props: {} };
      this._lteManaged = false;

      this.sections = [
        // teslalinux: what the device is connected to right now (GET /api/status)
        {
          id: "overview",
          title: "Overview",
          icon: "dashboard",
          render: this._overviewSection,
          railValue() {
            if (!this.cache.status) return "";
            const n = this._attention().length;
            return n ? n + (n === 1 ? " thing to check" : " things to check") : "All good";
          },
        },
        // teslalinux: the app's interface, keyboard, language, position, and the car interface's look and map
        {
          id: "waze",
          title: "Waze",
          icon: "navigation",
          render: this._wazeSection,
          railValue() {
            if (!this.cache.iface && !global.TLCarApp) return "";
            return this._ifaceNow() === "car" ? "Car interface" : "Phone interface";
          },
        },
        // the picture sent to the car (POST /api/displayState) and the Pi's graphics speed
        {
          id: "display",
          title: "Display",
          icon: "desktop_windows",
          render: this._displaySection,
          railValue() {
            const d = this.cache.display;
            if (!d) return "";
            const res = d.resolutionPreset === constants.NATIVE_RESOLUTION_PRESET ? "Native" : utils.resolutionPresetName(d.resolutionPreset);
            return res + ", " + d.refreshRate + " Hz";
          },
        },
        {
          id: "sound",
          title: "Sound",
          icon: "volume_up",
          render: this._soundSection,
          railValue() {
            const a = this.cache.audio;
            return a ? { car: "Plays in the car", pi: "Plays on the Pi", both: "Car and Pi" }[a.output] || "" : "";
          },
        },
        // teslalinux: the internet now, the hotspot, the LTE modem and the backup Wi-Fi (GET /api/status,
        // GET/POST /api/network); upstream's Rear Display is gone (the app is shown on the main screen)
        {
          id: "connections",
          title: "Connections",
          icon: "wifi",
          render: this._connectionsSection,
          railValue() {
            const st = this.cache.status;
            if (!st) return "";
            if (!st.internet || !st.internet.reachable) return "No internet";
            const up = st.uplink || {};
            const m = this._modem(st);
            // the signal as bars, not words
            if (up.kind === "lte") return m && typeof m.bars === "number" ? ["LTE ", UI.signalBars(m.bars)] : "LTE";
            return { wifi: "Backup Wi-Fi", ethernet: "Ethernet", phone: "Phone" }[up.kind] || "Online";
          },
        },
        // teslalinux: Waze's own updates (GET/POST /api/appUpdate*), the operating system's
        // (GET/POST /api/systemUpdate) and the Teslalinux release installed (GET /api/status)
        {
          id: "updates",
          title: "Updates",
          icon: "system_update",
          render: this._updatesSection,
          railValue() {
            const c = this.cache;
            if (!c.appUpdate && !c.sys && !c.release) return "";
            if ((c.sys && c.sys.running) || (c.release && ["installing", "restarting", "rolling-back"].indexOf(c.release.phase) >= 0)) return "Installing…";
            const n = (c.appUpdate && c.appUpdate.pending ? 1 : 0) + (c.sys && Array.isArray(c.sys.available) && c.sys.available.some((p) => !p.held) ? 1 : 0) +
              (c.release && c.release.supported && (c.release.available || c.release.pending) ? 1 : 0);
            return n ? n + " waiting" : "Up to date";
          },
        },
        // the device itself: health, how long it took to start, restarts, what it is
        {
          id: "device",
          title: "This device",
          icon: "memory",
          render: this._deviceSection,
          railValue() {
            const st = this.cache.status;
            if (!st) return "";
            const t = st.system && st.system.cpuTemperature;
            const app = appWord(st.app && st.app.state).toLowerCase();
            return typeof t === "number" && t >= 0 ? Math.round(t) + " °C, " + app : "Waze " + app;
          },
        },
      ];

      // the search index: [row id, section, title, the words a driver might use for it]
      // (the fifth, when set: the setting exists only while this is true, e.g. in one interface)
      const car = () => this._ifaceNow() === "car";
      const phone = () => this._ifaceNow() !== "car";
      this.index = [
        ["quick.daynight", "overview", "Day and night", "dark light night day mode theme quick", car],
        ["quick.restart", "overview", "Restart Waze", "restart waze frozen stuck reload quick"],
        ["waze.interface", "waze", "Waze interface", "phone car mode screens big buttons android auto"],
        ["waze.keyboard", "waze", "Keyboard for typing", "keyboard type osk on-screen car keyboard", phone],
        ["waze.locale", "waze", "Language and region", "language units km miles region format locale"],
        ["waze.timezone", "waze", "Time zone", "time zone clock arrival eta"],
        ["waze.gps", "waze", "Car's position", "gps location position where am i"],
        ["waze.style", "waze", "Panel style", "look theme rounded graphite style", car],
        ["waze.daynight", "waze", "Day and night", "dark light night day mode theme", car],
        ["waze.panelsize", "waze", "Panel size", "panel buttons size bigger smaller cards", car],
        ["waze.speedometer", "waze", "Speedometer", "speedometer speed limit sign km/h mph", car],
        ["waze.speedometerpos", "waze", "Speedometer position", "speedometer position place corner top left bottom right directions", car],
        ["waze.mapcolours", "waze", "Map colours", "map colours colors graphite muted palette", car],
        ["waze.mapsize", "waze", "Map text size", "map size street names zoom labels symbols", car],
        ["waze.pacing", "waze", "Map frame rate", "map frame rate fps smooth 60 30", car],
        ["waze.sharpness", "waze", "Map sharpness", "map sharpness render scale blurry", car],
        ["waze.typing", "waze", "Typing while driving", "typing keyboard driving moving passenger restriction search locked type", car],
        ["waze.appsettings", "waze", "Waze's settings", "waze own settings voice alerts reports units navigation avoid tolls sound", car],
        ["display.renderer", "display", "Picture format", "renderer h264 mjpeg webcodecs codec stream"],
        ["display.resolution", "display", "Resolution", "resolution native 832p 720p sharp pixels"],
        ["display.quality", "display", "Image quality", "quality compression jpeg"],
        ["display.responsive", "display", "Follow the window size", "dynamic aspect ratio resize window"],
        ["display.refresh", "display", "Refresh rate", "refresh rate hz fps frames smooth"],
        ["display.gpu", "display", "Graphics speed", "gpu speed clock overclock fan heatsink"],
        ["net.internet", "connections", "Internet now", "internet lte signal latency online modem operator data sim"],
        ["net.ssid", "connections", "Hotspot name", "wifi hotspot name ssid network car connected"],
        ["net.psk", "connections", "Hotspot password", "wifi password key psk"],
        ["net.country", "connections", "Country", "country channels regulatory"],
        ["net.band", "connections", "Band and channel", "band channel 5 ghz 2.4 bluetooth"],
        ["net.offline", "connections", "Keep the car on the hotspot", "offline connectivity check no internet"],
        ["net.telemetry", "connections", "Tesla telemetry", "telemetry data saver mobile data"],
        ["net.firmware", "connections", "Tesla software downloads", "firmware software update downloads data"],
        ["net.apn", "connections", "APN", "apn lte operator mobile carrier sim"],
        ["net.roaming", "connections", "Data roaming", "roaming abroad", () => this._lteManaged],
        ["net.mobiledata", "connections", "Mobile data", "mobile data lte off", () => this._lteManaged],
        ["net.backup", "connections", "Backup Wi-Fi", "backup wifi second card garage underground failover"],
        ["net.prefer", "connections", "When to use backup Wi-Fi", "prefer wifi lte save data"],
        ["net.add", "connections", "Add a Wi-Fi network", "add wifi network scan join home phone hotspot"],
        ["upd.waze", "updates", "Waze updates", "waze update version check"],
        ["upd.source", "updates", "Update source", "apkpure google play aurora manual source"],
        ["upd.auto", "updates", "Update Waze automatically", "automatic update auto", () => this._autoUpdates()],
        ["upd.pin", "updates", "Waze version", "pin version stay latest", () => this._autoUpdates()],
        ["upd.lte", "updates", "Update over mobile data", "metered mobile data download lte", () => this._autoUpdates()],
        ["upd.upload", "updates", "Upload an update", "upload apk apkm xapk file manual"],
        ["upd.play", "updates", "Google Play via a token dispenser", "google play dispenser advanced aurora"],
        ["upd.system", "updates", "System updates", "debian apt os system packages security kernel"],
        ["upd.version", "updates", "Teslalinux version", "teslalinux version release installed what's new changelog"],
        ["upd.release", "updates", "Teslalinux updates", "teslalinux update release new version github check download install"],
        ["upd.releaseauto", "updates", "Update Teslalinux automatically", "teslalinux automatic update release auto"],
        ["upd.releaseback", "updates", "Go back to the previous release", "teslalinux rollback previous release go back downgrade", () => Boolean(this.cache.release && this.cache.release.previous)],
        ["dev.health", "device", "Health", "temperature cpu load memory storage power throttled voltage heat"],
        ["dev.boot", "device", "Start-up time", "boot start startup how long power on fast slow seconds", () => Boolean(this._bootShown())],
        ["dev.restartapp", "device", "Restart Waze", "restart waze frozen stuck reload"],
        ["dev.restart", "device", "Restart the device", "reboot restart pi power"],
        ["dev.info", "device", "Model and serial number", "model serial kernel os about hostname"],
        ["dev.emulate", "device", "Emulate the Model 3 screen", "debug test emulate model 3 tesla car screen resolution scaling pixel ratio dpr computer"],
        ["dev.about", "device", "About Teslalinux", "about credits version licence license gpl source code github tesla android"],
        ["sound.output", "sound", "Where Waze's sound plays", "audio output speaker pi car both sound"],
        ["sound.pivolume", "sound", "Pi speaker volume", "volume loud quiet pi speaker usb jack", () => this.cache.audio && this.cache.audio.output !== "car"],
        ["sound.test", "sound", "Test sound", "test sound chime play hear speaker audio volume"],
        ["sound.browser", "sound", "Sound in the car's browser", "browser audio bluetooth sound"],
        ["sound.browservolume", "sound", "Browser volume", "volume loud quiet"],
        ["sound.voicebutton", "sound", "Voice search button", "voice microphone mic search speech dictation"],
        ["sound.voicelang", "sound", "Voice search language", "voice speech hebrew english microphone language", () => this._voiceOn()],
        ["sound.mictest", "sound", "Test the microphone", "microphone mic test voice", () => this._voiceOn()],
      ];

      // teslalinux: PalGate gates (js/settings/settings-gates.js): its section after Waze, and its search entries
      if (TAHtml.SettingsGates) TAHtml.SettingsGates.install(this);
      // teslalinux: the car's own link (js/settings/settings-tesla.js, docs/TESLA-LINK.md): right after Waze
      if (TAHtml.SettingsTesla) TAHtml.SettingsTesla.install(this);
      // teslalinux: Grok remote control (js/settings/settings-grok.js, docs/GROK.md)
      if (TAHtml.SettingsGrok) TAHtml.SettingsGrok.install(this);

      this.activeSectionId = "overview";
      this._pollTimer = null;
      this._renderToken = 0;

      this.systemConfigCache = null;
    }

    async initialize() {
      UI.mount(this.page);
      this._bindHeader();
      this._applyTheme();
      global.addEventListener("teslalinux-carapp-look", () => this._applyTheme());
      this._renderSidebar();
    }

    /** the section to show (and a row in it to scroll to and highlight) */
    async setActiveSection(sectionId, focusId) {
      if (!this.sections.some((s) => s.id === sectionId)) sectionId = this.sections[0].id;
      this.activeSectionId = sectionId;
      if (this.app) this.app.dataset.view = "section";
      this._closeSearch();
      this._applyTheme();
      this._renderSidebar();
      await this._renderActiveSection();
      if (focusId) {
        const el = this.content.querySelector('[data-id="' + focusId + '"]');
        if (el) {
          el.scrollIntoView({ block: "center" });
          el.classList.remove("flash");
          void el.offsetWidth;
          el.classList.add("flash");
        }
      } else if (this.pane) {
        this.pane.scrollTop = 0;
      }
      this._refreshCacheSoon();
    }

    /**
     * The page is no longer shown (the map, another page) or the Waze section left: Waze's own
     * settings screens, opened for the "Waze's settings" group, are closed in Waze (back until
     * its map is its screen again, js/carapp/carapp-controller.js closeAppSettings).
     */
    onHidden() {
      const car = global.TLCarApp;
      if (car && car.closeAppSettings) car.closeAppSettings();
    }

    /** the section drawn again where it is (after a change), and the rail */
    async _rerender() {
      await this._renderActiveSection(true);
      this._renderSidebar();
    }

    async _renderActiveSection(keepScroll) {
      const token = ++this._renderToken;
      if (this._pollTimer) {
        global.clearTimeout(this._pollTimer);
        this._pollTimer = null;
      }
      const section = this.sections.find((s) => s.id === this.activeSectionId) || this.sections[0];
      // Waze's own settings screens are open only while the Waze section shows them
      if (section.id !== "waze") this.onHidden();
      if (this.titleEl) this.titleEl.textContent = section.title;
      const scroll = this.pane ? this.pane.scrollTop : 0;
      if (section.render) {
        // the new sections return their nodes; until they arrive the old ones stay (no flash)
        if (!this.content.childElementCount) this.content.replaceChildren(h("div", { class: "loading" }, "Loading…"));
        let nodes;
        try {
          nodes = await section.render.call(this, token);
        } catch (e) {
          TAHtml.log && TAHtml.log("settings: " + section.id + ": " + e);
          nodes = [h("div", { class: "error-note", role: "alert" }, icon("error_outline"), h("span", null, "The device did not answer. Try again in a moment."))];
        }
        if (token !== this._renderToken) return;
        this.content.replaceChildren(...[].concat(nodes).filter(Boolean));
        if (this.pane) this.pane.scrollTop = keepScroll ? scroll : this.pane.scrollTop;
        return;
      }
    }

    _wifiBars(n) {
      const pct = typeof n.signalPercent === "number" ? n.signalPercent : typeof n.signalDbm === "number" ? Math.min(100, Math.max(0, 2 * (n.signalDbm + 100))) : 0;
      // the bundled Material Icons font has full, partial and empty Wi-Fi glyphs (no 1-3 bar ones)
      if (pct >= 60) {
        return n.secure ? "signal_wifi_4_bar_lock" : "signal_wifi_4_bar";
      }
      return pct >= 30 ? "network_wifi" : "signal_wifi_0_bar";
    }

    // the terminal: a <pre> with a blinking cursor while the run goes on; afterwards folded under
    // "Output of the last run", open when the run failed
    _createTerminal(live, failed) {
      const text = global.document.createTextNode("");
      const cursor = h("span", { class: "term-cursor" });
      const pre = h("pre", { class: "term" }, text, live ? cursor : null);
      const root = live ? pre : h("div", null, UI.disclosure("Output of the last run", pre, failed));
      return { root, pre, text, cursor, queue: [], dripping: false, progress: null };
    }

    /** the package manager's own progress: a step, a percentage and a bar (a sweep while unknown) */
    _createProgress(term) {
      const step = h("span", null, "Starting…");
      const pct = h("span", { class: "num" });
      const bar = h("div", { class: "progress-bar" });
      const track = h("div", { class: "progress-track indeterminate" }, bar);
      term.progress = { step, pct, track, bar };
      return h("div", { class: "progress" }, h("div", { class: "progress-label" }, step, pct), track);
    }

    _applySystemProgress(term, p) {
      if (!term.progress) {
        return;
      }
      const ui = term.progress;
      ui.step.textContent = (p && p.step) || "Working…";
      if (p && typeof p.percent === "number") {
        ui.track.classList.remove("indeterminate");
        ui.bar.style.width = p.percent + "%";
        ui.pct.textContent = Math.floor(p.percent) + " %";
      } else {
        ui.track.classList.add("indeterminate");
        ui.bar.style.width = "";
        ui.pct.textContent = "";
      }
    }

    // lines into the terminal a few per frame, so a burst of output scrolls by instead of jumping; the
    // view follows the end unless the user scrolled up to read
    _dripTerminal(term) {
      if (term.dripping) {
        return;
      }
      term.dripping = true;
      const frame = () => {
        if (!term.pre.isConnected || term.queue.length === 0) {
          term.dripping = false;
          return;
        }
        const pre = term.pre;
        const follow = pre.scrollHeight - pre.scrollTop - pre.clientHeight < 48;
        // drain a burst in about a third of a second, never slower than two lines a frame
        const n = Math.max(2, Math.ceil(term.queue.length / 20));
        term.text.appendData(term.queue.splice(0, n).join(""));
        if (term.text.length > 200000) {
          term.text.deleteData(0, term.text.length - 150000);
        }
        if (follow) {
          pre.scrollTop = pre.scrollHeight;
        }
        global.requestAnimationFrame(frame);
      };
      global.requestAnimationFrame(frame);
    }

    _feedTerminal(term, chunk, animate) {
      if (!chunk) {
        return;
      }
      // apt and dpkg redraw some lines with \r; the terminal keeps what a terminal would show
      const clean = chunk.replace(/\x1b\[[0-9;?]*[A-Za-z]/g, "").replace(/[^\n]*\r(?!\n)/g, "");
      this._sysLog.text = (this._sysLog.text + clean).slice(-200000);
      if (!animate) {
        term.text.appendData(clean);
        term.pre.scrollTop = term.pre.scrollHeight;
        return;
      }
      const lines = clean.match(/[^\n]*\n|[^\n]+$/g) || [];
      Array.prototype.push.apply(term.queue, lines);
      this._dripTerminal(term);
    }

    // the run's output from where this page left off; while the run goes on, every 700 ms, until it ends
    async _streamSystemLog(token, term, st, live) {
      const runId = st.started || 0;
      if (!this._sysLog || this._sysLog.run !== runId) {
        this._sysLog = { run: runId, text: "", offset: 0 };
      } else if (this._sysLog.text) {
        term.text.appendData(this._sysLog.text); // drawn again: what we already have, at once
        term.pre.scrollTop = term.pre.scrollHeight;
      }
      const stillHere = () => token === this._renderToken && this.activeSectionId === "updates" && term.pre.isConnected;
      // catch up without animation (a long log, or the page opened mid-run)
      for (let i = 0; i < 40; i += 1) {
        let s;
        try {
          s = await this.apiClient.fetchSystemUpdate(this._sysLog.offset);
        } catch (_error) {
          return;
        }
        if (!stillHere()) {
          return;
        }
        if (s.logReset) {
          this._sysLog.text = "";
          term.text.deleteData(0, term.text.length);
        }
        this._feedTerminal(term, s.log, false);
        const moved = s.logOffset !== this._sysLog.offset;
        this._sysLog.offset = s.logOffset;
        if (!moved || (s.log || "").length < 60000) {
          break;
        }
      }
      if (!live) {
        return;
      }
      const tick = async () => {
        if (!stillHere()) {
          return;
        }
        let s;
        try {
          s = await this.apiClient.fetchSystemUpdate(this._sysLog.offset);
        } catch (_error) {
          this._pollTimer = global.setTimeout(tick, 2000);
          return;
        }
        if (!stillHere()) {
          return;
        }
        if (s.logReset) {
          this._sysLog.text = "";
          term.text.deleteData(0, term.text.length);
          term.queue.length = 0;
        }
        this._feedTerminal(term, s.log, true);
        this._sysLog.offset = s.logOffset;
        this._applySystemProgress(term, s.progress);
        if (s.running) {
          this._pollTimer = global.setTimeout(tick, 700);
          return;
        }
        // finished: let the last lines scroll in, then draw the result
        const settle = () => {
          if (!stillHere()) {
            return;
          }
          if (term.queue.length) {
            this._pollTimer = global.setTimeout(settle, 100);
            return;
          }
          this._rerender();
        };
        if (term.progress) {
          term.progress.track.classList.remove("indeterminate");
          term.progress.bar.style.width = "100%";
        }
        this._pollTimer = global.setTimeout(settle, 400);
      };
      this._pollTimer = global.setTimeout(tick, 700);
    }

    // ------------------------------------------------------------------ the page frame

    _bindHeader() {
      const doc = global.document;
      const back = doc.getElementById("settings-back");
      if (back) {
        back.addEventListener("click", () => {
          if (this._phoneWidth() && this.app && this.app.dataset.view === "section") {
            this.app.dataset.view = "list";
            if (this.titleEl) this.titleEl.textContent = "Settings";
            this._closeSearch();
            return;
          }
          UI.closeSheet();
          this._closeSearch();
          if (this.onClose) this.onClose();
        });
      }
      const box = doc.getElementById("settings-search");
      const input = doc.getElementById("settings-search-input");
      const clear = doc.getElementById("settings-search-clear");
      if (input) {
        input.addEventListener("input", () => this._runSearch(input.value));
        input.addEventListener("keydown", (e) => {
          if (e.key === "Escape") {
            input.value = "";
            this._runSearch("");
            input.blur();
          }
          if (e.key === "Enter" && this._results) {
            const first = this._results.querySelector(".result");
            if (first) first.click();
          }
        });
      }
      if (clear) {
        clear.addEventListener("click", () => {
          input.value = "";
          this._runSearch("");
          input.focus();
        });
      }
      this._searchBox = box;
      this._searchInput = input;
    }

    _phoneWidth() {
      return global.matchMedia && global.matchMedia("(max-width: 600px)").matches;
    }

    /** the settings page is on screen (not the map, and the browser tab in front) */
    _visible() {
      return this.page && !this.page.hidden && !global.document.hidden;
    }

    /**
     * The page follows the car interface's day and night: the driver's Day or Night, or on
     * Automatic the car-app host's state (the sun at the car's position). With the phone interface
     * there is no car-app host, and the browser's prefers-color-scheme decides.
     */
    _applyTheme() {
      if (!this.page) return;
      const car = global.TLCarApp;
      if (car && car.mode === "car" && typeof car.isNight === "function") {
        this.page.dataset.theme = car.isNight() ? "night" : "day";
      } else {
        delete this.page.dataset.theme;
      }
    }

    _renderSidebar() {
      this.sidebar.replaceChildren(
        ...this.sections.map((section) =>
          h(
            "button",
            {
              class: "rail-item",
              type: "button",
              "aria-current": section.id === this.activeSectionId ? "page" : null,
              onclick: () => this.setActiveSection(section.id),
            },
            icon(section.icon),
            h("span", { class: "rail-label" }, section.title),
            h("span", { class: "rail-value" }, this._railValue(section.id)),
            this._railDot(section.id) ? h("span", { class: "rail-dot", "aria-label": "Needs attention" }) : null,
          ),
        ),
      );
    }

    /** the rail entry's live value (empty until the cache has what it needs) */
    _railValue(id) {
      const section = this.sections.find((s) => s.id === id);
      if (!section || !section.railValue) return "";
      try {
        return section.railValue.call(this) || "";
      } catch (_e) {
        return "";
      }
    }

    _railDot(id) {
      if (id === "overview") return false;
      return this._attention().some((x) => x.section === id);
    }

    /** what needs the driver (the Overview's list, and the rail's dots) */
    _attention() {
      const c = this.cache;
      const out = [];
      const st = c.status;
      if (st) {
        const hot = st.hotspot || {};
        const clients = Array.isArray(hot.clients) ? hot.clients : [];
        if (hot.present === false) {
          out.push({ icon: "wifi_off", title: "No hotspot", detail: "This device has no Wi-Fi for the car to join.", section: "connections", go: "net.ssid" });
        } else if (!clients.length) {
          out.push({ icon: "wifi_off", title: "The car is not on the hotspot", detail: "Join " + (hot.ssid || "the hotspot") + " in the car's Wi-Fi settings.", section: "connections", go: "net.ssid" });
        }
        if (st.internet && st.internet.reachable === false) {
          out.push({ icon: "public_off", title: "No internet", detail: "Waze keeps working with the map it has.", section: "connections", go: "net.internet" });
        }
        const modem = st.failover && st.failover.modem;
        if (modem && modem.simProblem) {
          out.push({ icon: "sim_card_alert", title: "SIM card problem", detail: modem.simProblem, section: "connections", go: "net.internet" });
        }
        if (this._stuckStick(modem, st.time)) {
          out.push({ icon: "usb", title: "LTE stick stuck as a USB drive", detail: "The USB power is switched off and on to start it.", section: "connections", go: "net.internet" });
        }
        const state = st.app && st.app.state;
        if (state && state !== "active" && APP_WORDS[state]) {
          out.push({
            icon: "restart_alt",
            title: state === "failed" || state === "inactive" ? "Waze is not running" : "Waze is starting",
            detail: state === "failed" || state === "inactive" ? "Restart it from This device." : "It is back in about a minute.",
            section: "device",
            go: "dev.restartapp",
          });
        }
        const th = st.system && st.system.throttled;
        if (th && (th.underVoltage || th.throttled || th.capped || th.softTempLimit)) {
          out.push({ icon: "bolt", title: "Power or heat problem", detail: "The Pi slows itself down now: check its power supply and cooling.", section: "device", go: "dev.health" });
        }
      }
      if (c.appUpdate && c.appUpdate.pending && c.appUpdate.pending.versionName) {
        out.push({ icon: "system_update", title: "Waze " + c.appUpdate.pending.versionName + " is ready", detail: "It installs by itself when the car is not connected.", section: "updates", go: "upd.waze" });
      }
      if (c.sys && Array.isArray(c.sys.available)) {
        const n = c.sys.available.filter((p) => !p.held).length;
        if (n && !c.sys.running) {
          out.push({ icon: "security", title: n + (n === 1 ? " system update" : " system updates"), detail: "Fixes for the system underneath.", section: "updates", go: "upd.system" });
        }
      }
      if (c.sys && c.sys.rebootRequired) {
        out.push({ icon: "power_settings_new", title: "Restart needed", detail: "New system files take effect after a restart.", section: "device", go: "dev.restart" });
      }
      if (c.gpu && c.gpu.pendingRestart) {
        out.push({ icon: "speed", title: "Graphics speed waits for a restart", detail: "The new speed applies when the device starts again.", section: "device", go: "dev.restart" });
      }
      return out;
    }

    // ------------------------------------------------------------------ Overview

    /** the glance: the car, the internet, the position, Waze; each opens where it is explained */
    _overviewGlance() {
      const c = this.cache;
      const st = c.status || {};
      const hot = st.hotspot || {};
      const car = (Array.isArray(hot.clients) ? hot.clients : [])[0];
      const internet = st.internet || {};
      const up = st.uplink;
      const modem = (st.failover && st.failover.modem && st.failover.modem.type && st.failover.modem.type !== "none" ? st.failover.modem : null) || null;
      const gps = st.gps || {};
      const fix = gps.fix;
      const state = st.app && st.app.state;
      const cell = (kind, ic, word, sub, target, focus) =>
        h(
          "button",
          { class: "gl gl--" + kind, type: "button", onclick: () => this.setActiveSection(target, focus) },
          h("span", { class: "gl-glyph" }, typeof ic === "string" ? icon(ic) : ic),
          h("span", { class: "gl-word" }, word),
          h("span", { class: "gl-sub" }, sub),
        );
      let carCell;
      if (!c.status) carCell = cell("off", "directions_car", "…", "Asking the device", "connections", "net.ssid");
      else if (car) {
        const sig = typeof car.signalDbm === "number" ? ", " + UI.fmt.signal(car.signalDbm) + " signal" : "";
        carCell = cell("ok", "directions_car", "Connected", (car.name || car.ip || car.mac || "The car") + sig, "connections", "net.ssid");
      } else if (hot.present === false) carCell = cell("bad", "wifi_off", "No hotspot", "No Wi-Fi on this device", "connections", "net.ssid");
      else carCell = cell("bad", "wifi_off", "No car", "Join " + (hot.ssid || "the hotspot") + " in the car", "connections", "net.ssid");

      const kindWord = { lte: "LTE", wifi: "Wi-Fi", ethernet: "Ethernet", phone: "Phone" };
      let netCell;
      if (!c.status) netCell = cell("off", "public", "…", "Asking the device", "connections", "net.internet");
      else if (internet.reachable) {
        const via = up ? (up.kind === "lte" && modem && modem.operator ? "LTE " + modem.operator : kindWord[up.kind] || up.kind) : "";
        const lat = typeof internet.latencyMs === "number" ? internet.latencyMs + " ms" : "";
        const weak = up && up.kind === "lte" && modem && typeof modem.bars === "number" && modem.bars < 3;
        // LTE: the live signal as bars in the glyph
        const glyph = up && up.kind === "lte" ? (modem && typeof modem.bars === "number" ? UI.signalBars(modem.bars) : "signal_cellular_alt") : up && up.kind === "wifi" ? "wifi" : "public";
        netCell = cell(weak ? "warn" : "ok", glyph, "Online", [via, lat].filter(Boolean).join(", "), "connections", "net.internet");
      } else netCell = cell("bad", "public_off", "Offline", "Waze uses the map it has", "connections", "net.internet");

      const gpsOn = !c.config || utils.toInt(c.config["persist.tesla-android.gps.is_active"], 1) === 1;
      let posCell;
      if (fix) posCell = cell("ok", "my_location", "Position live", "From the car" + (typeof fix.ageSeconds === "number" ? ", " + fix.ageSeconds + " s ago" : ""), "waze", "waze.gps");
      else if (!gpsOn) posCell = cell("off", "location_disabled", "No position", "Turn on Car's position", "waze", "waze.gps");
      else posCell = cell("off", "location_searching", "No position", gps.service === false ? "The position service is not running" : "Waiting for the car's browser", "waze", "waze.gps");

      const appOk = state === "active";
      const iface = this._ifaceNow() === "car" ? "Car interface" : "Phone interface";
      const known = Boolean(APP_WORDS[state]);
      const appCell = cell(
        !known ? "off" : appOk ? "ok" : state === "failed" || state === "inactive" ? "bad" : "warn",
        "navigation",
        known ? "Waze " + appWord(state).toLowerCase() : "Waze",
        known ? iface : "State unknown",
        "device",
        "dev.restartapp",
      );
      return h("div", { class: "glance", "data-part": "glance" }, carCell, netCell, posCell, appCell);
    }

    _overviewAttention() {
      const items = this._attention();
      const body = items.length
        ? items.map((a) =>
            h(
              "div",
              { class: "attn" },
              icon(a.icon),
              h("div", null, h("div", { class: "attn-title" }, a.title), h("div", { class: "attn-detail" }, a.detail)),
              UI.button("Show", () => this.setActiveSection(a.section, a.go)),
            ),
          )
        : [UI.calm("Nothing needs you. The car is connected and everything is up to date.")];
      const g = UI.group(items.length ? "Needs you" : "All good", body);
      g.dataset.part = "attention";
      return g;
    }

    async _overviewSection(token) {
      await this._refreshCache();
      const c = this.cache;
      const quick = [];
      if (c.audio) {
        const out = UI.row({ id: "quick.sound", title: "Waze's sound", stack: true });
        UI.setControl(
          out,
          UI.segmented(this._soundOptions(), c.audio.output, (v) =>
            UI.save(out, () => this.apiClient.setAudioOutput({ output: v })).then((ok) => {
              if (ok) {
                c.audio.output = v;
                this._renderSidebar();
              }
              return ok;
            }),
            { label: "Where Waze's sound plays" },
          ),
        );
        quick.push(out);
      }
      const car = global.TLCarApp;
      if (this._ifaceNow() === "car" && car && car.getLook) {
        const dn = UI.row({ id: "quick.daynight", title: "Day and night", stack: true });
        UI.setControl(dn, UI.segmented(this._dayNightOptions(), car.getLook().dayNight, (v) => UI.save(dn, () => car.setLook({ dayNight: v }), "Applied")));
        quick.push(dn);
      }
      const ra = UI.row({ id: "quick.restart", title: "Restart Waze", detail: "Back in about a minute" });
      UI.setControl(ra, UI.armed("Restart", "Tap again to restart", (b) => this._restartApp(ra, b)));
      quick.push(ra);

      this._pollTimer = global.setTimeout(() => this._pollOverview(token), 3000);
      return [this._overviewGlance(), this._overviewAttention(), UI.group("Quick controls", quick)];
    }

    /** every 3 s: the glance and what needs the driver, in place (the quick controls stay as they are) */
    async _pollOverview(token) {
      if (token !== this._renderToken) return;
      if (this._visible()) {
        try {
          await this._refreshCache();
        } catch (_e) {
          // the next round tries again
        }
        if (token !== this._renderToken) return;
        const glance = this.content.querySelector('[data-part="glance"]');
        const attention = this.content.querySelector('[data-part="attention"]');
        if (glance) glance.replaceWith(this._overviewGlance());
        if (attention) attention.replaceWith(this._overviewAttention());
      }
      this._pollTimer = global.setTimeout(() => this._pollOverview(token), 3000);
    }

    /** restarts the app (POST /api/appRestart), then the header follows it */
    async _restartApp(r, b) {
      if (b) b.disabled = true;
      const ok = await UI.save(r, () => this.apiClient.restartApp(), "Waze is restarting");
      if (b) b.disabled = false;
      if (ok) this._watchApp();
    }

    // ------------------------------------------------------------------ Display

    /**
     * The picture sent to the car (GET/POST /api/displayState: format, resolution, quality, window
     * size, refresh rate; each change applies at once and the picture reconnects) and the Pi's
     * graphics speed (GET/POST /api/gpuSpeed, teslalinux-set-gpu-speed: at the next device start).
     */
    async _displaySection(token) {
      const api = this.apiClient;
      const [raw, gpu] = await Promise.all([api.fetchDisplayState(), api.fetchGpuSpeed().catch(() => null)]);
      const ds = this._normalizeDisplayState(raw);
      this.cache.display = ds;
      if (gpu) this.cache.gpu = gpu;
      const rerender = () => global.setTimeout(() => token === this._renderToken && this._rerender(), 300);
      // every change goes through the device's current state, so two quick changes do not undo each other
      const post = (r, mutator, text) =>
        UI.save(r, async () => {
          if (!(await this._updateDisplayConfig(mutator))) throw new Error("not saved");
        }, text || "Applied. The picture reconnects in a moment").then((ok) => {
          if (ok) this._renderSidebar();
          return ok;
        });
      const h264 = ds.renderer !== 0;
      const density = (d) => {
        d.density = utils.densityForResolutionPreset(d.resolutionPreset, d.isH264 === 1);
      };

      const rr = UI.row({
        id: "display.renderer",
        title: "Picture format",
        stack: true,
        more: [
          "H.264 needs far less Wi-Fi bandwidth. Motion JPEG shows fewer compression artefacts but needs much more bandwidth.",
          "H.264 (older cars) is for cars whose browser lacks WebCodecs (Tesla software older than 2025.32).",
        ],
        control: UI.segmented(
          [
            { value: 1, label: "H.264", caption: "recommended" },
            { value: 2, label: "H.264", caption: "older cars" },
            { value: 0, label: "Motion JPEG", caption: "default" },
          ],
          ds.renderer,
          (v) =>
            post(rr, (d) => {
              d.renderer = Number(v);
              d.isH264 = d.renderer !== 0 ? 1 : 0;
              density(d);
            }).then((ok) => {
              if (ok) rerender();
              return ok;
            }),
          { label: "Picture format" },
        ),
      });
      const low = (v) => v >= 2 && v <= 4;
      const sr = UI.row({
        id: "display.resolution",
        title: "Resolution",
        stack: true,
        detail: h264 ? "Below 720p needs Motion JPEG" : null,
        control: UI.segmented(
          constants.DISPLAY_RESOLUTION_PRESETS.map((p) => ({
            value: p.value,
            label: p.value === constants.NATIVE_RESOLUTION_PRESET ? "Native" : p.label,
            caption: p.value === constants.NATIVE_RESOLUTION_PRESET ? "sharpest" : p.value === 0 ? "default" : null,
            disabled: h264 && low(p.value),
            why: h264 && low(p.value) ? "Below 720p needs Motion JPEG" : null,
          })),
          ds.resolutionPreset,
          (v) =>
            post(sr, (d) => {
              d.resolutionPreset = Number(v);
              density(d);
            }),
          { label: "Resolution" },
        ),
      });
      const qr = UI.row({
        id: "display.quality",
        title: "Image quality",
        stack: true,
        control: UI.slider({
          min: 40,
          max: 90,
          step: 10,
          value: ds.quality,
          iconLow: "photo",
          label: "Image quality",
          onChange: (v) => post(qr, (d) => (d.quality = v)),
        }),
      });
      const ar = UI.row({
        id: "display.responsive",
        title: "Follow the window size",
        more: "Off keeps the current shape even if the browser window changes size.",
        control: UI.toggle(ds.isResponsive === 1, (on) => post(ar, (d) => (d.isResponsive = on ? 1 : 0)), "Follow the window size"),
      });

      const fr = UI.row({
        id: "display.refresh",
        title: "Refresh rate",
        chips: [UI.CHIP.plain("Experimental")],
        control: UI.segmented(
          constants.DISPLAY_REFRESH_RATE_PRESETS.map((p) => ({ value: p.value, label: p.label, caption: p.value === 30 ? "default" : null })),
          ds.refreshRate,
          (v) => post(fr, (d) => (d.refreshRate = Number(v))),
          { label: "Refresh rate" },
        ),
      });
      const smooth = [fr];
      if (gpu) {
        const now = (g) =>
          "Now " + (g.gpuMHz ? g.gpuMHz + " MHz" : "not measured") + (g.temperatureC ? " at " + Math.round(g.temperatureC) + " °C" : "") +
          (g.throttled && g.throttled !== "0x0" ? ". The Pi has slowed itself down (heat or power)" : "");
        const gr = UI.row({
          id: "display.gpu",
          title: "Graphics speed",
          chips: [UI.CHIP.later()],
          stack: true,
          detail: now(gpu),
          more: "How fast the Pi's graphics chip runs; Waze's map is drawn by it. Faster needs a heatsink, Fastest a heatsink and a fan (it also raises the chip's voltage a little). A Pi that gets too hot slows itself down, so without cooling a faster setting only adds heat.",
          control: UI.segmented(
            [
              { value: "standard", label: "Standard", caption: "500 MHz, default" },
              { value: "faster", label: "Faster", caption: "600 MHz, heatsink" },
              { value: "fastest", label: "Fastest", caption: "700 MHz, fan" },
            ],
            gpu.speed,
            (v) =>
              UI.save(gr, async () => {
                Object.assign(gpu, await api.setGpuSpeed(v));
                this.cache.gpu = gpu;
              }, "Saved. Applies when the device starts again").then((ok) => {
                this._renderSidebar();
                return ok;
              }),
            { label: "Graphics speed" },
          ),
        });
        if (gpu.pendingRestart) UI.status(gr).info("Applies when the device starts again");
        smooth.push(gr);
      }
      return [UI.group("Picture", [rr, sr, qr, ar]), UI.group("Smoothness", smooth)];
    }

    // ------------------------------------------------------------------ Connections

    /** teslalinux-uplink's view of the LTE stick (HiLink web API or ModemManager), when there is one */
    _modem(st) {
      const m = st && st.failover && st.failover.modem;
      return m && m.type && m.type !== "none" ? m : null;
    }

    /**
     * teslalinux-uplink found the LTE stick stuck in its USB storage mode (it starts as a USB drive and
     * usb_modeswitch did not switch it): what happens about it, in plain words; null while it is not stuck
     * (or only just appeared, when a good switch takes a second)
     */
    _stuckStick(m, now) {
      const s = m && m.storageMode;
      if (!s || (s.state === "waiting" && /^starting/.test(s.status || ""))) return null;
      const head = "The LTE stick started as a USB drive and did not switch to its modem mode. ";
      const times = s.cycles === 1 ? "once" : s.cycles + " times";
      if (s.state === "blocked") return head + "It cannot be restarted: " + (s.status || "").replace(/^.*no USB power cycle: /, "") + ".";
      if (s.state === "power-cycling") return head + "Switching the USB power off and on now; the backup Wi-Fi drops for a few seconds.";
      if (s.state === "cycled") return head + "The USB power was switched off and on; waiting for it to start.";
      const next = typeof s.nextCycleAt === "number" && now ? " Trying again in " + UI.fmt.dur(Math.max(0, s.nextCycleAt - now)) + "." : "";
      return head + "The USB power was switched off and on " + times + "." + next;
    }

    /**
     * The internet now (GET /api/status, refreshed every 3 s): the uplink in use, the LTE modem
     * (operator, bars, this month's data), the backup uplinks' own checks, a note while another
     * uplink stands in for LTE or the SIM has a problem, and the modem's radio and recovery folded.
     */
    _internetBlock(st) {
      const fmt = UI.fmt;
      const net = st.internet || {};
      const up = st.uplink;
      const fo = st.failover || {};
      const m = this._modem(st);
      const kindName = { lte: "LTE modem", wifi: "Backup Wi-Fi", ethernet: "Ethernet", phone: "Phone (USB tethering)" };
      const kinds = { lte: "LTE", wifi: "Backup Wi-Fi", ethernet: "Ethernet", phone: "Phone" };
      const cap = (s) => (s ? s.charAt(0).toUpperCase() + s.slice(1) : "");
      const data = (d) => (d ? "↓ " + (d.down || "?") + "  ↑ " + (d.up || "?") : "");
      const notes = [];
      if (fo.reason && fo.activeKind) {
        const why = fo.reason.replace(/^lte\b/, "LTE").replace(/^wifi\b/, "Wi-Fi");
        notes.push(UI.warnbox("Using " + (kinds[fo.activeKind] || fo.activeKind) + " instead: " + why + (fo.since && st.time ? ", since " + fmt.dur(Math.max(0, st.time - fo.since)) : "") + ".", "alt_route"));
      }
      if (m && m.simProblem) notes.push(UI.warnbox("SIM card: " + m.simProblem, "sim_card_alert"));
      const stuck = this._stuckStick(m, st.time);
      if (stuck) notes.push(UI.warnbox(stuck, "usb"));
      const storage = m && m.type === "storage-mode";
      const facts = [
        ["Internet", net.reachable ? "Online" + (typeof net.latencyMs === "number" ? ", " + net.latencyMs + " ms" : "") : "No internet", net.reachable ? "ok" : "bad"],
        up ? ["Through", (kindName[up.kind] || up.kind) + " (" + up.interface + ")"] : ["Through", "No route to the internet", "bad"],
        up && up.address ? ["Address", up.address + (up.gateway ? ", gateway " + up.gateway : "")] : null,
      ];
      if (storage) {
        facts.push(["LTE modem", stuck ? "Stuck as a USB drive" : "Starting", stuck ? "bad" : null]);
      } else if (m) {
        const tech = m.networkType && m.technology && m.technology !== m.networkType ? m.networkType + " (" + m.technology + ")" : m.networkType || m.technology;
        facts.push(["Operator", [m.operator || "Unknown", tech, m.roaming ? "roaming" : ""].filter(Boolean).join(", ")]);
        if (typeof m.bars === "number") facts.push(["Signal", UI.signalBars(m.bars), m.bars >= 3 ? "ok" : m.bars >= 2 ? "warn" : "bad"]);
        else if (typeof m.signal === "number") facts.push(["Signal", m.signal + " %"]);
        if (m.connection) facts.push(["Modem", cap(m.connection) + (m.connection === "connected" && typeof m.connectedSeconds === "number" ? " for " + fmt.dur(m.connectedSeconds) : "")]);
        if (m.month) facts.push(["This month", data(m.month)]);
      } else if (st.modem) {
        const o = st.modem;
        facts.push(["LTE modem", [o.operator, o.model, o.technology, o.state, typeof o.signalPercent === "number" ? "signal " + o.signalPercent + " %" : ""].filter(Boolean).join(", ") || "Detected"]);
      } else {
        facts.push(["LTE modem", "Not detected"]);
      }
      if ((!m || !m.month) && up && typeof up.rxBytes === "number") facts.push(["Data since start", "↓ " + fmt.bytes(up.rxBytes) + "  ↑ " + fmt.bytes(up.txBytes)]);
      // the other uplinks' own checks (the one in use is above)
      (Array.isArray(fo.candidates) && fo.candidates.length > 1 ? fo.candidates : []).forEach((c) => {
        if (up && c.iface === up.interface) return;
        const on = c.kind === "wifi" && fo.wifi && fo.wifi.ssid ? fo.wifi.ssid + ": " : "";
        facts.push([(kinds[c.kind] || c.kind) + " (" + c.iface + ")", on + (c.ok ? "Ready, online" + (typeof c.latencyMs === "number" ? ", " + c.latencyMs + " ms" : "") : "No internet"), c.ok ? "ok" : null]);
      });
      const parts = [notes, UI.facts(facts)];
      if (m) {
        const radio = m.radio || {};
        const actionNames = {
          reconnect: m.type === "hilink" ? "mobile data off and on" : "reconnect",
          "modem-reset": "modem restart",
          "usb-reenumerate": "USB re-enumeration",
          "usb-power-cycle": "USB power cycle",
        };
        const details = UI.facts([
          m.model ? ["Model", m.model] : null,
          radio.rsrp ? ["RSRP", radio.rsrp] : null,
          radio.rsrq ? ["RSRQ", radio.rsrq] : null,
          radio.sinr ? ["SINR", radio.sinr] : null,
          radio.rssi && !radio.rsrp ? ["RSSI", radio.rssi] : null,
          radio.band ? ["Band", radio.band] : null,
          radio.cellId ? ["Cell", radio.cellId] : null,
          m.session ? ["This session", data(m.session)] : null,
          ["Recovery", (m.recovery === "off" ? "Off" : "Automatic") + (m.status ? ", " + m.status : "")],
          m.lastAction
            ? ["Last step", (actionNames[m.lastAction] || m.lastAction) + ", " + fmt.dur(Math.max(0, st.time - m.lastActionAt)) + " ago" + (m.lastResult && m.lastResult !== "done" ? " (" + m.lastResult + ")" : "")]
            : null,
          typeof m.nextActionAt === "number" && m.nextActionAt > st.time ? ["Next step", "in " + fmt.dur(m.nextActionAt - st.time)] : null,
          // the last recovery step and the stuck-stick fix need it; many boards (CM4 carriers) cannot
          m.usbPower ? ["USB power cycle", "Not possible: " + m.usbPower, "warn"] : null,
          m.storageCycle && typeof m.storageCycle.at === "number"
            ? [
                "USB restart",
                fmt.dur(Math.max(0, st.time - m.storageCycle.at)) + " ago, the stick had stayed a USB drive" +
                  (typeof m.storageCycle.switchedAfter === "number" ? "; it started " + fmt.dur(m.storageCycle.switchedAfter) + " later" : "") +
                  (m.storageCycle.result && m.storageCycle.result !== "done" ? " (" + m.storageCycle.result + ")" : ""),
              ]
            : null,
        ]);
        const [b, body] = UI.disclosure("Modem details", details, this._modemOpen);
        b.addEventListener("click", () => (this._modemOpen = !body.hidden));
        parts.push(b, body);
      }
      return h("div", { "data-id": "net.internet", "data-part": "internet" }, parts);
    }

    /** who is on the hotspot (GET /api/status), refreshed with the internet */
    _clientsBlock(st) {
      const hot = st.hotspot || {};
      const clients = Array.isArray(hot.clients) ? hot.clients : [];
      const fmt = UI.fmt;
      let items;
      if (hot.present === false) {
        items = [UI.listItem("wifi_off", "No hotspot", "This device has no Wi-Fi interface (" + (hot.interface || "wlan0") + "): the car cannot join it. The settings below are kept for a device with one.")];
      } else if (!clients.length) {
        items = [UI.listItem("wifi_find", "Nothing connected", hot.running === false ? "The hotspot is not running" : "The hotspot is on and waiting for the car")];
      } else {
        items = clients.map((c) =>
          UI.listItem(
            "directions_car",
            c.name || c.ip || c.mac,
            [
              typeof c.signalDbm === "number" ? fmt.signal(c.signalDbm) + " signal (" + c.signalDbm + " dBm)" : "",
              typeof c.txMbps === "number" ? Math.round(c.txMbps) + " Mbit/s" : "",
              typeof c.connectedSeconds === "number" ? "for " + fmt.dur(c.connectedSeconds) : "",
              c.ip && c.name ? c.ip : "",
            ].filter(Boolean).join(", "),
            UI.CHIP.plain("Connected"),
          ),
        );
      }
      return h("div", { "data-part": "clients" }, items);
    }

    async _connectionsSection(token) {
      const api = this.apiClient;
      const [net, conf, st] = await Promise.all([api.fetchNetwork(), this._fetchSystemConfigurationSafe(), api.fetchStatus().catch(() => this.cache.status || null)]);
      if (st) this.cache.status = st;
      const draft = this.netDraft;
      const redraw = () => token === this._renderToken && this._rerender();
      const hot = net.hotspot || {};
      const out = [];

      if (st) out.push(UI.group("Internet now", [this._internetBlock(st)]));

      // --- the hotspot: edits wait in the draft until Apply
      const val = (k, v) => (k in draft.hotspot ? draft.hotspot[k] : v);
      const ss = UI.row({
        id: "net.ssid",
        title: "Hotspot name",
        control: UI.tapValue(val("ssid", hot.ssid || ""), async () => {
          const v = await UI.editText({
            title: "Hotspot name",
            value: val("ssid", hot.ssid || ""),
            maxLength: 32,
            help: "The car shows this name in its Wi-Fi list.",
            validate: (x) => {
              const n = new TextEncoder().encode(x).length;
              return n < 1 || n > 32 ? "The name needs 1 to 32 characters." : "";
            },
          });
          if (v == null) return;
          if (v === (hot.ssid || "")) delete draft.hotspot.ssid;
          else draft.hotspot.ssid = v;
          redraw();
        }, "ssid" in draft.hotspot),
      });
      const pw = UI.row({
        id: "net.psk",
        title: "Hotspot password",
        detail: "The car needs the new one typed in once",
        control: UI.tapValue("psk" in draft.hotspot ? "New password" : hot.pskSet ? "Set" : "None", async () => {
          const v = await UI.editText({
            title: "Hotspot password",
            type: "password",
            maxLength: 64,
            placeholder: "At least 8 characters",
            help: "After applying, enter it in the car's Wi-Fi settings: the car forgets the old one.",
            validate: (x) => (/^(?:[\x20-\x7e]{8,63}|[0-9A-Fa-f]{64})$/.test(x) ? "" : "The password needs 8 to 63 characters (letters, digits, symbols)."),
          });
          if (v == null) return;
          draft.hotspot.psk = v;
          redraw();
        }, "psk" in draft.hotspot),
      });
      const co = UI.row({
        id: "net.country",
        title: "Country",
        more: "Decides which channels and power the hotspot may use.",
        control: UI.tapValue(val("country", hot.country || "") || "Not set", async () => {
          const v = await UI.editText({
            title: "Country",
            value: val("country", hot.country || ""),
            maxLength: 2,
            placeholder: "US",
            help: "Two letters, such as US, GB, DE or IL.",
            transform: (x) => x.toUpperCase().replace(/[^A-Z]/g, ""),
            validate: (x) => (/^[A-Z]{2}$/.test(x) ? "" : "The country is a two-letter code."),
          });
          if (v == null) return;
          if (v === (hot.country || "")) delete draft.hotspot.country;
          else draft.hotspot.country = v;
          redraw();
        }, "country" in draft.hotspot),
      });
      const bands = constants.SOFT_AP_BANDS;
      const savedBand = conf ? utils.mapSoftApBandFromConfig(conf) : bands[1];
      const band = draft.props.band || savedBand;
      const setBand = (b) => {
        if (b.key === savedBand.key) delete draft.props.band;
        else draft.props.band = b;
        redraw();
        return true;
      };
      const bd = UI.row({
        id: "net.band",
        title: "Band",
        stack: true,
        detail: band.band === 2 ? null : "Shares the radio with Bluetooth: Bluetooth audio can drop out",
        more: "If the car does not see the network, try another channel. Allowed channels differ by country.",
        control: UI.segmented(
          [
            { value: 1, label: "2.4 GHz" },
            { value: 2, label: "5 GHz", caption: "recommended" },
          ],
          band.band,
          (v) => setBand(Number(v) === 1 ? bands[0] : savedBand.band === 2 ? savedBand : bands[1]),
          { label: "Band" },
        ),
      });
      if (draft.props.band) bd.querySelector(".seg").classList.add("is-changed");
      const hotRows = [this._clientsBlock(st || { hotspot: hot }), ss, pw, co, bd];
      if (band.band === 2) {
        const ch = UI.row({
          id: "net.channel",
          title: "5 GHz channel",
          stack: true,
          control: UI.segmented(
            bands.filter((b) => b.band === 2).map((b) => ({ value: b.key, label: String(b.channel), caption: b.channel === 36 ? "default" : null })),
            band.key,
            (k) => setBand(bands.find((b) => b.key === k)),
            { label: "5 GHz channel" },
          ),
        });
        hotRows.push(ch);
      }
      out.push(UI.group("Hotspot", hotRows));

      // --- what the car may reach without internet (upstream's offline mode), also in the draft
      if (conf) {
        const props = [
          ["offline", "persist.tesla-android.offline-mode.is_enabled"],
          ["telemetry", "persist.tesla-android.offline-mode.telemetry.is_enabled"],
          ["firmware", "persist.tesla-android.offline-mode.tesla-firmware-downloads"],
        ];
        const saved = {};
        props.forEach(([k, key]) => (saved[k] = utils.toInt(conf[key], 0) === 1));
        const propToggle = (k, label) =>
          UI.toggle(k in draft.props ? draft.props[k] : saved[k], (on) => {
            if (on === saved[k]) delete draft.props[k];
            else draft.props[k] = on;
            redraw();
            return true;
          }, label);
        out.push(
          UI.group(
            "Without internet",
            [
              UI.row({
                id: "net.offline",
                title: "Keep the car on the hotspot",
                detail: "Even without internet",
                more: "The car leaves Wi-Fi networks that have no internet. With this on it stays connected to this device even without LTE; the car's own online features (the Tesla app, live traffic of the car's maps) then need the LTE modem.",
                control: propToggle("offline", "Keep the car on the hotspot"),
              }),
              UI.row({ id: "net.telemetry", title: "Tesla telemetry", control: propToggle("telemetry", "Tesla telemetry") }),
              UI.row({ id: "net.firmware", title: "Tesla software downloads", detail: "Off: the car still sees that an update exists", control: propToggle("firmware", "Tesla software downloads") }),
            ],
            "Turning telemetry and downloads off saves mobile data. These apply when the hotspot restarts.",
          ),
        );
      }

      // --- LTE: the APN for a ModemManager modem; a HiLink stick keeps its own
      const lte = net.lte;
      this._lteManaged = Boolean(lte && lte.managed);
      let lteRows;
      if (lte && lte.managed) {
        const lv = (k, v) => (k in draft.lte ? draft.lte[k] : v);
        const lteText = (k, title, o) => {
          const r = UI.row({
            id: o.id,
            title,
            detail: o.detail || null,
            control: UI.tapValue(o.type === "password" ? ("password" in draft.lte ? "New password" : o.empty) : lv(k, lte[k] || "") || o.empty, async () => {
              const v = await UI.editText({ title, value: o.type === "password" ? "" : lv(k, lte[k] || ""), type: o.type, maxLength: o.maxLength || 64, placeholder: o.empty, help: o.help, validate: o.validate });
              if (v == null) return;
              if (o.type !== "password" && v === (lte[k] || "")) delete draft.lte[k];
              else draft.lte[k] = v;
              redraw();
            }, k in draft.lte),
          });
          return r;
        };
        const lteToggle = (k, id, title, detail) => {
          const saved = k === "enabled" ? lte.enabled !== false : Boolean(lte[k]);
          return UI.row({
            id,
            title,
            detail,
            control: UI.toggle(lv(k, saved), (on) => {
              if (on === saved) delete draft.lte[k];
              else draft.lte[k] = on;
              redraw();
              return true;
            }, title),
          });
        };
        lteRows = [
          lteText("apn", "APN", {
            id: "net.apn",
            empty: "Automatic",
            detail: "From your mobile operator",
            maxLength: 63,
            help: "Empty uses the modem's default.",
            validate: (x) => (/^(?:[A-Za-z0-9][A-Za-z0-9.-]{0,62})?$/.test(x) ? "" : "The APN may hold letters, digits, dots and dashes."),
          }),
          lteText("user", "APN user name", { id: "net.apnuser", empty: "None" }),
          lteText("password", "APN password", { id: "net.apnpass", empty: lte.passwordSet ? "Set" : "None", type: "password" }),
          lteToggle("roaming", "net.roaming", "Data roaming", "Use other operators' networks abroad"),
          lteToggle("enabled", "net.mobiledata", "Mobile data", "Off: no internet over LTE"),
        ];
      } else {
        lteRows = [
          UI.row({
            id: "net.apn",
            title: "APN",
            detail: net.modemDetected
              ? "This modem keeps its own APN. Change it on the modem's web page from a computer."
              : "Plug in a USB LTE modem. HiLink sticks work without settings.",
            more: net.modemDetected
              ? "This modem is a router of its own (a USB \"HiLink\" stick such as the Huawei E3372): it keeps its APN itself. Change it on the modem's own web page from a computer, or plug it into one once."
              : "Modems that need an APN here are managed by ModemManager.",
            control: UI.value(net.modemDetected ? "Set on the modem" : "No modem"),
          }),
        ];
      }
      out.push(UI.group("LTE", lteRows));

      // --- backup Wi-Fi: applies at once
      if (net.wifi) out.push(UI.group("Backup Wi-Fi", this._backupWifiRows(net.wifi, redraw)));

      // --- the apply bar for the hotspot and LTE draft
      const n = Object.keys(draft.hotspot).length + Object.keys(draft.lte).length + Object.keys(draft.props).length;
      if (n) {
        const bar = h(
          "div",
          { class: "pendbar", role: "region", "aria-label": "Changes not applied" },
          h("div", { class: "pendbar-text" }, h("b", null, n + (n === 1 ? " change" : " changes") + " not applied"), "Apply now restarts the hotspot: the car drops off for a few seconds."),
          UI.button("Discard", () => {
            this.netDraft = { hotspot: {}, lte: {}, props: {} };
            redraw();
          }, "btn--ghost"),
          UI.button("At next start", () => this._applyNetwork("boot", net, bar), "btn--ghost"),
          UI.button("Apply now", () => this._applyNetwork("now", net, bar), "btn--primary"),
        );
        out.push(bar);
      }

      // the internet and the car on the hotspot follow every 3 s
      const poll = async () => {
        if (token !== this._renderToken) return;
        if (this._visible()) {
          try {
            const fresh = await api.fetchStatus();
            if (token !== this._renderToken) return;
            this.cache.status = fresh;
            const a = this.content.querySelector('[data-part="internet"]');
            const b = this.content.querySelector('[data-part="clients"]');
            if (a) a.replaceWith(this._internetBlock(fresh));
            if (b) b.replaceWith(this._clientsBlock(fresh));
            this._renderSidebar();
          } catch (_e) {
            // the next round tries again
          }
        }
        this._pollTimer = global.setTimeout(poll, 3000);
      };
      this._pollTimer = global.setTimeout(poll, 3000);
      return out;
    }

    /** the hotspot and LTE draft to the device: upstream's band and offline settings, then POST /api/network */
    async _applyNetwork(when, net, bar) {
      const draft = this.netDraft;
      const api = this.apiClient;
      bar.querySelectorAll("button").forEach((b) => (b.disabled = true));
      const text = bar.querySelector(".pendbar-text");
      text.replaceChildren(h("b", null, "Applying…"), "Saving the settings on the device.");
      UI.busy(1);
      try {
        const p = draft.props;
        if (p.band) {
          await api.setSoftApBand(p.band.band);
          await api.setSoftApChannelWidth(p.band.channelWidth);
          await api.setSoftApChannel(p.band.channel);
        }
        if ("offline" in p) await api.setOfflineModeState(p.offline ? 1 : 0);
        if ("telemetry" in p) await api.setOfflineModeTelemetryState(p.telemetry ? 1 : 0);
        if ("firmware" in p) await api.setOfflineModeTeslaFirmwareDownloads(p.firmware ? 1 : 0);
        const req = { when, hotspot: Object.assign({}, draft.hotspot) };
        const lte = net.lte;
        if (lte && lte.managed && Object.keys(draft.lte).length) {
          req.lte = Object.assign({ apn: lte.apn || "", user: lte.user || "", roaming: Boolean(lte.roaming), enabled: lte.enabled !== false }, draft.lte);
        }
        const r = await api.setNetwork(req);
        if (!r.ok) throw UI.userError(r.message || "the device did not answer");
        this.netDraft = { hotspot: {}, lte: {}, props: {} };
        await this._handleSystemConfigurationChanged().catch(() => null);
        UI.toast(when === "now" ? "Applied. The hotspot restarts; the car reconnects by itself." : "Saved. The hotspot changes apply at the next start.");
        await this._rerender();
      } catch (e) {
        text.replaceChildren(h("b", null, "Not applied"), (e && e.userMessage ? e.userMessage : "The device did not answer") + ". Your changes are kept.");
        bar.querySelectorAll("button").forEach((b) => (b.disabled = false));
      } finally {
        UI.busy(-1);
      }
    }

    /**
     * A second Wi-Fi card joins a saved network (home, a phone's hotspot, a garage) and
     * host/teslalinux-uplink routes through it when LTE has no internet, or whenever it is
     * connected. POST /api/network {"wifi": ...} applies at once; GET /api/wifiScan.
     */
    _backupWifiRows(wifi, redraw) {
      const api = this.apiClient;
      const send = async (ops) => {
        const r = await api.setNetwork({ when: "now", wifi: ops });
        if (!r.ok) throw UI.userError("Not saved: " + (r.message || "the device did not answer") + ".");
      };
      const enabled = wifi.enabled !== false;
      const state = !wifi.present
        ? "Plug in a second (USB) Wi-Fi card. Saved networks are used as soon as one is in."
        : wifi.ssid
          ? "Connected to " + wifi.ssid + (typeof wifi.signalDbm === "number" ? ", " + UI.fmt.signal(wifi.signalDbm) + " signal (" + wifi.signalDbm + " dBm)" : "")
          : enabled && wifi.cardState === "connecting"
            ? "Joining a saved network…"
            : "A second Wi-Fi card (" + (wifi.iface || "USB") + ") joins a saved network when it is in range";
      const wst = UI.row({
        id: "net.backup",
        title: "Use backup Wi-Fi",
        detail: state,
        more: "For places where LTE has no signal, such as an underground car park. The built-in Wi-Fi (" + (wifi.apIface || "wlan0") + ") runs the hotspot, so this needs a second card.",
        control: UI.toggle(enabled, (on) =>
          UI.save(wst, () => send({ enabled: on }), on ? "On" : "Off").then((ok) => {
            if (ok) global.setTimeout(redraw, 400);
            return ok;
          }), "Use backup Wi-Fi"),
      });
      const wp = UI.row({
        id: "net.prefer",
        title: "Use it",
        stack: true,
        dim: !enabled,
        more: "When LTE fails: LTE stays in use; a saved Wi-Fi takes over within about 10 seconds of LTE losing the internet, and LTE comes back once it works again. Always when in range: Wi-Fi first, to save mobile data at home or on a phone's hotspot.",
        control: UI.segmented(
          [
            { value: "lte", label: "When LTE fails", caption: "default" },
            { value: "wifi", label: "Always when in range", caption: "saves mobile data" },
          ],
          wifi.prefer === "wifi" ? "wifi" : "lte",
          (v) => UI.save(wp, () => send({ prefer: v })),
          { label: "When to use backup Wi-Fi" },
        ),
      });
      const networks = Array.isArray(wifi.networks) ? wifi.networks : [];
      const canConnect = Boolean(wifi.present) && enabled;
      // each saved network's row; Connect's progress and result are painted into it in place
      this._wifiCurrent = wifi.ssid ? { ssid: wifi.ssid, signalDbm: wifi.signalDbm } : null;
      this._wifiRows = networks.map((n) => {
        const row = {
          ssid: n.ssid,
          base: (n.open ? "Open network" : n.keyOnly ? "Key saved (WPA2 only)" : "Password saved") + (n.hidden ? ", hidden" : ""),
          canConnect,
          detail: h("div", { class: "list-detail", "aria-live": "polite" }),
          connect: UI.button("Connect", () => this._connectWifi(n.ssid, redraw)),
        };
        const remove = UI.armed("Remove", "Tap again", async (b) => {
          b.disabled = true;
          try {
            await send({ remove: n.ssid });
            UI.toast("Removed “" + n.ssid + "”.");
            redraw();
          } catch (e) {
            UI.toast(e.userMessage || "Not removed: the device did not answer.");
            b.disabled = false;
          }
        });
        row.el = h("div", { class: "list-item" }, icon(n.open ? "lock_open" : "lock"), h("div", null, h("div", { class: "list-name" }, n.ssid), row.detail), h("div", { class: "list-end" }, row.connect, remove));
        this._paintWifiRow(row);
        return row;
      });
      // the last attempt (also one started before this page opened), followed while it runs
      if (networks.length && wifi.present) this._watchWifiConnect(redraw);
      const add = h(
        "div",
        { class: "row-actions", "data-id": "net.add" },
        UI.button("Add a network", () =>
          this._addNetwork(wifi, canConnect).then((ssid) => {
            if (!ssid) return;
            if (canConnect) this._wifiAttempt = { ssid, state: "connecting" };
            redraw();
            if (canConnect) this._connectWifi(ssid, redraw);
          }), null, "add"),
      );
      return [wst, wp, networks.length ? h("div", null, this._wifiRows.map((r) => r.el)) : h("p", { class: "group-note" }, "No networks saved yet."), add];
    }

    /** One saved network's state: connecting, connected (with the signal), the last failure and why, or idle. */
    _paintWifiRow(row) {
      const a = this._wifiAttempt && this._wifiAttempt.ssid === row.ssid ? this._wifiAttempt : null;
      const cur = this._wifiCurrent && this._wifiCurrent.ssid === row.ssid ? this._wifiCurrent : null;
      const connected = Boolean(cur) || Boolean(a && a.state === "connected" && !this._wifiCurrent);
      row.detail.className = "list-detail";
      row.connect.disabled = false;
      row.connect.hidden = !row.canConnect || connected;
      row.connect.lastChild.textContent = "Connect";
      if (a && a.state === "connecting") {
        row.detail.replaceChildren(h("span", { class: "spin" }), "Connecting…");
        row.connect.disabled = true;
        row.connect.lastChild.textContent = "Connecting…";
      } else if (connected) {
        const sig = cur && typeof cur.signalDbm === "number"
          ? ", " + UI.fmt.signal(cur.signalDbm) + " signal (" + cur.signalDbm + " dBm)"
          : a && typeof a.signalPercent === "number" ? ", signal " + a.signalPercent + " %" : "";
        row.detail.replaceChildren("Connected" + sig);
        row.detail.classList.add("is-ok");
      } else if (a && a.state === "failed" && Date.now() / 1000 - (a.finished || 0) < 900) {
        row.detail.replaceChildren("Not connected: " + (a.message || "no reason given") + ".");
        row.detail.classList.add("is-err");
        row.connect.lastChild.textContent = "Try again";
      } else {
        row.detail.replaceChildren(row.base);
      }
    }

    _paintWifiRows() {
      (this._wifiRows || []).forEach((r) => this._paintWifiRow(r));
    }

    /** Connect: POST /api/wifiConnect starts the attempt; GET /api/wifiConnect follows it. */
    async _connectWifi(ssid, redraw) {
      this._wifiAttempt = { ssid, state: "connecting" };
      this._paintWifiRows();
      const r = await this.apiClient.connectWifi(ssid);
      if (!r.ok) {
        this._wifiAttempt = { ssid, state: "failed", message: r.message || "the device did not answer", finished: Date.now() / 1000 };
        this._paintWifiRows();
        return;
      }
      this._watchWifiConnect(redraw, true);
    }

    /** Polls the attempt while it runs (and the page shows its rows); redraws once when one ends. */
    async _watchWifiConnect(redraw, started) {
      if (this._wifiWatching) {
        // a poll already runs (it may have read the state from before this attempt): it goes on
        if (started) this._wifiWatchAgain = true;
        return;
      }
      this._wifiWatching = true;
      this._wifiWatchAgain = false;
      let ran = Boolean(started);
      const until = Date.now() + 120000;
      try {
        for (;;) {
          let r = null;
          try {
            r = await this.apiClient.fetchWifiConnect();
          } catch (_e) {
            r = null;
          }
          if (r && typeof r === "object") {
            this._wifiAttempt = r.attempt || null;
            this._wifiCurrent = r.current || null;
            this._paintWifiRows();
          }
          const a = this._wifiAttempt;
          const shown = (this._wifiRows || []).some((row) => row.el.isConnected);
          const again = this._wifiWatchAgain;
          this._wifiWatchAgain = false;
          if ((!a || a.state !== "connecting") && !again) break;
          if (!shown || Date.now() > until) break;
          ran = true;
          await new Promise((res) => global.setTimeout(res, 1500));
        }
      } finally {
        this._wifiWatching = false;
      }
      const a = this._wifiAttempt;
      if (!ran || !a || a.state === "connecting") return;
      if (a.state === "connected") UI.toast("Connected to “" + a.ssid + "”.");
      // the header and the uplink follow a moment later (teslalinux-uplink checks every 5 s)
      global.setTimeout(redraw, 1500);
    }

    /** the "Add a network" sheet: scan, name, password, hidden; resolves with the saved network's name
     * (the caller connects to it) or a falsy value */
    _addNetwork(wifi, willConnect) {
      const name = h("input", { id: "add-ssid", class: "settings-field-input", maxlength: 32, placeholder: "Network name", autocomplete: "off", spellcheck: "false", autocapitalize: "off" });
      const pass = h("input", { id: "add-psk", class: "settings-field-input", type: "password", maxlength: 64, placeholder: "Empty for an open network", autocomplete: "new-password" });
      let hidden = false;
      const err = h("div", { class: "field-err", "aria-live": "assertive" });
      const found = h("div", { role: "listbox" });
      const scan = UI.button("Scan for networks", null, null, "wifi_tethering");
      if (!wifi.present) {
        scan.disabled = true;
        scan.title = "No second Wi-Fi card";
      }
      scan.addEventListener("click", async () => {
        scan.disabled = true;
        scan.replaceChildren(h("span", { class: "spin" }), h("span", null, "Scanning…"));
        let r;
        try {
          r = await this.apiClient.scanWifi();
        } catch (_e) {
          r = { networks: [], error: "scan failed" };
        }
        scan.disabled = false;
        scan.replaceChildren(icon("refresh"), h("span", null, "Scan again"));
        const list = Array.isArray(r.networks) ? r.networks : [];
        found.replaceChildren(
          ...(list.length
            ? list.map((n) =>
                h(
                  "button",
                  {
                    class: "pick",
                    type: "button",
                    role: "option",
                    onclick: () => {
                      name.value = n.ssid;
                      pass.value = "";
                      found.querySelectorAll(".pick").forEach((x) => x.setAttribute("aria-selected", String(x.textContent.startsWith(n.ssid))));
                      if (n.secure) pass.focus();
                    },
                  },
                  h(
                    "span",
                    null,
                    n.ssid,
                    h("small", null, "  " + [n.band, n.secure ? "secured" : "open", typeof n.signalDbm === "number" ? UI.fmt.signal(n.signalDbm) : typeof n.signalPercent === "number" ? n.signalPercent + " %" : ""].filter(Boolean).join(", ")),
                  ),
                  icon(this._wifiBars(n)),
                ),
              )
            : [h("p", { class: "results-empty" }, r.error ? "The scan failed." : "No networks found.")]),
        );
      });
      const ok = h("button", { class: "btn btn--primary", type: "button" }, "Save network");
      const hiddenRow = UI.row({ title: "Hidden network", control: UI.toggle(false, (on) => {
        hidden = on;
        return true;
      }, "Hidden network") });
      const p = UI.openSheet({
        title: "Add a network",
        body: [
          h("div", { class: "sheet-scan" }, scan),
          found,
          h("div", { class: "field" }, h("label", { for: "add-ssid" }, "Network name"), name),
          h("div", { class: "field" }, h("label", { for: "add-psk" }, "Password"), pass, h("div", { class: "field-help" }, "Kept on this device only, readable by the system alone. WPA3 networks need the password itself.")),
          hiddenRow,
          err,
        ],
        actions: [h("button", { class: "btn", type: "button", onclick: () => p.finish(false) }, "Cancel"), ok],
      });
      ok.addEventListener("click", async () => {
        name.classList.remove("invalid");
        pass.classList.remove("invalid");
        const n = new TextEncoder().encode(name.value).length;
        if (n < 1 || n > 32) {
          err.textContent = "The network name needs 1 to 32 characters.";
          name.classList.add("invalid");
          return;
        }
        if (pass.value && !/^(?:[\x20-\x7e]{8,63}|[0-9A-Fa-f]{64})$/.test(pass.value)) {
          err.textContent = "A Wi-Fi password has 8 to 63 characters.";
          pass.classList.add("invalid");
          return;
        }
        ok.disabled = true;
        ok.textContent = "Saving…";
        let r;
        try {
          r = await this.apiClient.setNetwork({ when: "now", wifi: { add: { ssid: name.value, psk: pass.value, hidden, open: pass.value === "" } } });
        } catch (_e) {
          r = { ok: false };
        }
        if (!r.ok) {
          err.textContent = "Not saved: " + (r.message || "the device did not answer") + ".";
          ok.disabled = false;
          ok.textContent = "Save network";
          return;
        }
        UI.toast(willConnect ? "Saved “" + name.value + "”. Connecting…" : "Saved “" + name.value + "”. The backup card joins it when it is in range.");
        p.finish(name.value);
      });
      return p;
    }

    // ------------------------------------------------------------------ Updates

    /**
     * Waze's own updates (tools/appupdate/teslalinux-appupdate, docs/APP-UPDATES.md: GET
     * /api/appUpdate, POST /api/appUpdateSettings, /api/appUpdateCheck, /api/appUpdateUpload), the
     * operating system underneath (apt on Pi OS, apk on Alpine: GET/POST /api/systemUpdate; while a
     * run goes on a progress bar follows the package manager and a terminal streams its output),
     * and the Teslalinux release installed (GET /api/status system.teslalinux).
     */
    async _updatesSection(token) {
      const api = this.apiClient;
      const [info, su, st, ru] = await Promise.all([
        api.fetchAppUpdate().catch(() => null),
        api.fetchSystemUpdate().catch(() => null),
        api.fetchStatus().catch(() => null),
        api.fetchReleaseUpdate().catch(() => null),
      ]);
      if (!info && !su && !st && !ru) throw new Error("no answer");
      if (info) this.cache.appUpdate = info;
      if (su) this.cache.sys = su;
      if (st) this.cache.status = st;
      if (ru) this.cache.release = ru;
      const out = [];
      if (st || ru) out.push(this._releaseUpdateGroup(token, st, ru));
      if (info) out.push(...this._wazeUpdateGroups(token, info));
      if (info && this._autoUpdates()) out.push(this._mobileDataGroup(token, info));
      if (su) out.push(this._systemUpdateGroup(token, su));
      return out;
    }

    /**
     * One setting for Teslalinux and Waze updates: download over LTE too (the app updater's
     * allowMetered, which host/teslalinux-release-update reads as well). A Wi-Fi or Ethernet that is
     * connected beside LTE is used first either way.
     */
    _mobileDataGroup(token, info) {
      const set = info.settings || {};
      const row = UI.row({
        id: "upd.lte",
        title: "Update over mobile data",
        detail: "Teslalinux and Waze updates download over LTE too. Off: they wait for Wi-Fi or Ethernet.",
        control: UI.toggle(set.allowMetered === true, (on) =>
          UI.save(row, () => this.apiClient.setAppUpdateSettings({ allowMetered: on })).then((ok) => {
            if (ok) global.setTimeout(() => token === this._renderToken && this._rerender(), 300);
            return ok;
          }), "Update over mobile data"),
      });
      return UI.group("Mobile data", [row]);
    }

    /**
     * Teslalinux itself (host/teslalinux-release-update, docs/SYSTEM-UPDATES.md: GET/POST
     * /api/releaseUpdate): the installed release, a newer one with its date and notes, the download
     * (Wi-Fi or Ethernet only unless asked, with its progress), the install (while the car is not
     * connected, at the next start, or Install now), the health check and going back.
     */
    _releaseUpdateGroup(token, st, ru) {
      const v = this._teslalinuxVersion(st || (ru && ru.installed ? { system: { teslalinux: ru.installed } } : null));
      const fmt = UI.fmt;
      const verRow = UI.row({ id: "upd.version", title: "Installed version", detail: v.detail, control: UI.value(v.text) });
      if (!ru) return UI.group("Teslalinux", [verRow]);
      if (!ru.supported) {
        return UI.group("Teslalinux", [verRow, UI.row({ id: "upd.release", title: "Release updates", detail: ru.unsupportedReason || "Not available on this device.", control: UI.value("Not available") })]);
      }
      const name = (tag) => String(tag || "").replace(/^v/, "");
      const act = async (request, btn, row, okText) => {
        if (btn) btn.disabled = true;
        const r = await this.apiClient.releaseUpdateAction(request);
        if (!r.ok) {
          UI.status(row).err("Did not start: " + (r.message || "the device did not answer"));
          if (btn) btn.disabled = false;
          return;
        }
        if (okText) UI.status(row).pending(okText);
        global.setTimeout(() => token === this._renderToken && this._rerender(), 1500);
      };
      const rows = [verRow];
      const avail = ru.available;
      const pend = ru.pending;
      const busy = ["downloading", "preparing", "installing", "restarting", "health", "rolling-back"].indexOf(ru.phase) >= 0;
      const checkBtn = UI.button("Check now", null, null, "sync");
      checkBtn.disabled = busy;
      const line = this._releaseLine(ru);
      const stRow = UI.row({ id: "upd.release", title: avail ? "Teslalinux " + name(avail.tag) + " available" : "Release updates", detail: line, control: checkBtn });
      checkBtn.addEventListener("click", () => act({ action: "check" }, checkBtn, stRow, "Checking GitHub for a newer release…"));
      const notices = ru.notices || [];
      const last = notices.length ? notices[notices.length - 1] : null;
      if (last && /^(rolled-back|install-failed|verify-failed|rollback-failed|rollback-impossible)$/.test(last.result)) UI.status(stRow).err(fmt.ago(last.at) + ": " + last.text);
      else if (last && last.at > (ru.lastCheck || 0) - 60) UI.status(stRow).info(new Date(last.at * 1000).toLocaleString() + ": " + last.text);
      rows.push(stRow);
      if (ru.phase === "downloading" && ru.progress && ru.progress.total) {
        const pct = Math.floor((100 * (ru.progress.bytes || 0)) / ru.progress.total);
        const bar = h("div", { class: "progress-bar", style: "width:" + pct + "%" });
        rows.push(h("div", { class: "progress" }, h("div", { class: "progress-label" }, h("span", null, "Downloading " + name(avail && avail.tag)), h("span", { class: "num" }, pct + " % of " + fmt.bytes(ru.progress.total))), h("div", { class: "progress-track" }, bar)));
      }
      if (busy) global.setTimeout(() => token === this._renderToken && this._rerender(), 3000);
      if (avail && !pend && !busy) rows.push(...this._releaseDownloadRows(ru, avail, act));
      if (pend && !busy) {
        const ir = UI.row({
          id: "upd.releaseinstall",
          title: "Install " + name(pend.tag),
          detail: pend.prepared
            ? "Ready: Waze stops for about a minute while the device switches to it and restarts. The page reconnects by itself."
            : "Waze stops, the update installs (a few minutes) and the device restarts. Waze starts again on the new release; the page reconnects by itself.",
          chips: [UI.CHIP.restart()],
        });
        UI.setControl(ir, UI.armed("Install now", "Tap again to install", (b) => act({ action: "installNow" }, b, ir, "Installing… the device restarts when it is done"), "btn--primary"));
        rows.push(ir);
      }
      const shown = avail || pend;
      if (shown && shown.notes) {
        const when = shown.publishedAt ? " (" + new Date(shown.publishedAt).toLocaleDateString() + ")" : "";
        rows.push(h("div", null, UI.disclosure("What's new in " + name(shown.tag) + when, this._releaseNotes(shown.notes))));
      }
      const auto = UI.row({
        id: "upd.releaseauto",
        title: "Update Teslalinux automatically",
        detail: "Downloads a new release and installs it while the car is not connected.",
        control: UI.toggle(ru.settings && ru.settings.autoUpdate !== false, (on) => UI.save(auto, () => this.apiClient.releaseUpdateAction({ autoUpdate: on }).then((r) => {
          if (!r.ok) throw UI.userError("Not saved: " + (r.message || "the device did not answer"));
          return true;
        })), "Update Teslalinux automatically"),
      });
      rows.push(auto);
      if (ru.previous && !busy) {
        const back = UI.row({ id: "upd.releaseback", title: "Go back to " + name(ru.previous), detail: "The release installed before this one, with its files. The device restarts.", chips: [UI.CHIP.restart()] });
        UI.setControl(back, UI.armed("Go back", "Tap again to go back", (b) => act({ action: "goBack" }, b, back, "Going back… the device restarts when it is done")));
        rows.push(back);
      }
      const note = "Checked " + fmt.ago(ru.lastCheckOk) + (ru.repo ? ", releases of " + ru.repo + " on GitHub." : ".");
      return UI.group("Teslalinux", rows, note);
    }

    /** the release updater's state in one line */
    _releaseLine(ru) {
      const name = (tag) => String(tag || "").replace(/^v/, "");
      const a = ru.available;
      const p = ru.pending;
      switch (ru.phase) {
        case "downloading":
          return "Downloading " + name(a && a.tag) + "…";
        case "preparing":
          return "Getting " + name((p && p.tag) || (a && a.tag)) + " ready in the background. Waze keeps working; this takes a few minutes.";
        case "installing":
          return "Installing " + name((p && p.tag) || (a && a.tag)) + ". Waze is stopped; the device restarts when it is done.";
        case "restarting":
          return "Installed. The device is restarting.";
        case "health":
          return "Checking that " + name(ru.health && ru.health.tag) + " starts correctly…";
        case "rolling-back":
          return "Going back to the previous release…";
        default:
          break;
      }
      if (p && p.prepared) return name(p.tag) + " is ready: switching to it takes about a minute. " + (ru.settings && ru.settings.autoUpdate !== false ? "It switches when the car is not connected, or at the next start." : "Install it below.");
      if (p) return name(p.tag) + " is downloaded and verified. " + (ru.settings && ru.settings.autoUpdate !== false ? "It installs when the car is not connected, or at the next start." : "Install it below.");
      if (a && ru.skipped) return name(a.tag) + " is not installed automatically: " + ru.skipped.reason + ".";
      if (a && ru.lastResult === "waiting-unmetered") return "Waiting for Wi-Fi or Ethernet to download (LTE is mobile data).";
      if (a && ru.lastResult === "download-failed") return "The download stopped; it goes on by itself. " + ru.lastMessage;
      if (a) return ru.settings && ru.settings.autoUpdate === false ? "Automatic updates are off." : "It downloads over Wi-Fi or Ethernet.";
      if (ru.lastResult === "check-failed") return "The last check did not reach GitHub; it tries again by itself.";
      if (ru.lastResult === "installed") return "Up to date: " + ru.lastMessage + ".";
      return ru.lastCheckOk ? "Up to date." : "Not checked yet.";
    }

    /** a newer release that is not downloaded: download now, or over mobile data (two taps) */
    _releaseDownloadRows(ru, avail, act) {
      const name = String(avail.tag).replace(/^v/, "");
      const size = UI.fmt.bytes(avail.size);
      const lte = ru.uplink === "lte" || ru.uplink === "phone" || ru.lastResult === "waiting-unmetered";
      const auto = ru.settings && ru.settings.autoUpdate !== false;
      const out = [];
      if (ru.skipped || (!auto && !lte)) {
        const r = UI.row({ id: "upd.releasedl", title: (ru.skipped ? "Install " : "Download ") + name + " anyway", detail: size + ". Verified against the release's checksums before anything is installed." });
        UI.setControl(r, UI.button("Download", (e) => act({ action: "download" }, e.currentTarget, r, "Starting the download…"), null, "download"));
        out.push(r);
      }
      if (lte) {
        const r = UI.row({ id: "upd.releasemetered", title: "Download over mobile data", detail: size + " over LTE. Otherwise it waits for Wi-Fi or Ethernet.", chips: [UI.CHIP.plain("Mobile data")] });
        UI.setControl(r, UI.armed("Download " + size, "Tap again to use mobile data", (b) => act({ action: "downloadMetered" }, b, r, "Downloading over mobile data…"), "btn--primary"));
        out.push(r);
      }
      return out;
    }

    /**
     * The release notes (the release's text on GitHub, CHANGELOG.md's section) as plain text: a
     * "### " heading, its italic summary line, "- " points, paragraphs. Nothing is read as HTML.
     */
    _releaseNotes(text) {
      const box = h("div", { class: "relnotes" });
      let list = null;
      for (const raw of String(text).split("\n")) {
        const line = raw.trim();
        const item = /^[-*] (.*)$/.exec(line);
        if (item) {
          if (!list) box.append((list = h("ul")));
          list.append(h("li", null, item[1].replace(/[`*_]/g, "")));
          continue;
        }
        if (!line) {
          list = null;
          continue;
        }
        const head = /^#{2,6} (.*)$/.exec(line);
        if (head) {
          list = null;
          box.append(h("h3", null, head[1]));
        } else if (/^\*[^*].*\*$/.test(line)) box.append(h("p", { class: "relnotes-sub" }, line.slice(1, -1)));
        else if (list) list.lastElementChild.append(" " + line.replace(/[`*_]/g, ""));
        else box.append(h("p", null, line.replace(/[`*_]/g, "")));
      }
      return box;
    }

    /**
     * The Teslalinux release on this device, from the dist's MANIFEST (GET /api/status
     * system.teslalinux: release tag and build): {text, detail, short} where short is "0.4.0" or
     * "" when the device runs a build that is not a release.
     */
    _teslalinuxVersion(st) {
      const t = st && st.system && st.system.teslalinux;
      const release = t && typeof t.release === "string" ? t.release.replace(/^v/, "") : "";
      const build = t && typeof t.version === "string" ? t.version : "";
      if (release) return { text: release, detail: build ? "Build " + build + "." : null, short: release };
      if (build) return { text: "Build " + build, detail: "Not a release: built from the source.", short: "" };
      return { text: "Not recorded", detail: "Installed without a release package, so no version is kept.", short: "" };
    }

    /** the Waze updates check by themselves (not uploads only, not Google Play while it is off) */
    _autoUpdates() {
      const set = (this.cache.appUpdate && this.cache.appUpdate.settings) || {};
      const source = set.source || "apkpure";
      return source !== "manual" && !(source === "play" && set.playEnabled !== true);
    }

    _wazeUpdateGroups(token, info) {
      const api = this.apiClient;
      const fmt = UI.fmt;
      const set = info.settings || {};
      const source = set.source || "apkpure";
      const playUsable = set.playEnabled === true;
      const automatic = source !== "manual" && !(source === "play" && !playUsable);
      const ver = (v) => (v && v.versionName ? v.versionName : "unknown");
      const when = (unix) => new Date(unix * 1000).toLocaleString();
      const saveSet = (r, change, text) =>
        UI.save(r, () => api.setAppUpdateSettings(change), text).then((ok) => {
          if (ok) global.setTimeout(() => token === this._renderToken && this._rerender(), 300);
          return ok;
        });

      // the state in one line first
      const line = info.serverUnavailableSince
        ? "The update server is unavailable since " + when(info.serverUnavailableSince) + ". Waze keeps running; the Pi retries by itself."
        : info.health && info.health.versionName
          ? "Checking that Waze " + info.health.versionName + " starts correctly…"
          : info.pending && info.pending.versionName
            ? info.pending.versionName + " is verified. It installs when the car is not connected, or at the next start."
            : automatic
              ? "Checked " + fmt.ago(info.lastCheckOk) + (info.latest && info.latest.versionName ? ", latest is " + info.latest.versionName : "")
              : "Automatic checks are off: upload a bundle below";
      const checkBtn = UI.button("Check now", null, null, "sync");
      const wr = UI.row({ id: "upd.waze", title: info.installed && info.installed.versionName ? "Waze " + info.installed.versionName : "Waze", detail: line, control: automatic ? checkBtn : null });
      const notices = info.notices || [];
      const last = notices.length ? notices[notices.length - 1] : null;
      if (info.lastMessage && (!last || last.at <= (info.lastCheck || 0))) UI.status(wr).info("Last result, " + fmt.ago(info.lastCheck) + ": " + info.lastMessage);
      else if (last) UI.status(wr).info(when(last.at) + ": " + last.text);
      checkBtn.addEventListener("click", async () => {
        checkBtn.disabled = true;
        const st = UI.status(wr);
        st.pending("Checking for a newer Waze…");
        try {
          await api.checkAppUpdateNow();
        } catch (_e) {
          st.err("The check did not start: the device did not answer.");
          checkBtn.disabled = false;
          return;
        }
        // the check runs on the Pi; its result shows when the state changes (or after a minute)
        const before = info.lastCheck || 0;
        for (let i = 0; i < 30; i += 1) {
          await UI.wait(2000);
          if (token !== this._renderToken) return;
          try {
            if (((await api.fetchAppUpdate()).lastCheck || 0) > before) break;
          } catch (_e) {
            // keep waiting
          }
        }
        if (token === this._renderToken) await this._rerender();
      });

      const src = UI.row({
        id: "upd.source",
        title: "Update source",
        stack: true,
        more: [
          "Automatic: the Pi checks APKPure once a day (a few hundred KB), downloads a newer Waze, verifies its signature against the installed one and installs it when the car is not connected.",
          "Uploads only: no automatic checks; upload a bundle below (from APKMirror: .apkm; also .xapk, .apks or a zip of APKs).",
          "Google Play goes through a token dispenser and is off unless enabled under Advanced.",
        ],
        control: UI.segmented(
          [
            { value: "apkpure", label: "Automatic", caption: "APKPure, default" },
            { value: "manual", label: "Uploads only" },
            { value: "play", label: "Google Play", caption: playUsable ? "Aurora dispenser" : "turn on in Advanced", disabled: !playUsable, why: playUsable ? null : "Turn on Google Play under Advanced first" },
          ],
          source === "play" && !playUsable ? "manual" : source,
          (v) => saveSet(src, { source: v }),
          { label: "Update source" },
        ),
      });
      const rows = [wr, src];
      if (automatic) {
        const au = UI.row({
          id: "upd.auto",
          title: "Update Waze automatically",
          control: UI.toggle(set.autoUpdate !== false, (on) => saveSet(au, { autoUpdate: on }), "Update Waze automatically"),
        });
        // a version pin: download exactly this version and no newer one (the Map frame rates
        // "Automatic" and "Full" are checked on the versions offered; others may not work)
        const pinCode = set.pinVersionCode || 0;
        const choices = (info.pinChoices || []).slice();
        if (pinCode && !choices.some((c) => c.versionCode === pinCode)) choices.push({ versionCode: pinCode, versionName: set.pinVersionName, reason: "" });
        const installedCode = (info.installed && info.installed.versionCode) || 0;
        const named = (c) => c.versionName || "versionCode " + c.versionCode;
        const pacingNames = choices.filter((c) => c.reason === "mapPacing" && c.versionName).map((c) => c.versionName);
        const pinName = set.pinVersionName || "versionCode " + pinCode;
        const pinDetail = !pinCode
          ? "Every new version." + (pacingNames.length ? " Map frame rates checked on " + pacingNames.join(" and ") + "; others may not work." : "")
          : installedCode > pinCode
            ? "The installed " + ver(info.installed) + " is newer than " + pinName + ", so no further updates. Waze is never taken back to an older version."
            : installedCode === pinCode
              ? "Stays on " + pinName + ": no newer version is downloaded."
              : pinName + " is downloaded and installed like an update, then no newer one.";
        const pinOpts = [{ value: 0, label: "Latest", caption: "default" }].concat(
          choices.map((c) => ({ value: c.versionCode, label: named(c), caption: c.versionCode === installedCode ? "stay, installed" : "stay on it" })),
        );
        const pinSave = (v) => {
          const c = choices.find((x) => x.versionCode === Number(v));
          return saveSet(pin, { pinVersionCode: Number(v), pinVersionName: c && c.versionName ? c.versionName : "" }, Number(v) ? "Saved. Checking for that version" : "Saved");
        };
        const pin = UI.row({
          id: "upd.pin",
          title: "Waze version",
          stack: pinOpts.length > 1 && pinOpts.length <= 6,
          detail: pinDetail,
          control:
            pinOpts.length === 1 ? UI.value("Latest") : pinOpts.length <= 6
              ? UI.segmented(pinOpts, pinCode, pinSave, { label: "Waze version" })
              : UI.tapValue(pinCode ? named({ versionCode: pinCode, versionName: set.pinVersionName }) : "Latest", async () => {
                  const v = await UI.pickFrom({ title: "Waze version", value: pinCode, options: pinOpts.map((o) => ({ value: o.value, label: o.label, small: o.caption })) });
                  if (v != null && Number(v) !== pinCode) pinSave(v);
                }),
        });
        rows.push(au, pin);
      }

      // a manual upload (every source): an APKMirror .apkm, .xapk, .apks, zip of APKs or one APK
      const max = info.maxUpload || 419430400;
      const fileInput = h("input", { type: "file", accept: ".apkm,.xapk,.apks,.zip,.apk", hidden: true });
      const upBtn = UI.button("Choose file", () => fileInput.click(), null, "upload_file");
      const up = UI.row({
        id: "upd.upload",
        title: "Upload an update",
        detail: "A Waze bundle: .apkm, .xapk, .apks, .zip or .apk, up to " + Math.round(max / 1048576) + " MB",
        more: "The same checks as an automatic update: the signature must be Waze's own (the installed one's), the version newer, and the parts for this Pi present. Nothing else is installed.",
        control: [upBtn, fileInput],
      });
      if (info.upload && info.upload.message) UI.status(up).info("Last upload " + fmt.ago(info.upload.at) + ": " + info.upload.message);
      fileInput.addEventListener("change", async () => {
        const f = fileInput.files && fileInput.files[0];
        if (!f) return;
        const st = UI.status(up);
        if (f.size > max) {
          st.err(f.name + " is " + Math.round(f.size / 1048576) + " MB, over the " + Math.round(max / 1048576) + " MB limit.");
          fileInput.value = "";
          return;
        }
        upBtn.disabled = true;
        UI.busy(1);
        let r;
        try {
          r = await api.uploadAppBundle(f, (sent, total) =>
            st.pending(sent < total ? "Uploading " + f.name + ": " + Math.floor((sent * 100) / (total || 1)) + " % (" + Math.round(sent / 1048576) + " of " + Math.round(total / 1048576) + " MB)" : "Checking " + f.name + " (signature, version, parts)…"),
          );
        } catch (e) {
          r = { ok: false, message: "Upload failed: " + (e && e.message ? e.message : "the device did not answer") };
        } finally {
          UI.busy(-1);
          upBtn.disabled = false;
          fileInput.value = "";
        }
        if (r && r.ok !== false) {
          st.ok((r && r.message) || "Verified. It installs when the car is not connected.", true);
          this._refreshCacheSoon();
        } else st.err((r && r.message) || "Not accepted.");
      });
      rows.push(up);

      // advanced: Google Play through a token dispenser (off by default)
      const play = UI.row({
        id: "upd.play",
        title: "Google Play via a token dispenser",
        more: "An anonymous Google Play login from a token dispenser (no Google account). Aurora Store's public dispenser refuses clients other than Aurora Store, so this stays off unless you point it at a dispenser that accepts this Pi.",
        control: UI.toggle(playUsable, (on) => saveSet(play, { playEnabled: on }), "Google Play via a token dispenser"),
      });
      const adv = [play];
      if (playUsable) {
        const disp = UI.row({
          id: "upd.dispenser",
          title: "Dispenser address",
          control: UI.tapValue(set.dispenserUrl || "Aurora Store's", async () => {
            const v = await UI.editText({
              title: "Dispenser address",
              type: "url",
              value: set.dispenserUrl || "",
              placeholder: info.defaultDispenser || "https://auroraoss.com/api/auth",
              help: "Empty: Aurora Store's dispenser. Otherwise an https:// address (a mirror or your own).",
              validate: (x) => (x.trim() === "" || /^https:\/\/[A-Za-z0-9.-]+(:[0-9]{1,5})?(\/[A-Za-z0-9._~%!$&'()*+,;=:@/-]*)?$/.test(x.trim()) ? "" : "Use an https:// address."),
            });
            if (v != null) saveSet(disp, { dispenserUrl: v.trim() });
          }),
        });
        adv.push(disp);
      }
      const [advBtn, advBody] = UI.disclosure("Advanced", h("div", { class: "group-body" }, adv), playUsable);
      return [UI.group("Waze", rows), h("section", { class: "group" }, advBtn, advBody)];
    }

    _systemUpdateGroup(token, su) {
      const fmt = UI.fmt;
      const available = Array.isArray(su.available) ? su.available : [];
      const pending = available.filter((p) => !p.held);
      const held = available.filter((p) => p.held);
      const busy = su.running === true;
      const note = su.manager ? "Kernel " + (su.kernel || "?") + ", updated with " + su.manager + "." : null;
      const checkBtn = UI.button(busy ? "Working…" : "Check now", null, null, busy ? null : "sync");
      if (busy) checkBtn.prepend(h("span", { class: "spin" }));
      checkBtn.disabled = busy;
      const sysRow = UI.row({
        id: "upd.system",
        title: su.os || "Operating system",
        detail: !su.manager
          ? "This system has no package manager this page knows (apt or apk), so it cannot update itself"
          : busy
            ? su.phase === "upgrading" ? "Installing updates" : "Looking for updates"
            : su.lastCheck ? "Checked " + fmt.ago(su.lastCheck) + (su.lastUpgrade ? ", last install " + fmt.ago(su.lastUpgrade) : "") : "Not checked yet",
        control: su.manager ? checkBtn : null,
      });
      if (!su.manager) return UI.group("System", [sysRow]);
      // a failure in the row's status line; any other message is the calm line below (or the install row)
      if (!busy && su.message && su.result === "error") UI.status(sysRow).err(su.message);
      const start = async (action, btn) => {
        btn.disabled = true;
        let r;
        try {
          r = await this.apiClient.systemUpdateAction(action);
        } catch (_e) {
          r = { ok: false, message: "the device did not answer" };
        }
        if (!r.ok) {
          UI.status(sysRow).err("Could not start: " + r.message);
          btn.disabled = false;
          return;
        }
        this._sysLog = null; // a new run: a new terminal
        await this._rerender();
      };
      checkBtn.addEventListener("click", () => start("check", checkBtn));
      const rows = [sysRow];

      const term = this._createTerminal(busy, su.result === "error");
      if (busy) {
        rows.push(h("div", null, this._createProgress(term), term.root));
        this._applySystemProgress(term, su.progress);
      } else if (pending.length) {
        const install = UI.armed("Install " + pending.length, "Tap again to install", (b) => start("upgrade", b), "btn--primary");
        const ir = UI.row({
          id: "upd.install",
          title: pending.length + (pending.length === 1 ? " update available" : " updates available"),
          detail: su.uplink === "lte" ? "Downloads over LTE: this uses mobile data" : "Waze keeps running. The car stays connected.",
          chips: su.uplink === "lte" ? [UI.CHIP.plain("Mobile data")] : null,
          control: install,
        });
        const list = pending.slice(0, 60).map((p) => h("div", { class: "pkg" }, h("span", null, p.name), h("span", null, (p.from ? p.from + " → " : "") + p.to)));
        if (pending.length > 60) list.push(h("div", { class: "pkg" }, h("span", null, "and " + (pending.length - 60) + " more"), h("span")));
        rows.push(ir, h("div", null, UI.disclosure("Show the list", h("div", { class: "pkgs" }, list))));
      } else if (su.lastCheck) {
        rows.push(UI.calm(su.result === "error" ? "The last check did not finish." : su.message || "The system is up to date."));
      }
      if (held.length) rows.push(h("p", { class: "group-note" }, "Held back on purpose (/etc/teslalinux/system-update.hold): " + held.map((p) => p.name).join(", ") + "."));
      if (!busy && su.started) rows.push(term.root);
      if (su.rebootRequired) rows.push(this._restartDeviceRow(true));
      // the output from where this page left off, once the section is on the page
      if (busy || su.started) global.setTimeout(() => this._streamSystemLog(token, term, su, busy), 0);
      return UI.group("System", rows, note);
    }

    /** the device restart (POST /api/systemUpdate {"action":"reboot"}), two taps */
    _restartDeviceRow(needed) {
      const r = UI.row({
        id: "dev.restart",
        title: needed ? "Restart needed" : "Restart the device",
        detail: needed ? "New system files take effect after a restart. The page reconnects by itself." : "About half a minute. The page reconnects by itself.",
        chips: needed ? [UI.CHIP.plain("Waiting")] : null,
      });
      UI.setControl(
        r,
        UI.armed("Restart", "Tap again to restart", async (b) => {
          b.disabled = true;
          let x;
          try {
            x = await this.apiClient.systemUpdateAction("reboot");
          } catch (_e) {
            x = { ok: false, message: "the device did not answer" };
          }
          if (!x.ok) {
            UI.status(r).err("Could not restart: " + x.message);
            b.disabled = false;
          } else UI.status(r).pending("Restarting… this page reconnects by itself");
        }),
      );
      return r;
    }

    // ------------------------------------------------------------------ This device

    /** the start-up time to show (GET /api/status system.boot), or null */
    _bootShown() {
      const b = this.cache.status && this.cache.status.system && this.cache.status.system.boot;
      return b && (b.pageSeconds || (b.history && b.history.length)) ? b : null;
    }

    /**
     * The device itself (GET /api/status, /api/deviceInfo, /api/appState): its health, how long it
     * took to start (host/teslalinux-boottime), restarting Waze and the device, what it is, and
     * Teslalinux's version, licence and source.
     */
    async _deviceSection(token) {
      const api = this.apiClient;
      const [st, info, app] = await Promise.all([
        api.fetchStatus(),
        api.fetchDeviceInfo().catch(() => null),
        api.fetchAppState().catch(() => null),
      ]);
      this.cache.status = st;
      const fmt = UI.fmt;
      const sys = st.system || {};
      const th = sys.throttled;
      const temp = typeof sys.cpuTemperature === "number" && sys.cpuTemperature >= 0 ? sys.cpuTemperature : null;
      const nowBad = th && (th.underVoltage || th.throttled || th.capped || th.softTempLimit);
      const power = !th ? null : nowBad ? "Problem now" : th.sinceBoot ? "Problem earlier" : "OK";
      const why = th
        ? [th.underVoltage ? "under-voltage" : "", th.throttled ? "slowed down" : "", th.capped ? "clock capped" : "", th.softTempLimit ? "temperature limit" : ""].filter(Boolean).join(", ")
        : "";
      const mem = sys.memory || {};
      const disk = sys.disk || {};
      const health = h(
        "div",
        { "data-id": "dev.health" },
        UI.facts([
          temp !== null ? ["Temperature", Math.round(temp) + " °C", temp >= 80 ? "bad" : temp >= 70 ? "warn" : "ok"] : null,
          power ? ["Power", power + (why ? " (" + why + ")" : ""), power === "OK" ? "ok" : "warn"] : null,
          Array.isArray(sys.load) && sys.load.length ? ["Load", sys.load[0].toFixed(1) + (sys.cpus ? " of " + sys.cpus + " cores" : "")] : null,
          mem.total ? ["Memory used", fmt.bytes(mem.total - mem.available) + " of " + fmt.bytes(mem.total)] : null,
          disk.total ? ["Storage free", fmt.bytes(disk.free) + " of " + fmt.bytes(disk.total)] : null,
          typeof sys.uptimeSeconds === "number" ? ["Running for", fmt.dur(sys.uptimeSeconds)] : null,
        ]),
        power && power !== "OK"
          ? UI.warnbox("Use a power supply that gives 5 V at 3 A or more, and keep the device cool. The Pi slows itself down when it gets too hot or the voltage drops.", "bolt")
          : temp !== null && temp >= 80
            ? UI.warnbox("The processor is hot. Above 80 °C the Pi slows itself down: give it a heatsink or a fan and some air.", "device_thermostat")
            : null,
      );
      const out = [UI.group("Health", [health])];

      const boot = this._bootShown();
      if (boot) out.push(UI.group("Start-up", [this._bootTimeline(boot)]));

      const state = (app && app.state) || (st.app && st.app.state);
      const ra = UI.row({
        id: "dev.restartapp",
        title: "Waze",
        detail: appWord(state) + (state === "active" ? ". Restart it when it stops responding (about a minute)." : state === "activating" || state === "restarting" ? "…" : ""),
      });
      UI.setControl(ra, UI.armed("Restart Waze", "Tap again to restart", (b) => this._restartApp(ra, b)));
      out.push(UI.group("Restart", [ra, this._restartDeviceRow(Boolean(this.cache.sys && this.cache.sys.rebootRequired))]));
      out.push(await this._logsGroup(token));

      const hot = st.hotspot || {};
      out.push(
        UI.group("About this device", [
          h(
            "div",
            { "data-id": "dev.info" },
            UI.facts([
              ["Model", (sys.model && / /.test(sys.model) ? sys.model : info && info.device_model ? utils.mapDeviceModelName(info.device_model) : sys.model || "Unknown").replace(/ Model B.*/, "")],
              info && info.serial_number ? ["Serial number", String(info.serial_number)] : null,
              sys.os ? ["System", sys.os.replace(/ GNU\/Linux/, "")] : null,
              sys.kernel ? ["Kernel", sys.kernel] : null,
              sys.hostname ? ["Name", sys.hostname] : null,
              ["Pages showing Waze", String(st.displayViewers || 0)],
              hot.address ? ["Hotspot address", hot.address] : null,
            ]),
          ),
        ]),
      );
      out.push(this._debugGroup());

      // what Teslalinux is, its licence (web/ is Tesla Android's frontend, GPL-3.0) and where its source is
      const version = this._teslalinuxVersion(st).short;
      out.push(
        UI.group("Teslalinux", [
          UI.row({
            id: "dev.about",
            title: ["Teslalinux", version].filter(Boolean).join(" ") + " · GPL-3.0 · based on Tesla Android",
            detail: h("a", { class: "source-link", href: "https://github.com/Alexa-RR/teslalinux", target: "_blank", rel: "noopener" }, "github.com/Alexa-RR/teslalinux"),
          }),
        ]),
      );
      return out;
    }

    /**
     * Debug, for testing the car page on a computer (this browser only, nothing on the device):
     * the Model 3 screen emulator (js/core/screen-emulation.js, emulate.html). Folded away unless
     * something in it is on.
     */
    _debugGroup() {
      const emu = global.TLScreenEmulation;
      const p = emu && emu.PROFILES.model3;
      const on = Boolean(emu && (emu.active || emu.chosen));
      const er = UI.row({
        id: "dev.emulate",
        title: "Emulate the Model 3 screen",
        chips: [UI.CHIP.plain("This browser only")],
        detail: p
          ? "This page as a Model 3's browser shows it: " + Math.round(p.cssWidth) + " × " + Math.round(p.cssHeight) + " at " + p.devicePixelRatio + " pixels per point, scaled to fit this window."
          : "",
        more: [
          "The display size this page asks the device for, the picture's pixels and the layout all come out as in the car, so a computer shows what the car shows. A bar above it says the emulation is on.",
          "Like every browser showing Waze, it sets the device's display to its own size: the car's, while this is on.",
        ],
        control: UI.toggle(on, (v) => (emu ? emu.set(v ? "model3" : null) : false), "Emulate the Model 3 screen"),
      });
      const [b, body] = UI.disclosure(on ? "Debug options (on)" : "Debug options", [er], on);
      return UI.group("Debug", [b, body], "For testing on a computer.", "debug");
    }

    /** the start-up: a headline, the phases on one time axis, the milestones as legend and table, the last starts */
    _bootTimeline(b) {
      const s1 = (x) => UI.fmt.secs(x);
      const S1 = (x) => h("bdi", null, s1(x));
      const kernel = b.kernelSeconds;
      const hot = b.hotspotSeconds;
      const page = b.pageSeconds;
      const wrap = h("div", { class: "boot", "data-id": "dev.boot" });
      const st = h("div", { class: "row-status", "aria-live": "polite" });
      if (page) {
        // Waze's map ready ("Map shown and ready" exists in the phone interface only), else its first picture
        const mapReady = b.appMapSeconds && b.appMapSeconds > page;
        const map = mapReady ? b.appMapSeconds : b.appFrameSeconds && b.appFrameSeconds > page ? b.appFrameSeconds : null;
        const mapLabel = mapReady ? "Waze's map ready after" : "Waze's first picture after";
        const end = map || page;
        const ph = [];
        if (kernel) ph.push({ key: "kernel", name: "Kernel", from: 0, to: kernel, what: "The Linux kernel starts and finds the hardware" });
        const sysEnd = hot && hot < page ? hot : page;
        if (kernel && sysEnd > kernel) ph.push({ key: "system", name: "System", from: kernel, to: sysEnd, what: hot && hot < page ? "Services start, up to the hotspot the car joins" : "Services start, up to the web server" });
        if (hot && hot < page) ph.push({ key: "page", name: "Page", from: hot, to: page, what: "The web server that serves this page" });
        if (map) ph.push({ key: "waze", name: "Waze", from: page, to: map, what: mapReady ? "Waze starts and draws its map" : "Waze starts and shows its first picture" });
        wrap.append(
          h(
            "div",
            { class: "boot-hero" },
            h("div", null, h("div", { class: "boot-label" }, "Last start: ready for the car in"), h("div", { class: "boot-value num" }, S1(page))),
            map
              ? h("div", { class: "boot-side" }, h("div", { class: "boot-label" }, mapLabel), h("div", { class: "boot-value boot-value--2 num" }, S1(map)))
              : !b.final
                ? h("div", { class: "boot-side" }, h("div", { class: "boot-label" }, "Waze"), h("div", { class: "boot-value boot-value--2 num" }, "measuring…"))
                : null,
          ),
        );
        // the tall segmented bar: widths in proportion, a label only where it fits
        const bar = h("div", { class: "boot-bar", role: "img", "aria-label": "Start-up phases: " + ph.map((p) => p.name + " " + s1(p.to - p.from)).join(", ") });
        ph.forEach((p) => {
          const share = (p.to - p.from) / end;
          const seg = h(
            "button",
            { class: "boot-seg boot-seg--" + p.key, type: "button", style: "flex-grow:" + share.toFixed(4), title: p.name + ": " + s1(p.from) + " to " + s1(p.to) },
            share > 0.14 ? h("span", null, p.name + " " + s1(p.to - p.from)) : null,
          );
          seg.addEventListener("click", () => {
            st.className = "row-status is-info";
            st.replaceChildren(icon("info_outline"), h("span", null, p.name + ", " + s1(p.from) + " to " + s1(p.to) + ": " + p.what + "."));
          });
          bar.append(seg);
        });
        const ticks = h("div", { class: "boot-axis", "aria-hidden": "true" });
        const step = end > 60 ? 20 : end > 25 ? 10 : 5;
        for (let t = 0; t <= end; t += step) ticks.append(h("span", { style: "inset-inline-start:" + ((t / end) * 100).toFixed(2) + "%" }, h("bdi", null, t + " s")));
        const steps = [
          kernel ? ["kernel", "Kernel started", kernel] : null,
          hot ? ["system", "Hotspot on: the car can join", hot] : null,
          ["page", "This page served", page],
          b.appFrameSeconds ? ["waze", "Waze's first picture", b.appFrameSeconds] : null,
          b.appMapSeconds ? ["waze", "Waze's map ready", b.appMapSeconds] : null,
        ]
          .filter(Boolean)
          .sort((x, y) => x[2] - y[2]);
        const list = h(
          "ol",
          { class: "boot-steps" },
          steps.map((x) => h("li", null, h("span", { class: "boot-key boot-seg--" + x[0], "aria-hidden": "true" }), h("span", null, x[1]), h("span", { class: "num" }, S1(x[2])))),
        );
        wrap.append(bar, ticks, st, list);
        wrap.append(h("p", { class: "group-note" }, "Counted from the kernel's start. The Pi's own firmware runs for a few seconds before that and is not measured."));
      } else {
        wrap.append(h("p", { class: "group-note" }, "This start is not measured yet."));
      }
      // earlier starts, newest first
      const hist = (b.history || []).filter((x) => x.pageSeconds);
      if (hist.length > (page ? 1 : 0)) {
        const ready = hist.map((x) => x.pageSeconds).sort((x, y) => x - y);
        const typical = ready[Math.floor(ready.length / 2)];
        const rows = hist.map((x, i) =>
          h(
            "div",
            { class: "pkg" },
            h("span", null, i === 0 && page && x.at === b.at ? "This start" : x.at ? new Date(x.at * 1000).toLocaleDateString("en-GB", { weekday: "short", day: "numeric", month: "short" }) : "Earlier"),
            h(
              "span",
              { class: "num" },
              h("bdi", null, s1(x.pageSeconds) + " ready, " + (x.appMapSeconds ? "map " + s1(x.appMapSeconds) : x.appFrameSeconds ? "Waze " + s1(x.appFrameSeconds) : "Waze not measured")),
            ),
          ),
        );
        wrap.append(...UI.disclosure("Previous starts (typically " + s1(typical) + ")", h("div", { class: "pkgs" }, rows)));
      }
      return wrap;
    }

    // ------------------------------------------------------------------ Waze

    /**
     * How Waze looks and behaves: its interface (GET/POST /api/appInterface: the phone screens, or
     * its car interface drawn by js/carapp over its map), the keyboard that types into it (the
     * phone interface's choice; /api/keyboard), language and time zone (/api/locale), the car's
     * position (upstream's gps setting), and in the car interface its look (kept by this browser,
     * js/carapp) and its map (colours, text size, frame rate, sharpness). What restarts Waze waits
     * in the restart bar.
     */
    async _wazeSection(token) {
      const api = this.apiClient;
      const [iface, kb, loc, conf] = await Promise.all([
        api.fetchAppInterface(),
        api.fetchKeyboard().catch(() => null),
        api.fetchLocale().catch(() => null),
        this._fetchSystemConfigurationSafe(),
      ]);
      this.cache.iface = iface;
      if (kb) this.cache.keyboard = kb;
      const car = global.TLCarApp;
      // the map colours are the device's: this answer is fresher than the one the car interface read at load
      if (car && car.setDeviceMapColours) car.setDeviceMapColours(iface);
      const isCar = this._staged("interface", iface.interface) === "car";
      const rerender = () => global.setTimeout(() => token === this._renderToken && this._rerender(), 140);
      const out = [];

      // --- interface
      const ir = UI.row({
        id: "waze.interface",
        title: "Waze interface",
        chips: [UI.CHIP.restart()],
        stack: true,
        more: [
          "Car is the interface Waze shows on car screens: the map fills the display, with turn cards, search and large buttons on top. Phone is Waze as on a phone.",
          "Your saved places and Waze settings are the same in both.",
        ],
        control: UI.tiles(
          [
            { value: "car", label: "Car", caption: "Big buttons, map first", svg: UI.SVG.car() },
            { value: "phone", label: "Phone", caption: "As on a phone (default)", svg: UI.SVG.phone() },
          ],
          this._staged("interface", iface.interface),
          (v) => {
            this._stage(ir, "interface", "Waze interface", iface.interface, v, () => api.setAppInterface(v));
            this._applyTheme();
            rerender();
            return true;
          },
        ),
      });
      this._markStaged(ir, "interface");
      const interfaceRows = [ir];
      // the keyboard is the phone interface's choice: the car interface's fields always use the car's
      if (!isCar && kb) {
        const kr = UI.row({
          id: "waze.keyboard",
          title: "Keyboard",
          chips: [UI.CHIP.restart()],
          more: "Car's keyboard: this page opens the car's (or phone's) own keyboard, with its dictation microphone, and sends what you type to Waze. Safari on iPhone opens a keyboard only from a tap: use the keyboard button that appears in the corner.",
          control: UI.segmented(
            [
              { value: "device", label: "Car's", caption: "default, with dictation" },
              { value: "builtin", label: "Waze's" },
            ],
            this._staged("keyboard", kb.mode),
            (v) =>
              this._stage(kr, "keyboard", "Keyboard", kb.mode, v, async () => {
                await api.setKeyboard(v);
                global.dispatchEvent(new CustomEvent("teslalinux-keyboard-mode", { detail: v }));
              }),
          ),
        });
        this._markStaged(kr, "keyboard");
        interfaceRows.push(kr);
      }
      out.push(UI.group("Interface", interfaceRows));

      // --- language, time zone (they go to the device together) and the car's position
      const langRows = [];
      if (loc) {
        const curLocale = this._staged("locale", loc.locale);
        const curZone = this._staged("timezone", loc.timezone);
        const setLoc = () => api.setLocale(this._staged("locale", loc.locale), this._staged("timezone", loc.timezone));
        const lr = UI.row({
          id: "waze.locale",
          title: "Language and region",
          chips: [UI.CHIP.restart()],
          control: UI.tapValue(
            this._localeName(curLocale),
            async () => {
              const v = await UI.pickFrom({
                title: "Language and region",
                value: curLocale,
                options: (loc.locales || []).map((x) => ({ value: x, label: this._localeName(x), small: x })),
              });
              if (v && v !== curLocale) {
                this._stage(lr, "locale", "Language and region", loc.locale, v, setLoc);
                rerender();
              }
            },
            this.restart.has("locale"),
          ),
        });
        const tr = UI.row({
          id: "waze.timezone",
          title: "Time zone",
          chips: [UI.CHIP.restart()],
          control: UI.tapValue(
            this._zoneName(curZone).label,
            async () => {
              const v = await UI.pickFrom({
                title: "Time zone",
                value: curZone,
                options: (loc.timezones || []).map((z) => Object.assign({ value: z }, this._zoneName(z))),
              });
              if (v && v !== curZone) {
                this._stage(tr, "timezone", "Time zone", loc.timezone, v, setLoc);
                rerender();
              }
            },
            this.restart.has("timezone"),
          ),
        });
        this._markStaged(lr, "locale");
        this._markStaged(tr, "timezone");
        langRows.push(lr, tr);
      }
      if (conf) {
        const gpsOn = utils.toInt(conf["persist.tesla-android.gps.is_active"], 1) === 1;
        const gr = UI.row({
          id: "waze.gps",
          title: "Car's position",
          more: [
            "The car's browser sends its position to this device, which stands in for a GPS receiver for Waze. It never leaves the car.",
            "On Tesla software 2024.14 and newer, sharing the browser's position has crashed the car's browser for some drivers. If that happens, turn this off.",
          ],
          control: UI.toggle(
            gpsOn,
            (on) =>
              UI.save(gr, async () => {
                await api.setGpsState(on ? 1 : 0);
                await this._handleSystemConfigurationChanged();
                this.gpsController.setEnabled(on);
              }, on ? "On: Waze gets the car's position" : "Off: Waze has no position"),
            "Car's position",
          ),
        });
        langRows.push(gr);
      }
      if (langRows.length) out.push(UI.group("Language and position", langRows));

      // --- the car interface's look and map (only while it is the interface picked)
      if (isCar && car && car.getLook) {
        const look = car.getLook();
        const sr = UI.row({
          id: "waze.style",
          title: "Panel style",
          stack: true,
          chips: [UI.CHIP.plain("Graphite is new")],
          control: UI.tiles(
            [
              { value: "rounded", label: "Rounded", caption: "Dark panels (default)", svg: UI.SVG.rounded() },
              { value: "graphite", label: "Graphite", caption: "Light by day, dark by night", svg: UI.SVG.graphite() },
            ],
            look.style,
            (v) => UI.save(sr, () => car.setLook({ style: v }), "Applied"),
          ),
        });
        const dr = UI.row({
          id: "waze.daynight",
          title: "Day and night",
          stack: true,
          more: "Automatic follows the sun at the car's position. Waze's map, the Graphite panels and this page switch with it.",
          control: UI.segmented(this._dayNightOptions(), look.dayNight, (v) => UI.save(dr, () => car.setLook({ dayNight: v }), "Applied")),
        });
        const A = UI.A;
        const pr = UI.row({
          id: "waze.panelsize",
          title: "Panel size",
          stack: true,
          control: UI.segmented(
            [
              { value: "smallest", glyph: A(13), caption: "Smallest" },
              { value: "smaller", glyph: A(16), caption: "Smaller" },
              { value: "default", glyph: A(19), caption: "Default" },
              { value: "larger", glyph: A(23), caption: "Larger" },
            ],
            look.panelSize || "default",
            (v) => UI.save(pr, () => car.setLook({ panelSize: v }), "Applied"),
            { label: "Panel size" },
          ),
        });
        // the speedometer this page draws over the map (carapp-speedometer.js; the device sends the
        // app's readings when it finds them in the installed Waze, and Waze's own stays off in all three)
        const speedSupport = (iface && iface.speedometerSupport) || look.speedometerSupport;
        const spd = UI.row({
          id: "waze.speedometer",
          title: "Speedometer",
          stack: true,
          after: this._versionNote(speedSupport, "Waze draws its own."),
          control: UI.segmented(
            [
              { value: "both", label: "Speed and limit", caption: "default" },
              { value: "limit", label: "Limit only" },
              { value: "off", label: "Off" },
            ],
            look.speedometer || "both",
            (v) => UI.save(spd, () => car.setLook({ speedometer: v }), "Applied"),
            { label: "Speedometer" },
          ),
        });
        const spp = UI.row({
          id: "waze.speedometerpos",
          title: "Speedometer position",
          stack: true,
          control: UI.segmented(
            [
              { value: "bottomRight", label: "Bottom right", caption: "beside the map buttons, default" },
              { value: "topLeft", label: "Under the directions", caption: "top left" },
            ],
            look.speedometerPosition || "bottomRight",
            (v) => UI.save(spp, () => car.setLook({ speedometerPosition: v }), "Applied"),
            { label: "Speedometer position" },
          ),
        });
        out.push(UI.group("Car interface look", [sr, dr, pr, spd, spp]));

        const skin = look.mapSkin;
        const unverified = Boolean(skin && !skin.supported);
        const mayNotWork = Boolean(skin && skin.state === "unverified");
        const savedColours = look.mapColours;
        const mr = UI.row({
          id: "waze.mapcolours",
          title: "Map colours",
          stack: true,
          chips: [UI.CHIP.custom("chip--restart", "restart_alt", "Graphite restarts Waze")],
          more: [
            "Graphite: Waze's own map recoloured when it starts, grey land, white roads by day and dark roads by night, a blue route. Muted: this screen tones Waze's map down (with the Graphite panels). Waze's own: as Waze draws it.",
          ],
          control: UI.tiles(
            [
              {
                value: "graphite",
                label: "Graphite",
                svg: UI.SVG.colours("graphite"),
                caption: unverified ? "Not for this Waze" : mayNotWork ? "May not work with this Waze" : "Recoloured by the device",
              },
              { value: "muted", label: "Muted", svg: UI.SVG.colours("muted"), caption: "Toned down here, default" },
              { value: "app", label: "Waze's own", svg: UI.SVG.colours("app"), caption: "As Waze draws it" },
            ],
            this._staged("mapcolours", savedColours),
            (v) => {
              // only a change to or from Graphite restarts Waze; Muted and Waze's own apply at once
              if ((v === "graphite") !== (savedColours === "graphite")) {
                return this._stage(mr, "mapcolours", "Map colours", savedColours, v, () => car.setLook({ mapColours: v }));
              }
              this.restart.delete("mapcolours");
              this._markStaged(mr, "mapcolours");
              this._renderRestartBar();
              if (v === car.getLook().mapColours) return true;
              return UI.save(mr, () => car.setLook({ mapColours: v }), "Applied");
            },
          ),
        });
        this._markStaged(mr, "mapcolours");
        const zr = UI.row({
          id: "waze.mapsize",
          title: "Map text size",
          stack: true,
          detail: "Street names and symbols on the map",
          control: UI.segmented(
            [
              { value: "smaller", glyph: A(14), caption: "Smaller" },
              { value: "default", glyph: A(17), caption: "Default" },
              { value: "larger", glyph: A(21), caption: "Larger" },
              { value: "largest", glyph: A(25), caption: "Largest" },
            ],
            look.mapSize || "default",
            (v) => UI.save(zr, () => car.setLook({ mapSize: v }), "Applied"),
            { label: "Map text size" },
          ),
        });
        const pacingSaved = ["auto", "app", "full"].includes(iface.mapPacing) ? iface.mapPacing : "auto";
        const support = iface.mapPacingSupport;
        const pc = UI.row({
          id: "waze.pacing",
          title: "Map frame rate",
          chips: [UI.CHIP.restart()],
          stack: true,
          detail: "Automatic is smooth while the map moves and lets the Pi rest while it is still; Full works the Pi hardest",
          after: this._versionNote(support, "Waze keeps its own rate."),
          control: UI.segmented(
            [
              { value: "auto", label: "Automatic", caption: "60 while moving, default" },
              { value: "app", label: "Waze's own", caption: "30 per second" },
              { value: "full", label: "Full", caption: "up to 60 per second" },
            ],
            this._staged("pacing", pacingSaved),
            (v) => this._stage(pc, "pacing", "Map frame rate", pacingSaved, v, () => api.setMapPacing(v)),
            { label: "Map frame rate" },
          ),
        });
        this._markStaged(pc, "pacing");
        const sh = UI.row({
          id: "waze.sharpness",
          title: "Map sharpness",
          stack: true,
          detail: "Lower is a little softer and smoother on the move",
          control: UI.segmented(
            [
              { value: "full", label: "Full", caption: "default" },
              { value: "high", label: "90%" },
              { value: "medium", label: "80%" },
              { value: "low", label: "70%" },
            ],
            look.mapSharpness || "full",
            (v) => UI.save(sh, () => car.setLook({ mapSharpness: v }), "Applied"),
            { label: "Map sharpness" },
          ),
        });
        const graphite = this._staged("mapcolours", savedColours) === "graphite";
        const skinNote = graphite ? this._versionNote(skin, look.style === "graphite" ? "Waze's own colours, toned down here." : "Waze's own colours.") : null;
        out.push(UI.group("Map", [skinNote, mr, zr, pc, sh].filter(Boolean)));
        out.push(UI.group("While driving", [this._typingWhileDrivingRow(car)]));
      }
      // Waze's own car settings, drawn from its settings screens (js/carapp/carapp-settings-view.js):
      // only while the car interface runs (its channel is there), not while Car is only staged
      if (isCar && car && car.mode === "car" && car.openAppSettings && global.TLCarAppSettingsView) {
        const view = new global.TLCarAppSettingsView(car, { onMap: () => this.onClose && this.onClose() });
        out.push(view.element());
      }
      return out;
    }

    /**
     * Typing while driving: the car's UX restriction mode in the car interface's host (ATL's
     * ATLCarVehicle). Off is the baseline, AOSP's restrictions as Android Auto has them: while the car
     * moves, no keyboard input (the search field is off, voice search stays). On is the passenger
     * mode: the same restrictions without the keyboard one. The host applies it at once and keeps it
     * on the device; only while the car interface runs (the host is there to ask).
     */
    _typingWhileDrivingRow(car) {
      const running = car.mode === "car" && Boolean(car.restrictions);
      const mode = running ? car.getRestrictionMode() : null;
      const why = !running
        ? "Available while the car interface is running."
        : mode === null
          ? "This device's app runtime cannot allow typing while driving: update the device."
          : null;
      const tr = UI.row({
        id: "waze.typing",
        title: "Typing while driving",
        stack: true,
        detail: "Off: while the car moves, the search field takes no typing, as in Android Auto. Voice search still works.",
        more: [
          "On: a passenger can type in the car interface's search while the car moves. Lists stay as short as Off has them.",
          "Applies at once, for every screen showing Waze, and stays after a restart.",
        ],
        after: [
          why ? UI.warnbox(why, "info_outline") : null,
          UI.warnbox("Typing while the car moves is the driver's responsibility. Turn it on for a passenger, never to type while driving yourself."),
        ].filter(Boolean),
        control: UI.segmented(
          [
            { value: "off", label: "Off", caption: "default, as Android Auto", disabled: Boolean(why) },
            { value: "on", label: "On", caption: "for a passenger", disabled: Boolean(why) },
          ],
          mode === "passenger" ? "on" : "off",
          (v) =>
            UI.save(
              tr,
              () => car.setRestrictionMode(v === "on" ? "passenger" : "baseline"),
              v === "on" ? "On: a passenger can type while the car moves" : "Off: no typing while the car moves",
              "Not changed: the car interface did not answer.",
            ),
          { label: "Typing while driving" },
        ),
      });
      return tr;
    }

    /**
     * One short line under a feature that depends on the installed Waze version (Graphite map
     * colours, the speedometer, the map frame rate; /api/appInterface mapSkin, speedometerSupport,
     * mapPacingSupport): nothing for a checked version, "May not work" for one nobody checked (the
     * device found what it needs and the feature can be chosen), and why it is off otherwise, with
     * FALLBACK (what Waze does instead).
     */
    _versionNote(support, fallback) {
      if (!support || support.state === "verified" || support.state === "pending") return null;
      const installed = support.versionCode ? "Waze " + (support.versionName || "versionCode " + support.versionCode) : "this Waze";
      if (support.state === "unverified" && (support.supported || support.checked === false)) {
        return UI.warnbox("May not work with " + installed + ".", "info_outline");
      }
      if (support.supported) return null;
      const why =
        {
          "not-installed": "Not available: no Waze installed.",
          "no-tool": "Not available on this device.",
          "no-manifest": "Not available on this device.",
        }[support.state] || "Not available for " + installed + ".";
      return UI.warnbox(why + " " + fallback);
    }

    _localeName(code) {
      const tag = String(code || "").replace("_", "-");
      try {
        const name = new Intl.DisplayNames(["en"], { type: "language" }).of(tag);
        return name && name !== tag ? name : String(code);
      } catch (_e) {
        return String(code);
      }
    }

    _zoneName(zone) {
      let off = "";
      try {
        const part = new Intl.DateTimeFormat("en-US", { timeZone: zone, timeZoneName: "shortOffset" }).formatToParts(new Date()).find((p) => p.type === "timeZoneName");
        off = part ? part.value : "";
      } catch (_e) {
        // no offset known
      }
      const z = String(zone || "");
      return { label: z.split("/").pop().replace(/_/g, " "), small: (z.indexOf("/") > 0 ? z.split("/")[0] + " " : "") + off };
    }

    // ------------------------------------------------------------------ Sound

    /**
     * Where Waze's sound plays (GET/POST /api/audioOutput, services/audio/audio.py: this browser, a
     * speaker connected to the Pi, or both; applied within half a second, no restart), the car's
     * browser audio (upstream's browser_audio settings), and voice search (js/speech/speech.js,
     * this browser's, car interface only).
     */
    async _soundSection(token) {
      const api = this.apiClient;
      const [out, conf] = await Promise.all([api.fetchAudioOutput().catch(() => null), this._fetchSystemConfigurationSafe()]);
      if (out) this.cache.audio = out;
      const groups = [];

      if (out) {
        const or = UI.row({ id: "sound.output", title: "Where Waze's sound plays", stack: true });
        UI.setControl(
          or,
          UI.segmented(this._soundOptions(), out.output, (v) =>
            UI.save(or, () => api.setAudioOutput({ output: v }), "Saved. It switches within a second").then((ok) => {
              if (ok) {
                this.cache.audio = Object.assign({}, out, { output: v });
                this._renderSidebar();
                global.setTimeout(() => token === this._renderToken && this._rerender(), 600);
              }
              return ok;
            }),
            { label: "Where Waze's sound plays" },
          ),
        );
        const waze = [or];
        if (out.output !== "car") {
          const sp = UI.row({
            id: "sound.pispeaker",
            title: "Pi speaker",
            more: "A USB speaker or sound card when one is plugged in, otherwise the 3.5 mm jack. Plugging one in or out moves the sound within a second.",
            control: UI.value(this._speakerText(out)),
          });
          const vr = UI.row({
            id: "sound.pivolume",
            title: "Pi speaker volume",
            stack: true,
            control: UI.slider({
              min: 0,
              max: 100,
              step: 5,
              value: out.volume,
              unit: " %",
              iconLow: "volume_down",
              label: "Pi speaker volume",
              onChange: (v) => UI.save(vr, () => api.setAudioOutput({ volume: v })),
            }),
          });
          waze.push(sp, vr);
          this._speakerPlaying(sp, out);
          // the Pi output follows plugging in and out: its line is read again every 3 s
          const poll = async () => {
            if (token !== this._renderToken) return;
            if (this._visible()) {
              try {
                const o = await api.fetchAudioOutput();
                if (token !== this._renderToken) return;
                const v = sp.querySelector(".row-value");
                if (v) v.textContent = this._speakerText(o);
                this._speakerPlaying(sp, o);
              } catch (_e) {
                // keep the last text; the next round tries again
              }
            }
            this._pollTimer = global.setTimeout(poll, 3000);
          };
          this._pollTimer = global.setTimeout(poll, 3000);
        }
        waze.push(this._soundTestRow(out, conf));
        groups.push(UI.group("Waze's sound", waze));
      }

      if (conf) {
        const onPiOnly = out && out.output === "pi";
        const enabled = utils.toInt(conf["persist.tesla-android.browser_audio.is_enabled"], 1) === 1;
        const volume = utils.toInt(conf["persist.tesla-android.browser_audio.volume"], 100);
        const br = UI.row({
          id: "sound.browser",
          title: "Sound in the car's browser",
          dim: onPiOnly,
          detail: onPiOnly ? "Off while Waze's sound plays only on the Pi" : null,
          more: [
            "Turn it off to use Bluetooth audio from the device instead. Browser audio with the picture needs a lot of Wi-Fi bandwidth.",
            "No sound in the car? The car's browser sometimes loses its audio; restarting the car's touchscreen brings it back. That is a browser problem, not this device.",
          ],
          control: UI.toggle(enabled && !onPiOnly, (on) =>
            UI.save(br, async () => {
              await api.setBrowserAudioState(on ? 1 : 0);
              await api.setBrowserAudioVolume(100);
              await this._handleSystemConfigurationChanged();
            }, on ? "On" : "Off").then((ok) => {
              if (ok) global.setTimeout(() => token === this._renderToken && this._rerender(), 500);
              return ok;
            }),
          "Sound in the car's browser"),
        });
        const bv = UI.row({
          id: "sound.browservolume",
          title: "Browser volume",
          stack: true,
          dim: onPiOnly || !enabled,
          detail: "Above 100 % boosts quiet prompts",
          control: UI.slider({
            min: 0,
            max: 150,
            step: 10,
            value: volume,
            unit: " %",
            iconLow: "volume_down",
            label: "Browser volume",
            marks: [{ at: 100, label: "100" }],
            onChange: (v) =>
              UI.save(bv, async () => {
                await api.setBrowserAudioState(1);
                await api.setBrowserAudioVolume(v);
                await this._handleSystemConfigurationChanged();
              }),
          }),
        });
        groups.push(UI.group("Car's browser", [br, bv]));
      }

      // both interfaces: the car interface's search button, and in the phone interface Waze's own
      // microphone button (the app's speech recognizer listens through this page)
      const voice = this._voiceRows(token);
      if (voice.length) groups.push(UI.group("Voice", voice));
      if (!groups.length) throw new Error("no answer");
      return groups;
    }

    /**
     * Saved logs (GET/POST /api/logs, host/teslalinux-logs): the device keeps its log in memory, so a
     * restart or the car cutting the power loses it. Save keeps a snapshot on the device; each one
     * downloads later from any browser on the hotspot (GET /api/logsFile).
     */
    async _logsGroup(token) {
      const api = this.apiClient;
      const fmt = UI.fmt;
      let logs = [];
      try {
        logs = await api.fetchLogs();
      } catch (_e) {
        // shown as none; saving tells whether the device answers
      }
      const save = UI.row({
        id: "dev.logs",
        title: "Save logs now",
        detail: "Right after something goes wrong: the log is lost at the next restart.",
      });
      const btn = UI.button("Save", null, "btn--primary", "save");
      btn.addEventListener("click", async () => {
        const reason = await UI.editText({ title: "What happened?", placeholder: "Optional, e.g. no voice while driving", maxLength: 200 });
        if (reason == null) return;
        btn.disabled = true;
        UI.status(save).pending("Saving…");
        const r = await api.saveLogs(reason.trim());
        btn.disabled = false;
        if (!r.ok) {
          UI.status(save).err(r.message || "Not saved");
          return;
        }
        UI.status(save).ok("Saved", true);
        global.setTimeout(() => token === this._renderToken && this._rerender(), 800);
      });
      UI.setControl(save, btn);
      const rows = [save];
      for (const l of logs) {
        const when = new Date(l.at * 1000).toLocaleString();
        const r = UI.row({ title: when, detail: (l.reason ? l.reason + " · " : "") + fmt.bytes(l.size) });
        const dl = h("a", { class: "btn", href: api.logFileUrl(l.name), download: l.name }, "Download");
        const del = UI.armed("Delete", "Tap again to delete", async () => {
          const res = await api.deleteLogs(l.name);
          if (!res.ok) UI.status(r).err(res.message || "Not deleted");
          else global.setTimeout(() => token === this._renderToken && this._rerender(), 300);
        });
        UI.setControl(r, h("div", { class: "row-actions" }, dl, del));
        rows.push(r);
      }
      return UI.group("Logs", rows, logs.length ? "Kept on the device: the newest 20." : null);
    }

    /**
     * Test sound (POST /api/audioTest): the Pi plays a short chime into the sink Waze plays into, so
     * it takes Waze's whole path (the car's stream, the Pi speaker, or both) and a silent Waze can be
     * told apart from a silent path. The press is also the tap the car's browser needs before it
     * plays anything: the page's sound stream is started first, and the chime asked for once it had
     * a moment to connect.
     */
    _soundTestRow(out, conf) {
      const api = this.apiClient;
      const r = UI.row({
        id: "sound.test",
        title: "Test sound",
        more: "Plays a short chime from the Pi the way Waze's voice goes. Hear it but not Waze? Then the sound path works and Waze is not playing.",
      });
      const btn = UI.button("Play", null, null, "volume_up");
      btn.addEventListener("click", async () => {
        const st = UI.status(r);
        const o = this.cache.audio || out;
        const car = o.output !== "pi";
        const browserOn = !conf || utils.toInt(conf["persist.tesla-android.browser_audio.is_enabled"], 1) === 1;
        if (o.output === "car" && !browserOn) {
          st.err("Sound in the car's browser is off: turn it on below.");
          return;
        }
        if (o.output === "pi" && !o.speaker) {
          st.err("Nothing is connected to the Pi to play it on.");
          return;
        }
        btn.disabled = true;
        try {
          if (car && browserOn) {
            const was = typeof global.getAudioState === "function" ? global.getAudioState() : null;
            if (typeof global.startAudioFromGesture === "function") global.startAudioFromGesture();
            if (was !== "playing") {
              st.pending("Connecting the car's sound…");
              await new Promise((res) => global.setTimeout(res, 1500));
            }
          }
          const res = await api.playAudioTest();
          const a = res.audio || o;
          const speaker = a.speaker ? a.speaker.description || a.speaker.name || "the Pi speaker" : null;
          const where =
            a.output === "pi" ? "on " + (speaker || "the Pi speaker")
              : a.output === "both" && speaker ? "in the car and on " + speaker
                : "in the car";
          st.info("Playing a chime " + where + "…");
          await new Promise((res2) => global.setTimeout(res2, Math.ceil((res.seconds || 1.5) * 1000)));
          st.ok("Played " + where + ". Nothing heard? Check the car's volume, or restart its touchscreen.", true);
        } catch (_e) {
          st.err("The Pi could not play the test sound.");
        } finally {
          btn.disabled = false;
        }
      });
      UI.setControl(r, btn);
      return r;
    }

    _voiceOn() {
      const s = global.TLSpeech;
      return Boolean(s && s.supported() && s.enabled());
    }

    _speakerText(o) {
      if (!o.running) return "The sound service is not running";
      const sp = o.speaker;
      if (!sp) return "Nothing connected";
      if (sp.kind === "jack") return "3.5 mm jack";
      const kind = { usb: "USB", other: "sound card" }[sp.kind] || "";
      return (sp.description || sp.name || "Speaker") + (kind ? " (" + kind + ")" : "");
    }

    _speakerPlaying(r, o) {
      if (o.running && o.speaker) UI.status(r).info(o.playing ? "Playing now" : "Quiet right now");
      else UI.status(r).clear();
    }

    /**
     * Voice search (js/speech/speech.js): this browser's microphone and speech recognition, for the
     * car interface's search and Waze's own microphone button in the phone interface. Off by
     * default, per browser: a Tesla's browser has none, and the car's
     * own keyboard dictates through its microphone. Language and test only while it is on.
     */
    _voiceRows(token) {
      const speech = global.TLSpeech;
      if (!speech) return [];
      const teslaNote = "Does not work in a Tesla's browser: use the microphone on the car's keyboard";
      if (!speech.supported()) {
        return [UI.row({ id: "sound.voicebutton", title: "Voice search button", detail: teslaNote, control: UI.value("Not in this browser") })];
      }
      const vb = UI.row({
        id: "sound.voicebutton",
        title: "Voice search button",
        detail: teslaNote,
        more: "A microphone button in the car interface's search, and Waze's own microphone button in the phone interface, using this browser's own speech recognition.",
        control: UI.toggle(speech.enabled(), (on) => {
          speech.setEnabled(on);
          global.setTimeout(() => token === this._renderToken && this._rerender(), 150);
          return true;
        }, "Voice search button"),
      });
      if (!speech.enabled()) return [vb];
      const vl = UI.row({
        id: "sound.voicelang",
        title: "Voice search language",
        control: UI.segmented(speech.LANGUAGES.map((l) => ({ value: l.value, label: l.label })), speech.language(), (v) => {
          speech.setLanguage(v);
          UI.status(vl).ok("Saved in this browser");
          return true;
        }),
      });
      const mt = UI.row({ id: "sound.mictest", title: "Test the microphone" });
      const test = UI.button("Test", null, null, "mic");
      let session = null;
      const idle = () => {
        session = null;
        test.replaceChildren(icon("mic"), h("span", null, "Test"));
      };
      const why = {
        "no-match": "Nothing recognised. Try again a little louder.",
        "no-speech": "No speech heard. Is the microphone on?",
        "not-allowed": "The microphone is blocked: allow it for this page in the browser.",
        "service-not-allowed": "This browser does not offer speech recognition to pages.",
        "audio-capture": "No microphone found.",
        network: "Speech recognition could not reach its service (it needs the internet).",
        "language-not-supported": "This browser cannot recognise the chosen language.",
      };
      test.addEventListener("click", () => {
        const st = UI.status(mt);
        if (session) {
          session.stop();
          return;
        }
        test.replaceChildren(icon("stop"), h("span", null, "Stop"));
        st.info("Listening… say a place");
        session = speech.listen({
          partial: true,
          onPartial: (texts) => st.info("Heard: " + (texts[0] || "")),
          onResult: (texts) => {
            idle();
            st.ok("Heard: " + texts[0], true);
          },
          onError: (error) => {
            idle();
            if (error === "aborted") st.clear();
            else st.err(why[error] || "Speech recognition failed (" + error + ").");
          },
        });
      });
      UI.setControl(mt, test);
      return [vb, vl, mt];
    }

    _soundOptions() {
      return [
        { value: "car", label: "Car", icon: "directions_car", caption: "default" },
        { value: "pi", label: "Pi speaker", icon: "speaker" },
        { value: "both", label: "Both", icon: "speaker_group" },
      ];
    }

    _dayNightOptions() {
      return [
        { value: "auto", label: "Automatic", icon: "brightness_auto", caption: "follows the sun" },
        { value: "day", label: "Day", icon: "wb_sunny" },
        { value: "night", label: "Night", icon: "nights_stay" },
      ];
    }

    /** the answers the rail and the overview read, all at once; each part null when it fails */
    async _refreshCache() {
      const api = this.apiClient;
      const safe = (p) => p.then((v) => v, () => null);
      const [status, iface, audio, display, appUpdate, sys, config, gpu, keyboard, release] = await Promise.all([
        safe(api.fetchStatus()),
        safe(api.fetchAppInterface()),
        safe(api.fetchAudioOutput()),
        safe(api.fetchDisplayState()),
        safe(api.fetchAppUpdate()),
        safe(api.fetchSystemUpdate()),
        safe(api.fetchSystemConfiguration()),
        safe(api.fetchGpuSpeed()),
        safe(api.fetchKeyboard()),
        safe(api.fetchReleaseUpdate()),
      ]);
      const c = this.cache;
      const keep = (k, v) => {
        if (v) c[k] = v;
      };
      keep("status", status);
      keep("iface", iface);
      keep("audio", audio);
      keep("display", display);
      keep("appUpdate", appUpdate);
      keep("sys", sys);
      keep("config", config);
      keep("gpu", gpu);
      keep("keyboard", keyboard);
      keep("release", release);
      this._cacheAt = Date.now();
      this._renderSidebar();
    }

    _refreshCacheSoon() {
      if (Date.now() - this._cacheAt < 8000 || this._cacheBusy) return;
      this._cacheBusy = true;
      this._refreshCache().finally(() => {
        this._cacheBusy = false;
      });
    }

    // ------------------------------------------------------------------ the interface picked

    /** a value as it will be after the restart: the staged one, else the saved one */
    _staged(key, saved) {
      return this.restart.has(key) ? this.restart.get(key).value : saved;
    }

    /**
     * The interface the page shows settings for: the one picked, even while it waits for the
     * restart (the other interface's settings keep their values and waiting changes, and come back
     * with it).
     */
    _ifaceNow() {
      const saved = this.cache.iface ? this.cache.iface.interface : global.TLCarApp && global.TLCarApp.mode;
      return this._staged("interface", saved === "car" ? "car" : "phone");
    }

    // ------------------------------------------------------------------ changes that restart Waze

    /**
     * Changes that restart Waze wait together in one bar at the bottom of the pane until the
     * driver restarts Waze, so several changes cost one restart. Choosing the saved value again
     * takes a change out; Undo drops them all. The row says that its change is waiting.
     */
    _stage(r, key, label, saved, value, apply) {
      if (String(value) === String(saved)) this.restart.delete(key);
      else this.restart.set(key, { label, value, saved, apply });
      this._markStaged(r, key);
      this._renderRestartBar();
      return true;
    }

    _markStaged(r, key) {
      if (!r) return;
      const st = UI.status(r);
      if (this.restart.has(key)) st.info("Applies when Waze restarts");
      else st.clear();
    }

    _renderRestartBar() {
      if (!this.page) return;
      let bar = this.page.querySelector(".rbar");
      this.page.classList.toggle("has-rbar", this.restart.size > 0);
      if (!this.restart.size) {
        if (bar && !bar.classList.contains("leaving")) {
          bar.classList.add("leaving");
          bar._t = global.setTimeout(() => bar.remove(), 180);
        }
        return;
      }
      if (!bar) {
        bar = h("div", { class: "rbar", role: "region", "aria-label": "Changes waiting for Waze to restart" });
        this.page.append(bar);
      }
      global.clearTimeout(bar._t);
      bar.classList.remove("leaving");
      const names = [...this.restart.values()].map((x) => x.label);
      bar.replaceChildren(
        h(
          "div",
          { class: "rbar-text" },
          h("b", null, names.length === 1 ? "Restart Waze to apply your change" : "Restart Waze to apply " + names.length + " changes"),
          names.join(", ") + ". Waze is back in about a minute; navigation stops until then.",
        ),
        UI.button("Undo", () => this._undoRestart(), "btn--ghost"),
        UI.button("Restart Waze now", () => this._applyRestart(bar), "btn--primary", "restart_alt"),
      );
    }

    async _undoRestart() {
      this.restart.clear();
      this._renderRestartBar();
      await this._rerender();
    }

    async _applyRestart(bar) {
      bar.querySelectorAll("button").forEach((b) => (b.disabled = true));
      bar.querySelector(".rbar-text").replaceChildren(h("b", null, "Restarting Waze…"), "Saving the changes.");
      UI.busy(1);
      const iface = this.restart.has("interface") ? this.restart.get("interface").value : null;
      try {
        for (const [k, x] of [...this.restart]) {
          await x.apply();
          this.restart.delete(k);
        }
      } catch (_e) {
        bar.querySelector(".rbar-text").replaceChildren(h("b", null, "Not applied"), "The device did not answer. Your changes are kept.");
        bar.querySelectorAll("button").forEach((b) => (b.disabled = false));
        UI.busy(-1);
        return;
      }
      UI.busy(-1);
      this._renderRestartBar();
      if (iface) {
        // the page itself changes with the interface: it reloads once the device reports the new one
        UI.toast("Waze restarts with the " + iface + " interface. This page reloads by itself.");
        for (let i = 0; i < 20; i += 1) {
          await UI.wait(500);
          try {
            if ((await this.apiClient.fetchAppInterface()).interface === iface) break;
          } catch (_e) {
            // the API restarting with the app: try again
          }
        }
        global.location.reload();
        return;
      }
      this._watchApp();
      await this._rerender();
    }

    /** the header's pill, the rail and the overview follow the app until it runs again (or 3 minutes pass) */
    _watchApp() {
      global.clearTimeout(this._appWatch);
      const pill = global.document.getElementById("settings-bar-state");
      const text = global.document.getElementById("settings-bar-state-text");
      const started = Date.now();
      let seen = false;
      const tick = async () => {
        let state = "unknown";
        try {
          state = (await this.apiClient.fetchAppState()).state;
        } catch (_e) {
          // keep following
        }
        if (this.cache.status && this.cache.status.app) this.cache.status.app.state = state;
        const down = state !== "active";
        if (down) seen = true;
        if (pill) pill.classList.toggle("on", down);
        if (text) text.textContent = state === "restarting" || state === "inactive" ? "Waze is restarting" : "Waze is starting";
        this._renderSidebar();
        if (this.activeSectionId === "overview" || this.activeSectionId === "device") this._rerender();
        if ((down || !seen) && Date.now() - started < 180000) {
          this._appWatch = global.setTimeout(tick, 1500);
        } else {
          if (pill) pill.classList.remove("on");
          if (!down) UI.toast("Waze is running again.");
        }
      };
      this._appWatch = global.setTimeout(tick, 800);
    }

    // ------------------------------------------------------------------ search

    _closeSearch() {
      if (this._results) {
        this._results.remove();
        this._results = null;
      }
    }

    /** settings of one interface only are found only in it */
    _searchable(id) {
      const entry = this.index.find((x) => x[0] === id);
      if (!entry) return false;
      const only = entry[4];
      return !only || only.call(this);
    }

    _runSearch(q) {
      const box = this._searchBox;
      if (box) box.classList.toggle("has-text", q.length > 0);
      if (!q.trim()) {
        this._closeSearch();
        return;
      }
      const words = q.toLowerCase().split(/\s+/).filter(Boolean);
      const hits = this.index
        .filter((x) => this.sections.some((s) => s.id === x[1]) && this._searchable(x[0]))
        .map(([id, sec, title, keys]) => {
          const hay = (title + " " + keys).toLowerCase();
          if (!words.every((w) => hay.includes(w))) return null;
          const t = title.toLowerCase();
          return { id, sec, title, score: t.startsWith(words[0]) ? 0 : t.includes(words[0]) ? 1 : 2 };
        })
        .filter(Boolean)
        .sort((a, b) => a.score - b.score);
      if (!this._results) {
        this._results = h("div", { class: "results", role: "listbox", "aria-label": "Search results" });
        this.page.append(this._results);
      }
      const mark = (t) => {
        const i = t.toLowerCase().indexOf(words[0]);
        return i < 0 ? t : [t.slice(0, i), h("mark", null, t.slice(i, i + words[0].length)), t.slice(i + words[0].length)];
      };
      this._results.replaceChildren();
      UI.append(
        this._results,
        hits.length
          ? hits.map((x) => {
              const sec = this.sections.find((s) => s.id === x.sec);
              return h(
                "button",
                {
                  class: "result",
                  type: "button",
                  role: "option",
                  onclick: () => {
                    this._searchInput.value = "";
                    if (box) box.classList.remove("has-text");
                    this._searchInput.blur();
                    this.setActiveSection(x.sec, x.id);
                  },
                },
                icon(sec.icon),
                h("div", null, h("div", { class: "result-title" }, mark(x.title)), h("div", { class: "result-path" }, sec.title)),
                icon("chevron_right", "flip"),
              );
            })
          : h("p", { class: "results-empty" }, "No setting matches “" + q + "”. Try a word like sound, map, Wi-Fi or update."),
      );
    }

    // ------------------------------------------------------------------ display state

    async _updateDisplayConfig(mutator) {
      // teslalinux: no alert(); the row (or a toast for the old sections) says it applied
      try {
        const config = this._normalizeDisplayState(await this.apiClient.fetchDisplayState());
        const draft = { ...config };
        mutator(draft);
        await this.apiClient.postDisplayState(draft);
        this.displayController.triggerResizeFromViewport();
        this.cache.display = draft;
        return true;
      } catch (_error) {
        return false;
      }
    }

    _normalizeDisplayState(raw) {
      return {
        width: Math.max(1, utils.toInt(raw.width, 1024)),
        height: Math.max(1, utils.toInt(raw.height, 768)),
        density: utils.toInt(raw.density, 200),
        resolutionPreset: utils.toInt(raw.resolutionPreset, 0),
        renderer: utils.toInt(raw.renderer, 0),
        isHeadless:
          raw.isHeadless === undefined || raw.isHeadless === null
            ? 1
            : utils.toInt(raw.isHeadless, 1),
        isResponsive: utils.toInt(raw.isResponsive, 1),
        isH264: utils.toInt(raw.isH264, 0),
        refreshRate: utils.toInt(raw.refreshRate, 30),
        quality: utils.toInt(raw.quality, 90),
      };
    }

    async _handleSystemConfigurationChanged() {
      this.systemConfigCache = await this.apiClient.fetchSystemConfiguration();
      if (typeof this.onSystemConfigurationChanged === "function") {
        this.onSystemConfigurationChanged(this.systemConfigCache);
      }
    }

    async _fetchSystemConfigurationSafe() {
      try {
        this.systemConfigCache = await this.apiClient.fetchSystemConfiguration();
        return this.systemConfigCache;
      } catch (_error) {
        return null;
      }
    }
  }

  TAHtml.SettingsController = SettingsController;
})(window);
