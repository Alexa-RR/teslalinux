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
      // teslalinux: the page has no bottom bar: "Map" goes back to the display, "What's new" opens the release notes
      this.onClose = options.onClose || null;
      this.onOpenPage = options.onOpenPage || null;

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
        { id: "status", title: "Status", icon: "info" },
        { id: "display", title: "Display", icon: "desktop_windows" },
        // teslalinux: hotspot and LTE (GET/POST /api/network); upstream's Rear Display is gone (the
        // app is a navigation app shown on the main screen)
        { id: "network", title: "Wi-Fi & LTE", icon: "wifi" },
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
        // teslalinux: automatic Waze updates from Google Play (GET/POST /api/appUpdate*)
        { id: "updates", title: "App updates", icon: "system_update" },
        // teslalinux: the operating system's own updates (GET/POST /api/systemUpdate)
        { id: "system", title: "System update", icon: "system_security_update" },
        { id: "device", title: "Device", icon: "memory" },
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
        ["waze.mapcolours", "waze", "Map colours", "map colours colors graphite muted palette", car],
        ["waze.mapsize", "waze", "Map text size", "map size street names zoom labels symbols", car],
        ["waze.pacing", "waze", "Map frame rate", "map frame rate fps smooth 60 30", car],
        ["waze.sharpness", "waze", "Map sharpness", "map sharpness render scale blurry", car],
        ["sound.output", "sound", "Where Waze's sound plays", "audio output speaker pi car both sound"],
        ["sound.pivolume", "sound", "Pi speaker volume", "volume loud quiet pi speaker usb jack", () => this.cache.audio && this.cache.audio.output !== "car"],
        ["sound.browser", "sound", "Sound in the car's browser", "browser audio bluetooth sound"],
        ["sound.browservolume", "sound", "Browser volume", "volume loud quiet"],
        ["sound.voicebutton", "sound", "Voice search button", "voice microphone mic search speech dictation", car],
        ["sound.voicelang", "sound", "Voice search language", "voice speech hebrew english microphone language", () => car() && this._voiceOn()],
        ["sound.mictest", "sound", "Test the microphone", "microphone mic test voice", () => car() && this._voiceOn()],
      ];

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
      this.content.innerHTML = "";
      this._curBody = null;

      if (this.activeSectionId === "status") {
        await this._renderStatusSection(token);
        return;
      }
      if (this.activeSectionId === "system") {
        await this._renderSystemUpdateSection(token);
        return;
      }
      if (this.activeSectionId === "display") {
        await this._renderDisplaySection(token);
        return;
      }
      if (this.activeSectionId === "network") {
        await this._renderNetworkSection(token);
        return;
      }
      if (this.activeSectionId === "updates") {
        await this._renderAppUpdateSection(token);
        return;
      }
      if (this.activeSectionId === "device") {
        await this._renderDeviceSection(token);
      }
    }

    async _renderDisplaySection(token) {
      this._appendLoadingState();

      let config;
      try {
        config = this._normalizeDisplayState(await this.apiClient.fetchDisplayState());
      } catch (_error) {
        this._renderErrorState("Service error");
        return;
      }

      if (token !== this._renderToken || this.activeSectionId !== "display") {
        return;
      }

      this.content.innerHTML = "";

      this._appendTile({
        icon: "texture",
        title: "Renderer",
        trailing: this._createSelect(constants.DISPLAY_RENDERERS, config.renderer, async (value) => {
          const renderer = utils.toInt(value, config.renderer);
          await this._updateDisplayConfig((draft) => {
            draft.renderer = renderer;
            draft.isH264 = renderer !== 0 ? 1 : 0;
            draft.density = utils.densityForResolutionPreset(draft.resolutionPreset, draft.isH264 === 1);
          });
        }),
      });
      this._appendDescription(
        "Teslalinux supports both h264 and MJPEG display compression. MJPEG has less visible compression artifacts but needs much more bandwidth.\n\nNOTE: WebCodecs may not work if your car is running Tesla Firmware older than 2025.32.",
      );
      this._appendDivider();

      this._appendTile({
        icon: "display_settings",
        title: "Resolution",
        trailing: this._createSelect(constants.DISPLAY_RESOLUTION_PRESETS, config.resolutionPreset, async (value) => {
          const preset = utils.toInt(value, config.resolutionPreset);
          await this._updateDisplayConfig((draft) => {
            draft.resolutionPreset = preset;
            draft.density = utils.densityForResolutionPreset(draft.resolutionPreset, draft.isH264 === 1);
          });
        }),
      });
      this._appendDescription(
        "Choosing a low resolution improves the display performance in Drive. It reduces the browser load, meant for cars equipped with MCU2. Resolutions lower than 720p only work with the MJPEG renderer.",
      );
      this._appendDivider();

      this._appendTile({
        icon: "photo_size_select_actual_outlined",
        title: "Image quality",
        trailing: this._createSelect(constants.DISPLAY_QUALITY_PRESETS, config.quality, async (value) => {
          const quality = utils.toInt(value, config.quality);
          await this._updateDisplayConfig((draft) => {
            draft.quality = quality;
          });
        }),
      });
      this._appendDescription(
        "Reducing the image quality can significantly improve performance when a higher resolution is used.",
      );
      this._appendDivider();

      this._appendTile({
        icon: "monitor",
        title: "Refresh rate",
        trailing: this._createSelect(constants.DISPLAY_REFRESH_RATE_PRESETS, config.refreshRate, async (value) => {
          const refreshRate = utils.toInt(value, config.refreshRate);
          await this._updateDisplayConfig((draft) => {
            draft.refreshRate = refreshRate;
          });
        }),
      });
      this._appendDescription(
        "By default Teslalinux is running in 30Hz. You can increase the frame rate with this setting. This feature is experimental.",
      );
      this._appendDivider();

      this._appendTile({
        icon: "photo_size_select_large",
        title: "Dynamic aspect ratio",
        trailing: this._createSwitch(config.isResponsive === 1, async (checked) => {
          await this._updateDisplayConfig((draft) => {
            draft.isResponsive = checked ? 1 : 0;
          });
        }),
      });
      this._appendDescription(
        "Advanced setting, Teslalinux can automatically resize the virtual display when the browser window size changes. If you disable this option, the display aspect will be locked on the current value.",
      );
    }

    // teslalinux: what the device is connected to right now (GET /api/status), refreshed every 3 s while
    // the section is open: the car (and anything else) on the hotspot, the internet uplink and the LTE
    // modem, the car's GPS position, the app, and the device itself.
    async _renderStatusSection(token, refresh) {
      if (!refresh) {
        this._appendLoadingState();
      }
      let st;
      try {
        st = await this.apiClient.fetchStatus();
      } catch (_error) {
        this._renderErrorState("Service error");
        return;
      }
      if (token !== this._renderToken || this.activeSectionId !== "status") {
        return;
      }
      const scroll = this.content.scrollTop;
      this.content.innerHTML = "";
      const fmt = this._fmt();
      const hot = st.hotspot || {};
      const sys = st.system || {};

      this._appendHeading("Car");
      const clients = Array.isArray(hot.clients) ? hot.clients : [];
      if (!hot.present) {
        this._appendTile({
          icon: "wifi_off",
          title: "No hotspot",
          subtitle: "This device has no Wi-Fi interface " + (hot.interface || "") + ": the car cannot join it",
          dense: false,
        });
      } else if (clients.length === 0) {
        this._appendTile({
          icon: "wifi_find",
          title: "Nothing connected to " + (hot.ssid || "the hotspot"),
          subtitle: hot.running ? "The hotspot is on and waiting for the car" : "The hotspot is not running",
          dense: false,
        });
      } else {
        clients.forEach((c) => {
          const parts = [];
          if (c.ip) {
            parts.push(c.ip);
          }
          if (typeof c.connectedSeconds === "number") {
            parts.push("connected " + fmt.duration(c.connectedSeconds));
          }
          if (typeof c.txMbps === "number") {
            parts.push(Math.round(c.txMbps) + " Mbit/s");
          }
          this._appendTile({
            icon: "directions_car",
            title: c.name || c.mac,
            subtitle: parts.join(" · "),
            trailing: this._createTrailingText(typeof c.signalDbm === "number" ? c.signalDbm + " dBm" : ""),
            dense: false,
          });
        });
      }
      this._appendTile({
        icon: "cast_connected",
        title: "Pages showing the app",
        trailing: this._createTrailingText(String(st.displayViewers || 0)),
      });
      this._appendDivider();

      this._appendHeading("Internet");
      const up = st.uplink;
      const net = st.internet || {};
      const kindName = { lte: "LTE modem", wifi: "Wi-Fi", ethernet: "Ethernet", phone: "Phone (USB tethering)" };
      if (!up) {
        this._appendTile({ icon: "public_off", title: "No internet connection", subtitle: "No route to the internet", dense: false });
      } else {
        this._appendTile({
          icon: up.kind === "lte" ? "cell_tower" : up.kind === "wifi" ? "wifi" : "lan",
          title: "Through " + (kindName[up.kind] || up.kind) + " (" + up.interface + ")",
          subtitle: [up.address, up.gateway ? "gateway " + up.gateway : ""].filter(Boolean).join(" · "),
          trailing: this._createTrailingText(
            net.reachable ? "Online" + (typeof net.latencyMs === "number" ? ", " + net.latencyMs + " ms" : "") : "No internet",
          ),
          dense: false,
        });
        if (typeof up.rxBytes === "number") {
          this._appendTile({
            icon: "data_usage",
            title: "Data since start",
            trailing: this._createTrailingText("↓ " + fmt.bytes(up.rxBytes) + "  ↑ " + fmt.bytes(up.txBytes)),
          });
        }
      }
      // teslalinux-uplink: which uplink carries the traffic and why; every uplink's own check
      const fo = st.failover;
      if (fo && Array.isArray(fo.candidates)) {
        const kinds = { lte: "LTE", wifi: "Backup Wi-Fi", ethernet: "Ethernet", phone: "Phone" };
        if (fo.reason) {
          this._appendTile({
            icon: "alt_route",
            title: "Using " + (kinds[fo.activeKind] || fo.activeKind) + " instead",
            subtitle: fo.reason.replace(/^lte\b/, "LTE").replace(/^wifi\b/, "Wi-Fi") + " · since " + fmt.duration(Math.max(0, st.time - fo.since)),
            dense: false,
          });
        }
        if (fo.candidates.length > 1) {
          fo.candidates.forEach((c) => {
            this._appendTile({
              icon: c.ok ? "check_circle" : "cancel",
              title: (kinds[c.kind] || c.kind) + " (" + c.iface + ")" + (c.iface === fo.active ? " · in use" : ""),
              trailing: this._createTrailingText(c.ok ? "Online" + (typeof c.latencyMs === "number" ? ", " + c.latencyMs + " ms" : "") : "No internet"),
            });
          });
        }
        if (fo.wifi && fo.wifi.iface && fo.wifi.ssid && fo.activeKind !== "wifi") {
          this._appendTile({
            icon: "wifi",
            title: "Backup Wi-Fi ready",
            subtitle: fo.wifi.ssid + (typeof fo.wifi.signalDbm === "number" ? " · " + fo.wifi.signalDbm + " dBm" : ""),
            dense: false,
          });
        }
      }
      const modem = st.modem;
      // teslalinux-uplink's view of the stick (HiLink web API or ModemManager, every field optional) and its
      // automatic recovery; the API's own modem reading is the fallback when the daemon has none
      const mr = fo && fo.modem && fo.modem.type && fo.modem.type !== "none" ? fo.modem : null;
      if (mr) {
        const bars = typeof mr.bars === "number" ? mr.bars : null;
        const tech = mr.networkType && mr.technology && mr.technology !== mr.networkType ? mr.networkType + " (" + mr.technology + ")" : mr.networkType || mr.technology;
        const conn = mr.connection ? mr.connection.charAt(0).toUpperCase() + mr.connection.slice(1) : "";
        this._appendTile({
          icon: mr.simProblem ? "signal_cellular_no_sim" : bars === 0 ? "signal_cellular_0_bar" : bars !== null && bars >= 4 ? "signal_cellular_4_bar" : "signal_cellular_alt",
          title: (mr.operator || "LTE modem") + (mr.model ? " · " + mr.model : ""),
          subtitle: [tech, conn, mr.roaming ? "Roaming" : "", typeof mr.connectedSeconds === "number" && mr.connection === "connected" ? "for " + fmt.duration(mr.connectedSeconds) : ""]
            .filter(Boolean)
            .join(" · "),
          trailing: this._createTrailingText(bars !== null ? "Signal " + bars + "/5" : typeof mr.signal === "number" ? "Signal " + mr.signal + " %" : ""),
          dense: false,
        });
        if (mr.simProblem) {
          this._appendTile({ icon: "sim_card_alert", title: "SIM card", subtitle: mr.simProblem, dense: false });
        }
        const radio = mr.radio || {};
        const radioBits = [
          radio.rsrp ? "RSRP " + radio.rsrp : "",
          radio.rsrq ? "RSRQ " + radio.rsrq : "",
          radio.sinr ? "SINR " + radio.sinr : "",
          radio.rssi && !radio.rsrp ? "RSSI " + radio.rssi : "",
          radio.band ? "band " + radio.band : "",
          radio.cellId ? "cell " + radio.cellId : "",
        ].filter(Boolean);
        if (radioBits.length) {
          this._appendTile({ icon: "cell_tower", title: "Radio", subtitle: radioBits.join(" · "), dense: false });
        }
        const data = (d) => (d ? "↓ " + (d.down || "?") + "  ↑ " + (d.up || "?") : "");
        if (mr.session || mr.month) {
          this._appendTile({
            icon: "data_usage",
            title: "Mobile data",
            subtitle: [mr.session ? "This session " + data(mr.session) : "", mr.month ? "This month " + data(mr.month) : ""].filter(Boolean).join(" · "),
            dense: false,
          });
        }
        const actionNames = {
          reconnect: mr.type === "hilink" ? "mobile data off and on" : "reconnect",
          "modem-reset": "modem restart",
          "usb-reenumerate": "USB re-enumeration",
          "usb-power-cycle": "USB power cycle",
        };
        if (mr.lastAction || (mr.status && mr.status !== "working")) {
          const last = mr.lastAction
            ? "Last: " + (actionNames[mr.lastAction] || mr.lastAction) + ", " + fmt.duration(Math.max(0, st.time - mr.lastActionAt)) + " ago" +
              (mr.lastResult && mr.lastResult !== "done" ? " (" + mr.lastResult + ")" : "")
            : "";
          this._appendTile({
            icon: "restart_alt",
            title: "Modem recovery" + (mr.recovery === "off" ? " (off)" : ""),
            subtitle: [mr.status ? mr.status.charAt(0).toUpperCase() + mr.status.slice(1) : "", last].filter(Boolean).join(" · "),
            trailing: this._createTrailingText(
              typeof mr.nextActionAt === "number" && mr.nextActionAt > st.time ? "Next step in " + fmt.duration(mr.nextActionAt - st.time) : "",
            ),
            dense: false,
          });
        }
      } else if (modem) {
        const bits = [modem.technology, modem.state].filter(Boolean);
        this._appendTile({
          icon: "signal_cellular_alt",
          title: (modem.operator || "LTE modem") + (modem.model ? " · " + modem.model : ""),
          subtitle: bits.join(" · "),
          trailing: this._createTrailingText(typeof modem.signalPercent === "number" ? "Signal " + modem.signalPercent + " %" : ""),
          dense: false,
        });
      } else {
        this._appendTile({ icon: "signal_cellular_off", title: "LTE modem", trailing: this._createTrailingText("Not detected") });
      }
      this._appendDivider();

      this._appendHeading("Hotspot");
      const band = hot.frequencyMhz ? (hot.frequencyMhz >= 5000 ? "5 GHz" : "2.4 GHz") : "";
      this._appendTile({
        icon: hot.running ? "wifi_tethering" : "wifi_tethering_off",
        title: hot.ssid || "Hotspot",
        subtitle: hot.present
          ? [hot.running ? "On" : "Off", band, hot.channel ? "channel " + hot.channel : "", hot.widthMhz ? hot.widthMhz + " MHz" : "", hot.address]
              .filter(Boolean)
              .join(" · ")
          : "No Wi-Fi interface",
        dense: false,
      });
      this._appendDivider();

      this._appendHeading("Location and app");
      const gps = st.gps || {};
      const fix = gps.fix;
      this._appendTile({
        icon: fix ? "my_location" : "location_disabled",
        title: fix ? fix.latitude.toFixed(5) + ", " + fix.longitude.toFixed(5) : "No position from the car",
        subtitle: fix
          ? [
              typeof fix.speedMps === "number" ? Math.round(fix.speedMps * 3.6) + " km/h" : "",
              typeof fix.accuracyM === "number" ? "±" + Math.round(fix.accuracyM) + " m" : "",
              typeof fix.ageSeconds === "number" ? fix.ageSeconds + " s ago" : "",
            ]
              .filter(Boolean)
              .join(" · ")
          : gps.service
            ? "The car's browser sends its position while GPS is on in Settings > GPS"
            : "The GPS service is not running",
        dense: false,
      });
      const appState = (st.app || {}).state;
      this._appendTile({
        icon: "navigation",
        title: "Waze",
        trailing: this._createTrailingText(
          { active: "Running", activating: "Starting", restarting: "Restarting", inactive: "Stopped", failed: "Stopped after an error" }[appState] ||
            "Unknown",
        ),
      });
      this._appendDivider();

      this._appendHeading("Device");
      this._appendTile({
        icon: "memory",
        title: sys.model || "Device",
        subtitle: [sys.os, sys.kernel ? "kernel " + sys.kernel : ""].filter(Boolean).join(" · "),
        dense: false,
      });
      this._appendTile({
        icon: "schedule",
        title: "Running for",
        trailing: this._createTrailingText(typeof sys.uptimeSeconds === "number" ? fmt.duration(sys.uptimeSeconds) : "unknown"),
      });
      if (typeof sys.cpuTemperature === "number" && sys.cpuTemperature >= 0) {
        this._appendTile({
          icon: "device_thermostat",
          title: "CPU temperature",
          trailing: this._createTrailingText(sys.cpuTemperature + "°C"),
        });
      }
      if (Array.isArray(sys.load) && sys.load.length) {
        this._appendTile({
          icon: "speed",
          title: "Load (1 / 5 / 15 min)",
          subtitle: sys.cpus ? sys.cpus + " CPU cores" : "",
          trailing: this._createTrailingText(sys.load.map((v) => v.toFixed(2)).join(" / ")),
          dense: false,
        });
      }
      if (sys.memory && sys.memory.total) {
        this._appendTile({
          icon: "memory_alt",
          title: "Memory in use",
          trailing: this._createTrailingText(fmt.bytes(sys.memory.total - sys.memory.available) + " of " + fmt.bytes(sys.memory.total)),
        });
      }
      if (sys.disk && sys.disk.total) {
        this._appendTile({
          icon: "storage",
          title: "Storage free",
          trailing: this._createTrailingText(fmt.bytes(sys.disk.free) + " of " + fmt.bytes(sys.disk.total)),
        });
      }
      const th = sys.throttled;
      if (th && (th.underVoltage || th.throttled || th.capped || th.softTempLimit || th.sinceBoot)) {
        const now = [th.underVoltage ? "under-voltage" : "", th.throttled ? "throttled" : "", th.capped ? "frequency capped" : "", th.softTempLimit ? "temperature limit" : ""]
          .filter(Boolean)
          .join(", ");
        this._appendTile({
          icon: "warning",
          title: "Power or heat problem",
          subtitle: now ? "Now: " + now : "Seen since start (not now)",
          dense: false,
        });
        this._appendDescription("Use a power supply that gives 5 V at 3 A or more, and keep the device cool.");
      }
      this.content.scrollTop = scroll;
      this._pollTimer = global.setTimeout(() => {
        if (token !== this._renderToken || this.activeSectionId !== "status") {
          return;
        }
        if (!global.document.hidden) {
          this._renderStatusSection(token, true);
        } else {
          // the page is in the background: look again later
          this._pollTimer = global.setTimeout(() => this._renderStatusSection(token, true), 3000);
        }
      }, 3000);
    }

    // teslalinux: the hotspot (name, password, country, band and channel), what the car may reach
    // without internet, and the LTE modem's APN (GET/POST /api/network, host/teslalinux-network).
    // Band, channel and the offline switches are upstream's settings (POST /api/softAp* etc.).
    async _renderNetworkSection(token) {
      this._appendLoadingState();
      let net;
      try {
        net = await this.apiClient.fetchNetwork();
        this.systemConfigCache = await this.apiClient.fetchSystemConfiguration();
      } catch (_error) {
        this._renderErrorState("Failed to fetch the network settings from your device.");
        return;
      }
      if (token !== this._renderToken || this.activeSectionId !== "network") {
        return;
      }
      this.content.innerHTML = "";
      const saved = this._networkPendingFromSystemConfig(this.systemConfigCache);
      const draft = Object.assign({}, saved);
      const hot = net.hotspot || {};

      this._appendHeading("Hotspot");
      if (!hot.present) {
        this._appendDescription(
          "This device has no Wi-Fi interface (" + (hot.interface || "wlan0") + "): the settings below are kept and take effect on a device with one.",
        );
      }
      const ssid = this._createTextInput(hot.ssid || "", { maxLength: 32, placeholder: "Network name" });
      this._appendTile({ icon: "wifi", title: "Network name", trailing: ssid, dense: false });
      const psk = this._createTextInput("", {
        type: "password",
        maxLength: 64,
        placeholder: hot.pskSet ? "Unchanged" : "At least 8 characters",
        autocomplete: "new-password",
      });
      this._appendTile({
        icon: "password",
        title: "Password",
        subtitle: "The car forgets the old one: enter the new password in the car's Wi-Fi settings",
        trailing: psk,
        dense: false,
      });
      const country = this._createTextInput(hot.country || "", { maxLength: 2, placeholder: "US", width: "90px" });
      country.addEventListener("input", () => {
        country.value = country.value.toUpperCase().replace(/[^A-Z]/g, "");
      });
      this._appendTile({
        icon: "public",
        title: "Country",
        subtitle: "Two-letter code: decides which channels and power the hotspot may use",
        trailing: country,
        dense: false,
      });
      this._appendTile({
        icon: "wifi_channel",
        title: "Frequency band and channel",
        trailing: this._createSelect(
          constants.SOFT_AP_BANDS.map((item) => ({ value: item.key, label: item.name })),
          saved.bandType.key,
          async (value) => {
            const selected = this._bandByKey(value);
            if (selected) {
              draft.bandType = selected;
            }
          },
        ),
      });
      this._appendDescription(
        "5 GHz is faster and leaves the car's Bluetooth alone; if the car does not see the network, try another channel (allowed channels differ by country). On 2.4 GHz the hotspot shares the radio with Bluetooth, which can drop frames while Bluetooth audio plays.",
      );
      this._appendDivider();

      this._appendHeading("When there is no internet");
      this._appendTile({
        icon: "wifi_off",
        title: "Keep the car on the hotspot",
        subtitle: "Answer the car's connectivity check here",
        trailing: this._createSwitch(saved.isOfflineModeEnabled, async (checked) => {
          draft.isOfflineModeEnabled = checked;
        }),
        dense: false,
      });
      this._appendDescription(
        "The car leaves Wi-Fi networks that have no internet. With this on it stays connected to this device even without LTE; the car's own online features (the Tesla app, live traffic of the car's maps) then need the LTE modem.",
      );
      this._appendTile({
        icon: "data_thresholding",
        title: "Tesla telemetry",
        subtitle: "Turn off to save mobile data",
        trailing: this._createSwitch(saved.isOfflineModeTelemetryEnabled, async (checked) => {
          draft.isOfflineModeTelemetryEnabled = checked;
        }),
        dense: false,
      });
      this._appendTile({
        icon: "update",
        title: "Tesla software downloads",
        subtitle: "Turn off to save mobile data; the car still sees that an update exists",
        trailing: this._createSwitch(saved.isOfflineModeTeslaFirmwareDownloadsEnabled, async (checked) => {
          draft.isOfflineModeTeslaFirmwareDownloadsEnabled = checked;
        }),
        dense: false,
      });
      this._appendDivider();

      this._appendHeading("LTE");
      const lte = net.lte;
      let lteFields = null;
      if (lte && lte.managed) {
        const apn = this._createTextInput(lte.apn || "", { maxLength: 63, placeholder: "Automatic" });
        this._appendTile({ icon: "cell_tower", title: "APN", subtitle: "From your mobile operator; empty uses the modem's default", trailing: apn, dense: false });
        const user = this._createTextInput(lte.user || "", { maxLength: 64, placeholder: "None" });
        this._appendTile({ icon: "person", title: "APN user name", trailing: user, dense: false });
        const pass = this._createTextInput("", {
          type: "password",
          maxLength: 64,
          placeholder: lte.passwordSet ? "Unchanged" : "None",
          autocomplete: "new-password",
        });
        this._appendTile({ icon: "key", title: "APN password", trailing: pass, dense: false });
        const lteDraft = { roaming: Boolean(lte.roaming), enabled: lte.enabled !== false };
        this._appendTile({
          icon: "travel_explore",
          title: "Data roaming",
          trailing: this._createSwitch(lteDraft.roaming, async (checked) => {
            lteDraft.roaming = checked;
          }),
        });
        this._appendTile({
          icon: "signal_cellular_alt",
          title: "Mobile data",
          trailing: this._createSwitch(lteDraft.enabled, async (checked) => {
            lteDraft.enabled = checked;
          }),
        });
        lteFields = { apn, user, pass, lteDraft };
      } else {
        this._appendTile({
          icon: "cell_tower",
          title: "LTE modem",
          trailing: this._createTrailingText(net.modemDetected ? "Detected" : "Not detected"),
        });
        this._appendDescription(
          net.modemDetected
            ? "This modem is a router of its own (a USB \"HiLink\" stick such as the Huawei E3372): it keeps its APN itself. Change it on the modem's own web page from a computer, or plug it into one once. Its operator and signal show on the Status page."
            : "Plug in a USB LTE modem. HiLink sticks (Huawei E3372 and similar) work without settings; modems that need an APN here are managed by ModemManager, which this system does not run.",
        );
      }
      this._appendDivider();

      const message = utils.createElement("div", "settings-description settings-save-message", "");
      message.hidden = true;
      const row = utils.createElement("div", "settings-save-row", null);
      const now = this._createActionButton("Save and apply now");
      const later = this._createActionButton("Save for next start");
      row.appendChild(now);
      row.appendChild(later);
      this.content.appendChild(row);
      this.content.appendChild(message);
      this._appendDescription(
        "Apply now restarts the hotspot: the car drops off the Wi-Fi for a few seconds and joins again (with the new password, if you changed it, after you enter it in the car). LTE settings always take effect right away.",
      );

      const show = (text, error) => {
        message.textContent = text;
        message.classList.toggle("settings-error-text", Boolean(error));
        message.hidden = false;
      };
      const submit = async (when) => {
        const request = { when, hotspot: {} };
        const name = ssid.value;
        if (new TextEncoder().encode(name).length < 1 || new TextEncoder().encode(name).length > 32) {
          ssid.classList.add("invalid");
          show("The network name needs 1 to 32 characters.", true);
          return;
        }
        ssid.classList.remove("invalid");
        if (name !== (hot.ssid || "")) {
          request.hotspot.ssid = name;
        }
        if (psk.value) {
          if (!/^(?:[\x20-\x7e]{8,63}|[0-9A-Fa-f]{64})$/.test(psk.value)) {
            psk.classList.add("invalid");
            show("The password needs 8 to 63 characters (letters, digits, symbols).", true);
            return;
          }
          request.hotspot.psk = psk.value;
        }
        psk.classList.remove("invalid");
        if (country.value !== (hot.country || "")) {
          if (!/^[A-Z]{2}$/.test(country.value)) {
            country.classList.add("invalid");
            show("The country is a two-letter code, such as US, GB or DE.", true);
            return;
          }
          request.hotspot.country = country.value;
        }
        country.classList.remove("invalid");
        if (lteFields) {
          if (!/^(?:[A-Za-z0-9][A-Za-z0-9.-]{0,62})?$/.test(lteFields.apn.value)) {
            lteFields.apn.classList.add("invalid");
            show("The APN may hold letters, digits, dots and dashes.", true);
            return;
          }
          lteFields.apn.classList.remove("invalid");
          request.lte = {
            apn: lteFields.apn.value,
            user: lteFields.user.value,
            roaming: lteFields.lteDraft.roaming,
            enabled: lteFields.lteDraft.enabled,
          };
          if (lteFields.pass.value) {
            request.lte.password = lteFields.pass.value;
          }
        }
        now.disabled = later.disabled = true;
        show("Saving…", false);
        try {
          await this.apiClient.setSoftApBand(draft.bandType.band);
          await this.apiClient.setSoftApChannelWidth(draft.bandType.channelWidth);
          await this.apiClient.setSoftApChannel(draft.bandType.channel);
          await this.apiClient.setOfflineModeState(draft.isOfflineModeEnabled ? 1 : 0);
          await this.apiClient.setOfflineModeTelemetryState(draft.isOfflineModeTelemetryEnabled ? 1 : 0);
          await this.apiClient.setOfflineModeTeslaFirmwareDownloads(draft.isOfflineModeTeslaFirmwareDownloadsEnabled ? 1 : 0);
        } catch (_error) {
          show("The device did not save the hotspot settings.", true);
          now.disabled = later.disabled = false;
          return;
        }
        const result = await this.apiClient.setNetwork(request);
        now.disabled = later.disabled = false;
        if (!result.ok) {
          show("Not saved: " + (result.message || "service error"), true);
          return;
        }
        psk.value = "";
        if (lteFields) {
          lteFields.pass.value = "";
        }
        await this._handleSystemConfigurationChanged();
        show(
          when === "now"
            ? "Saved. The hotspot restarts in a moment; the car reconnects by itself."
            : "Saved. The hotspot changes take effect at the next start.",
          false,
        );
      };
      now.addEventListener("click", () => submit("now"));
      later.addEventListener("click", () => submit("boot"));

      // the backup Wi-Fi: its own block, redrawn by itself after each change (changes apply at once)
      this._appendDivider();
      const wifiBlock = utils.createElement("div", "settings-wifi-block", null);
      this.content.appendChild(wifiBlock);
      this._renderBackupWifi(token, wifiBlock, net.wifi);
    }

    // teslalinux: a second Wi-Fi card joins a saved network (home, a phone's hotspot, a garage) and
    // host/teslalinux-uplink routes through it when LTE has no internet, e.g. underground, or whenever
    // it is connected. POST /api/network {"wifi": ...}, GET /api/wifiScan.
    _renderBackupWifi(token, block, wifi) {
      const stillHere = () => token === this._renderToken && this.activeSectionId === "network" && block.isConnected;
      block.innerHTML = "";
      this._withTarget(block, () => {
        this._appendHeading("Backup Wi-Fi");
        if (!wifi) {
          this._appendDescription("The backup Wi-Fi settings are not available on this device.");
          return;
        }
        const redraw = async () => {
          let fresh = null;
          try {
            fresh = (await this.apiClient.fetchNetwork()).wifi;
          } catch (_error) {
            fresh = wifi;
          }
          if (stillHere()) {
            this._renderBackupWifi(token, block, fresh);
          }
        };
        const message = utils.createElement("div", "settings-description settings-save-message", "");
        message.hidden = true;
        const show = (text, error) => {
          message.textContent = text;
          message.classList.toggle("settings-error-text", Boolean(error));
          message.hidden = false;
        };
        const send = async (ops, doneText) => {
          show("Saving…", false);
          const r = await this.apiClient.setNetwork({ when: "now", wifi: ops });
          if (!r.ok) {
            show("Not saved: " + (r.message || "service error"), true);
            return false;
          }
          await redraw();
          if (doneText) {
            const again = block.querySelector(".settings-save-message");
            if (again) {
              again.textContent = doneText;
              again.hidden = false;
            }
          }
          return true;
        };

        if (!wifi.present) {
          this._appendTile({
            icon: "wifi_off",
            title: "No second Wi-Fi card",
            subtitle: "The built-in Wi-Fi (" + (wifi.apIface || "wlan0") + ") runs the hotspot",
            dense: false,
          });
          this._appendDescription(
            "Plug in a USB Wi-Fi adapter to join a known network when LTE has no signal, for example in an underground car park. Networks saved below are used as soon as one is plugged in.",
          );
        } else {
          this._appendTile({
            icon: wifi.ssid ? "wifi" : "wifi_off",
            title: wifi.ssid ? "Connected to " + wifi.ssid : "Not connected",
            subtitle:
              (wifi.iface || "") +
              (typeof wifi.signalDbm === "number" ? " · signal " + wifi.signalDbm + " dBm" : "") +
              (wifi.enabled ? "" : " · switched off"),
            dense: false,
          });
        }
        this._appendTile({
          icon: "wifi_tethering",
          title: "Use backup Wi-Fi",
          trailing: this._createSwitch(wifi.enabled !== false, async (checked) => {
            await send({ enabled: checked });
          }),
        });
        this._appendTile({
          icon: "alt_route",
          title: "Use Wi-Fi",
          trailing: this._createSelect(
            [
              { value: "lte", label: "When LTE has no internet" },
              { value: "wifi", label: "Whenever connected" },
            ],
            wifi.prefer === "wifi" ? "wifi" : "lte",
            async (value) => {
              await send({ prefer: value });
            },
          ),
          dense: false,
        });
        this._appendDescription(
          "\"When LTE has no internet\" keeps LTE in use and switches to a saved Wi-Fi within about 10 seconds of LTE losing the internet, and back once LTE works again. \"Whenever connected\" uses the Wi-Fi first to save mobile data (at home, on a phone's hotspot).",
        );

        this._appendHeading("Saved networks");
        const networks = Array.isArray(wifi.networks) ? wifi.networks : [];
        if (!networks.length) {
          this._appendDescription("No networks saved yet.");
        }
        networks.forEach((n) => {
          const remove = this._createActionButton("Remove");
          this._appendTile({
            icon: n.open ? "lock_open" : "lock",
            title: n.ssid,
            subtitle: [n.open ? "Open" : "Password saved", n.hidden ? "hidden" : "", wifi.ssid === n.ssid ? "connected" : ""]
              .filter(Boolean)
              .join(" · "),
            trailing: remove,
            dense: false,
          });
          this._armButton(remove, "Remove", async () => {
            remove.disabled = true;
            await send({ remove: n.ssid });
          });
        });

        this._appendHeading("Add a network");
        const ssid = this._createTextInput("", { maxLength: 32, placeholder: "Network name" });
        this._appendTile({ icon: "wifi", title: "Network name", trailing: ssid, dense: false });
        const pass = this._createTextInput("", { type: "password", maxLength: 64, placeholder: "Password (empty: open network)", autocomplete: "new-password" });
        this._appendTile({ icon: "password", title: "Password", trailing: pass, dense: false });
        let hidden = false;
        this._appendTile({
          icon: "visibility_off",
          title: "Hidden network",
          subtitle: "It does not announce its name",
          trailing: this._createSwitch(false, async (checked) => {
            hidden = checked;
          }),
        });
        const row = utils.createElement("div", "settings-save-row", null);
        const add = this._createActionButton("Add network");
        const scan = this._createActionButton("Scan for networks");
        scan.disabled = !wifi.present;
        row.appendChild(add);
        row.appendChild(scan);
        block.appendChild(row);
        block.appendChild(message);
        const found = utils.createElement("div", "settings-scan-list", null);
        block.appendChild(found);

        add.addEventListener("click", async () => {
          const name = ssid.value;
          const bytes = new TextEncoder().encode(name).length;
          if (bytes < 1 || bytes > 32) {
            ssid.classList.add("invalid");
            show("The network name needs 1 to 32 characters.", true);
            return;
          }
          ssid.classList.remove("invalid");
          if (pass.value && !/^(?:[\x20-\x7e]{8,63}|[0-9A-Fa-f]{64})$/.test(pass.value)) {
            pass.classList.add("invalid");
            show("A Wi-Fi password has 8 to 63 characters.", true);
            return;
          }
          pass.classList.remove("invalid");
          add.disabled = true;
          const ok = await send(
            { add: { ssid: name, psk: pass.value, hidden, open: pass.value === "" } },
            "Saved “" + name + "”. The backup card joins it when it is in range.",
          );
          add.disabled = false;
          if (ok) {
            pass.value = "";
          }
        });

        scan.addEventListener("click", async () => {
          scan.disabled = true;
          scan.textContent = "Scanning…";
          found.innerHTML = "";
          let result;
          try {
            result = await this.apiClient.scanWifi();
          } catch (_error) {
            result = { networks: [], error: "scan failed" };
          }
          if (!stillHere()) {
            return;
          }
          scan.disabled = false;
          scan.textContent = "Scan again";
          const list = Array.isArray(result.networks) ? result.networks : [];
          if (!list.length) {
            found.appendChild(utils.createElement("p", "settings-description", result.error ? "The scan failed." : "No networks found."));
            return;
          }
          list.forEach((n) => {
            const item = utils.createElement("button", "settings-scan-item", null);
            item.type = "button";
            const icon = utils.createElement("span", "material-symbols-rounded settings-scan-icon", this._wifiBars(n));
            const name = utils.createElement("span", "settings-scan-name", n.ssid);
            const meta = utils.createElement(
              "span",
              "settings-scan-meta",
              [n.band, n.secure ? "secured" : "open", typeof n.signalDbm === "number" ? n.signalDbm + " dBm" : typeof n.signalPercent === "number" ? n.signalPercent + " %" : ""]
                .filter(Boolean)
                .join(" · "),
            );
            item.appendChild(icon);
            item.appendChild(name);
            item.appendChild(meta);
            item.addEventListener("click", () => {
              ssid.value = n.ssid;
              pass.value = "";
              if (n.secure) {
                pass.focus();
              }
              found.querySelectorAll(".settings-scan-item").forEach((el) => el.classList.toggle("active", el === item));
            });
            found.appendChild(item);
          });
        });
      });
    }

    _wifiBars(n) {
      const pct = typeof n.signalPercent === "number" ? n.signalPercent : typeof n.signalDbm === "number" ? Math.min(100, Math.max(0, 2 * (n.signalDbm + 100))) : 0;
      // the bundled Material Icons font has full, partial and empty Wi-Fi glyphs (no 1-3 bar ones)
      if (pct >= 60) {
        return n.secure ? "signal_wifi_4_bar_lock" : "signal_wifi_4_bar";
      }
      return pct >= 30 ? "network_wifi" : "signal_wifi_0_bar";
    }

    // the append helpers write into this element instead of the section's content while fn runs
    _withTarget(el, fn) {
      const previous = this._target;
      this._target = el;
      try {
        fn();
      } finally {
        this._target = previous;
      }
    }

    // teslalinux: updates of the operating system underneath the app (apt on Pi OS, apk on Alpine),
    // GET/POST /api/systemUpdate, host/teslalinux-system-update. While a run goes on, the section is not
    // redrawn: a progress bar follows the package manager's own progress and a terminal streams the
    // run's output line by line (GET /api/systemUpdate?log=OFFSET), then the section is drawn once more
    // with the result. The output of the last run stays below, folded.
    async _renderSystemUpdateSection(token, refresh) {
      if (!refresh) {
        this._appendLoadingState();
      }
      let st;
      try {
        st = await this.apiClient.fetchSystemUpdate();
      } catch (_error) {
        this._renderErrorState("Service error");
        return;
      }
      if (token !== this._renderToken || this.activeSectionId !== "system") {
        return;
      }
      const scroll = this.content.scrollTop;
      this.content.innerHTML = "";
      const fmt = this._fmt();
      const available = Array.isArray(st.available) ? st.available : [];
      const pending = available.filter((p) => !p.held);
      const held = available.filter((p) => p.held);
      const busy = st.running === true;

      this._appendTile({
        icon: "terminal",
        title: st.os || "Operating system",
        subtitle: "Kernel " + (st.kernel || "?") + (st.manager ? " · updates through " + st.manager : ""),
        dense: false,
      });
      if (!st.manager) {
        this._appendDescription("This system has no package manager this page knows (apt or apk), so it cannot update itself.");
        return;
      }

      const statusText = busy
        ? st.phase === "upgrading"
          ? "Installing updates"
          : "Looking for updates"
        : st.message || (st.lastCheck ? "" : "Not checked yet");
      const check = this._createActionButton(busy ? "Working…" : "Check now");
      check.disabled = busy;
      this._appendTile({
        icon: busy ? "sync" : st.result === "error" ? "error" : pending.length ? "system_update" : "verified",
        title: statusText,
        subtitle: "Last check " + fmt.ago(st.lastCheck) + (st.lastUpgrade ? " · last install " + fmt.ago(st.lastUpgrade) : ""),
        trailing: check,
        dense: false,
      });
      const start = async (action, button) => {
        button.disabled = true;
        const r = await this.apiClient.systemUpdateAction(action);
        if (!r.ok) {
          button.disabled = false;
          this._appendDescription("Could not start: " + r.message);
          return;
        }
        this._sysLog = null; // a new run: a new terminal
        await this._renderSystemUpdateSection(token, true);
      };
      check.addEventListener("click", () => start("check", check));

      // progress and terminal
      const term = this._createTerminal(busy, st.result === "error");
      if (busy) {
        const prog = utils.createElement("div", "settings-progress", null);
        const label = utils.createElement("div", "settings-progress-label", null);
        const step = utils.createElement("span", "settings-progress-step", "Starting…");
        const pct = utils.createElement("span", "settings-progress-percent", "");
        label.appendChild(step);
        label.appendChild(pct);
        const track = utils.createElement("div", "settings-progress-track indeterminate", null);
        const bar = utils.createElement("div", "settings-progress-bar", null);
        track.appendChild(bar);
        prog.appendChild(label);
        prog.appendChild(track);
        this.content.appendChild(prog);
        term.progress = { step, pct, track, bar };
        this._applySystemProgress(term, st.progress);
      }
      if (busy || st.started) {
        this.content.appendChild(term.root);
      }

      if (pending.length && !busy) {
        const install = this._createActionButton("Install " + pending.length);
        this._appendTile({
          icon: "download",
          title: pending.length + " update" + (pending.length === 1 ? "" : "s") + " available",
          subtitle: st.uplink === "lte" ? "Downloads over the LTE modem: this uses mobile data" : "",
          trailing: install,
          dense: false,
        });
        this._armButton(install, "Install " + pending.length, () => start("upgrade", install));
        const list = utils.createElement("div", "settings-package-list", null);
        pending.slice(0, 60).forEach((p) => {
          const line = utils.createElement("div", "settings-package", null);
          line.appendChild(utils.createElement("span", "settings-package-name", p.name));
          line.appendChild(utils.createElement("span", "settings-package-version", (p.from ? p.from + " → " : "") + p.to));
          list.appendChild(line);
        });
        if (pending.length > 60) {
          list.appendChild(utils.createElement("div", "settings-package", "and " + (pending.length - 60) + " more"));
        }
        this.content.appendChild(list);
        this._appendDescription(
          "Installing does not stop the app; services pick up the new versions when they restart. The car stays connected.",
        );
      }
      if (held.length) {
        this._appendDescription(
          "Held back (listed in /etc/teslalinux/system-update.hold): " + held.map((p) => p.name).join(", "),
        );
      }
      this._appendDivider();

      const restart = this._createActionButton("Restart");
      this._appendTile({
        icon: "restart_alt",
        title: st.rebootRequired ? "Restart needed" : "Restart device",
        subtitle: st.rebootRequired
          ? "A new kernel or system library is installed and takes effect after a restart"
          : "The page reconnects by itself after about half a minute",
        trailing: restart,
        dense: false,
      });
      this._armButton(restart, "Restart", async () => {
        restart.disabled = true;
        restart.textContent = "Restarting…";
        const r = await this.apiClient.systemUpdateAction("reboot");
        if (!r.ok) {
          restart.disabled = false;
          restart.textContent = "Restart";
          this._appendDescription("Could not restart: " + r.message);
        }
      });
      this.content.scrollTop = scroll;

      if (busy || st.started) {
        await this._streamSystemLog(token, term, st, busy);
      }
    }

    // the terminal: a <pre> with a blinking cursor while the run goes on; afterwards folded in a
    // <details> ("Output of the last run"), open when the run failed
    _createTerminal(live, failed) {
      const pre = utils.createElement("pre", "settings-terminal", null);
      const text = global.document.createTextNode("");
      pre.appendChild(text);
      const cursor = utils.createElement("span", "settings-terminal-cursor", null);
      if (live) {
        pre.appendChild(cursor);
      }
      let root = pre;
      if (!live) {
        root = utils.createElement("details", "settings-terminal-details", null);
        root.appendChild(utils.createElement("summary", null, "Output of the last run"));
        root.appendChild(pre);
        root.open = failed;
      }
      return { root, pre, text, cursor, queue: [], dripping: false, progress: null };
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
      const stillHere = () => token === this._renderToken && this.activeSectionId === "system" && term.pre.isConnected;
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
          this._renderSystemUpdateSection(token, true);
        };
        if (term.progress) {
          term.progress.track.classList.remove("indeterminate");
          term.progress.bar.style.width = "100%";
        }
        this._pollTimer = global.setTimeout(settle, 400);
      };
      this._pollTimer = global.setTimeout(tick, 700);
    }

    // a button that needs a second tap within 4 s: a stray touch while driving must not act
    _armButton(button, label, action) {
      let armed = false;
      let timer = null;
      button.addEventListener("click", async () => {
        if (!armed) {
          armed = true;
          button.textContent = "Tap again";
          button.classList.add("armed");
          timer = global.setTimeout(() => {
            armed = false;
            button.textContent = label;
            button.classList.remove("armed");
          }, 4000);
          return;
        }
        global.clearTimeout(timer);
        armed = false;
        button.classList.remove("armed");
        await action();
      });
    }

    _createTextInput(value, options) {
      const opts = options || {};
      const input = utils.createElement("input", "settings-text-input", null);
      input.type = opts.type || "text";
      input.value = value;
      input.spellcheck = false;
      input.setAttribute("autocapitalize", "off");
      input.setAttribute("autocorrect", "off");
      input.setAttribute("autocomplete", opts.autocomplete || "off");
      if (opts.maxLength) {
        input.maxLength = opts.maxLength;
      }
      if (opts.placeholder) {
        input.placeholder = opts.placeholder;
      }
      if (opts.width) {
        input.style.width = opts.width;
      }
      return input;
    }

    _fmt() {
      return {
        bytes: (n) => {
          if (typeof n !== "number" || !isFinite(n)) {
            return "?";
          }
          const units = ["B", "KB", "MB", "GB", "TB"];
          let i = 0;
          while (n >= 1000 && i < units.length - 1) {
            n /= 1000;
            i += 1;
          }
          return (i === 0 ? String(n) : n.toFixed(n < 10 ? 1 : 0)) + " " + units[i];
        },
        duration: (s) => {
          const d = Math.floor(s / 86400);
          const h = Math.floor((s % 86400) / 3600);
          const m = Math.floor((s % 3600) / 60);
          if (d) {
            return d + " d " + h + " h";
          }
          if (h) {
            return h + " h " + m + " min";
          }
          return m ? m + " min" : Math.floor(s) + " s";
        },
        ago: (unix) => {
          if (!unix) {
            return "never";
          }
          const s = Math.max(0, Date.now() / 1000 - unix);
          if (s < 90) {
            return "just now";
          }
          if (s < 5400) {
            return Math.round(s / 60) + " min ago";
          }
          if (s < 129600) {
            return Math.round(s / 3600) + " h ago";
          }
          return Math.round(s / 86400) + " days ago";
        },
      };
    }

    async _renderDeviceSection(token) {
      this._appendLoadingState();

      let deviceInfo;
      try {
        deviceInfo = await this.apiClient.fetchDeviceInfo();
      } catch (_error) {
        this._renderErrorState("Service error");
        return;
      }

      if (token !== this._renderToken || this.activeSectionId !== "device") {
        return;
      }

      const deviceModel = utils.mapDeviceModelName(deviceInfo.device_model);

      this.content.innerHTML = "";

      this._appendTile({
        icon: "device_thermostat",
        title: "CPU Temperature",
        trailing: this._createTrailingText(String(utils.toInt(deviceInfo.cpu_temperature, 0)) + "\u00B0C"),
      });
      this._appendDescription(
        "CPU temperature should not exceed 80\u00B0C. Make sure the device is actively cooled and proper ventilation is provided.",
      );
      this._appendDivider();

      this._appendTile({
        icon: "perm_device_info",
        title: "Model",
        trailing: this._createTrailingText(deviceModel),
        dense: false,
      });
      this._appendTile({
        icon: "developer_board_rounded",
        title: "Serial Number",
        trailing: this._createTrailingText(String(deviceInfo.serial_number || "undefined")),
        dense: false,
      });
      await this._appendAppRestartTile(token);
      await this._appendGpuSpeedTile(token);
    }

    // teslalinux: the Pi's GPU clock (teslalinux-set-gpu-speed through /api/gpuSpeed). Waze's map is
    // limited by the GPU; faster clocks need cooling, and apply at the next restart.
    async _appendGpuSpeedTile(token) {
      let st;
      try {
        st = await this.apiClient.fetchGpuSpeed();
      } catch (_error) {
        return;
      }
      if (token !== this._renderToken || this.activeSectionId !== "device") {
        return;
      }
      const describe = (s) => {
        const now = [];
        if (s.gpuMHz) now.push("GPU at " + s.gpuMHz + " MHz");
        if (s.temperatureC) now.push(Math.round(s.temperatureC) + " \u00b0C");
        if (s.throttled && s.throttled !== "0x0") now.push("the Pi has slowed itself down (heat or power)");
        return (s.pendingRestart ? "Applies at the next restart. " : "") + (now.length ? "Now: " + now.join(", ") : "Now: not measured");
      };
      this._appendDivider();
      const select = this._createSelect(
        [
          { value: "standard", label: "Standard (500 MHz)" },
          { value: "faster", label: "Faster (600 MHz)" },
          { value: "fastest", label: "Fastest (700 MHz)" },
        ],
        st.speed,
        async (value) => {
          try {
            st = await this.apiClient.setGpuSpeed(value);
            subtitle.textContent = describe(st);
          } catch (_error) {
            subtitle.textContent = "Not saved (service error)";
          }
        },
      );
      const gpuRow = this._appendTile({
        icon: "speed",
        title: "GPU speed",
        subtitle: describe(st),
        trailing: select,
        dense: false,
      });
      const subtitle = gpuRow.querySelector(".settings-tile-subtitle");
      this._appendDescription(
        "How fast the Pi's graphics chip runs. Waze's map is drawn by it, so a faster clock makes the map smoother. Faster needs a heatsink, Fastest a heatsink and a fan (it also raises the chip's voltage a little). A Pi that gets too hot slows itself down, so without cooling a faster setting only adds heat. Applies at the next restart.",
      );
    }

    // teslalinux: restart the app (Waze under ATL) without touching the display stream or the page.
    // GET /api/appState + POST /api/appRestart (services/api/api.py); left out when the API lacks them.
    async _appendAppRestartTile(token) {
      let appState;
      try {
        appState = await this.apiClient.fetchAppState();
      } catch (_error) {
        return;
      }
      if (token !== this._renderToken || this.activeSectionId !== "device") {
        return;
      }
      const describe = (state) => {
        switch (state) {
          case "active":
            return "Running";
          case "activating":
          case "restarting":
            return "Restarting\u2026";
          case "inactive":
            return "Stopped";
          case "failed":
            return "Stopped after an error";
          default:
            return "State unknown";
        }
      };
      this._appendDivider();
      const tile = this._appendTile({
        icon: "refresh",
        title: "Restart Waze",
        subtitle: describe(appState.state),
        trailing: this._createActionButton("Restart"),
        dense: false,
      });
      const subtitle = tile.querySelector(".settings-tile-subtitle");
      const button = tile.querySelector(".settings-action-button");
      this._appendDescription(
        "Closes Waze and starts it again (about a minute). The display shows the new start as soon as it is drawn. Use it when Waze stops responding.",
      );

      let armed = false;
      let disarmTimer = null;
      button.addEventListener("click", async () => {
        // two taps: a stray touch while driving must not restart navigation
        if (!armed) {
          armed = true;
          button.textContent = "Tap again";
          button.classList.add("armed");
          disarmTimer = global.setTimeout(() => {
            armed = false;
            button.textContent = "Restart";
            button.classList.remove("armed");
          }, 4000);
          return;
        }
        global.clearTimeout(disarmTimer);
        armed = false;
        button.classList.remove("armed");
        button.disabled = true;
        button.textContent = "Restarting\u2026";
        subtitle.textContent = describe("restarting");
        try {
          await this.apiClient.restartApp();
        } catch (_error) {
          subtitle.textContent = "Restart failed (service error)";
          button.disabled = false;
          button.textContent = "Restart";
          return;
        }
        // follow the service until it runs again (or two minutes pass)
        const started = Date.now();
        const poll = async () => {
          if (!tile.isConnected) {
            return;
          }
          let state = "unknown";
          try {
            state = (await this.apiClient.fetchAppState()).state;
          } catch (_error) {
            // keep polling
          }
          subtitle.textContent = describe(state);
          if (state === "active" || Date.now() - started > 120000) {
            button.disabled = false;
            button.textContent = "Restart";
            return;
          }
          global.setTimeout(poll, 1500);
        };
        global.setTimeout(poll, 1500);
      });
    }

    // teslalinux: the Pi updates Waze from Google Play by itself (tools/appupdate/teslalinux-appupdate,
    // docs/APP-UPDATES.md). GET /api/appUpdate, POST /api/appUpdateSettings and /api/appUpdateCheck
    // (services/api/api.py).
    async _renderAppUpdateSection(token) {
      this._appendLoadingState();
      let info;
      try {
        info = await this.apiClient.fetchAppUpdate();
      } catch (_error) {
        this._renderErrorState("Service error");
        return;
      }
      if (token !== this._renderToken || this.activeSectionId !== "updates") {
        return;
      }
      this.content.innerHTML = "";
      const settings = info.settings || {};
      const ago = (unix) => {
        if (!unix) {
          return "never";
        }
        const s = Math.max(0, Date.now() / 1000 - unix);
        if (s < 90) {
          return "just now";
        }
        if (s < 5400) {
          return Math.round(s / 60) + " min ago";
        }
        if (s < 129600) {
          return Math.round(s / 3600) + " h ago";
        }
        return Math.round(s / 86400) + " days ago";
      };
      const when = (unix) => new Date(unix * 1000).toLocaleString();
      const ver = (v) => (v && v.versionName ? v.versionName : "unknown");
      const save = async (changes) => {
        try {
          await this.apiClient.setAppUpdateSettings(changes);
        } catch (_error) {
          this._renderErrorState("Service error");
          return;
        }
        await this._renderActiveSection();
      };
      const source = settings.source || "apkpure";
      const playUsable = settings.playEnabled === true;

      // --- source
      const sourceNote = utils.createElement("p", "settings-description", "");
      this._appendTile({
        icon: "system_update",
        title: "Update source",
        subtitle: "Waze " + ver(info.installed) + " installed",
        trailing: this._createSelect(
          [
            { value: "apkpure", label: "APKPure (automatic)" },
            { value: "manual", label: "Manual uploads only" },
            { value: "play", label: playUsable ? "Google Play via Aurora" : "Google Play via Aurora (unavailable)" },
          ],
          source,
          async (value) => {
            if (value === "play" && !playUsable) {
              // the dropdown re-renders the section after this; the note says why nothing changed
              this._appUpdateNote = "Google Play via Aurora is off: Aurora's login service refuses other clients. It can be enabled under Advanced.";
              return;
            }
            this._appUpdateNote = "";
            await this.apiClient.setAppUpdateSettings({ source: value });
          },
        ),
        dense: false,
      });
      sourceNote.textContent =
        this._appUpdateNote ||
        (source === "manual" || (source === "play" && !playUsable)
          ? "No automatic checks. Upload a Waze bundle below (from APKMirror: .apkm; also .xapk, .apks or a zip of APKs)."
          : "The Pi checks once a day (a few hundred KB), downloads a newer Waze, verifies its signature against the installed one and installs it when the car is not connected.");
      this._appUpdateNote = "";
      this.content.appendChild(sourceNote);

      if (source !== "manual" && !(source === "play" && !playUsable)) {
        let subtitle = "Waze " + ver(info.installed);
        if (info.latest && info.latest.versionName) {
          subtitle += " · latest " + info.latest.versionName;
        }
        subtitle += " · checked " + ago(info.lastCheckOk);
        this._appendTile({
          icon: "update",
          title: "Update Waze automatically",
          subtitle: subtitle,
          trailing: this._createSwitch(settings.autoUpdate !== false, (checked) => save({ autoUpdate: checked })),
          dense: false,
        });
        // teslalinux: a version pin: download exactly this version and no newer one (the Map frame
        // rate "Full" works only on the Waze versions it is verified for)
        const pinCode = settings.pinVersionCode || 0;
        const pinOptions = [{ value: "0", label: "Latest" }];
        const choices = (info.pinChoices || []).slice();
        if (pinCode && !choices.some((c) => c.versionCode === pinCode)) {
          choices.push({ versionCode: pinCode, versionName: settings.pinVersionName, reason: "" });
        }
        for (let index = 0; index < choices.length; index += 1) {
          const c = choices[index];
          pinOptions.push({ value: String(c.versionCode), label: "Stay on " + (c.versionName || "versionCode " + c.versionCode) });
        }
        // the versions the Map frame rate "Full" works on, named in the subtitle
        const pacingNames = choices.filter((c) => c.reason === "mapPacing" && c.versionName).map((c) => c.versionName);
        const pacingNote = pacingNames.length ? " Map frame rate Full works on " + pacingNames.join(", ") + " only." : "";
        let pinSubtitle;
        if (!pinCode) {
          pinSubtitle = "Updates to each new Waze version." + pacingNote;
        } else {
          const pinName = settings.pinVersionName || "versionCode " + pinCode;
          const installedCode = (info.installed && info.installed.versionCode) || 0;
          pinSubtitle =
            installedCode > pinCode
              ? "Pinned to " + pinName + ": the installed " + ver(info.installed) + " is newer, so no further updates (never back to an older version)"
              : installedCode === pinCode
                ? "Pinned to " + pinName + ": installed, no newer version is downloaded"
                : "Pinned to " + pinName + ": downloaded and installed like an update, then no newer one";
        }
        this._appendTile({
          icon: "push_pin",
          title: "Waze version",
          subtitle: pinSubtitle,
          trailing: this._createSelect(pinOptions, String(pinCode), async (value) => {
            const code = parseInt(value, 10) || 0;
            const chosen = choices.find((c) => c.versionCode === code);
            await this.apiClient.setAppUpdateSettings({
              pinVersionCode: code,
              pinVersionName: chosen && chosen.versionName ? chosen.versionName : "",
            });
          }),
          dense: false,
        });
        this._appendTile({
          icon: "cell_tower",
          title: "Allow updates over LTE",
          subtitle: "Off: downloads (about 200 MB) wait for Wi-Fi or Ethernet",
          trailing: this._createSwitch(settings.allowMetered === true, (checked) => save({ allowMetered: checked })),
          dense: false,
        });
      }

      // --- status
      const lines = [];
      if (info.serverUnavailableSince) {
        lines.push("Update server unavailable since " + when(info.serverUnavailableSince) + ". Waze keeps running; the Pi retries by itself.");
      }
      if (info.pending && info.pending.versionName) {
        lines.push("Waze " + info.pending.versionName + " is verified and ready. It installs when the car is not connected, or at the next start.");
      }
      if (info.health && info.health.versionName) {
        lines.push("Checking that Waze " + info.health.versionName + " starts correctly…");
      }
      if (info.lastMessage) {
        lines.push("Last result (" + ago(info.lastCheck) + "): " + info.lastMessage);
      }
      const notices = info.notices || [];
      if (notices.length) {
        const last = notices[notices.length - 1];
        lines.push(when(last.at) + ": " + last.text);
      }
      if (lines.length) {
        this._appendDivider();
      }
      for (let index = 0; index < lines.length; index += 1) {
        this._appendDescription(lines[index]);
      }

      // --- check now (automatic sources)
      if (source !== "manual" && !(source === "play" && !playUsable)) {
        const checkTile = this._appendTile({
          icon: "sync",
          title: "Check for an update now",
          trailing: this._createActionButton("Check"),
          dense: false,
        });
        const checkButton = checkTile.querySelector(".settings-action-button");
        checkButton.addEventListener("click", async () => {
          checkButton.disabled = true;
          checkButton.textContent = "Checking…";
          try {
            await this.apiClient.checkAppUpdateNow();
          } catch (_error) {
            checkButton.textContent = "Failed";
            return;
          }
          // the check runs on the Pi; show its result when the state changes (or after a minute)
          const started = Date.now();
          const before = info.lastCheck || 0;
          const poll = async () => {
            if (!checkTile.isConnected) {
              return;
            }
            let now = null;
            try {
              now = await this.apiClient.fetchAppUpdate();
            } catch (_error) {
              // keep polling
            }
            if ((now && (now.lastCheck || 0) > before) || Date.now() - started > 60000) {
              await this._renderActiveSection();
              return;
            }
            global.setTimeout(poll, 2000);
          };
          global.setTimeout(poll, 2000);
        });
      }

      // --- manual upload (every source): an APKMirror .apkm, .xapk, .apks, zip of APKs or one APK
      this._appendDivider();
      const fileInput = document.createElement("input");
      fileInput.type = "file";
      fileInput.accept = ".apkm,.xapk,.apks,.zip,.apk";
      fileInput.style.display = "none";
      const uploadButton = this._createActionButton("Choose file");
      this._appendTile({
        icon: "upload_file",
        title: "Upload an update",
        subtitle: "Waze bundle: .apkm, .xapk, .apks, .zip or .apk (up to " + Math.round((info.maxUpload || 419430400) / 1048576) + " MB)",
        trailing: uploadButton,
        dense: false,
      });
      this.content.appendChild(fileInput);
      const uploadNote = utils.createElement("p", "settings-description", "");
      const lastUpload = info.upload;
      if (lastUpload && lastUpload.message) {
        uploadNote.textContent = "Last upload (" + ago(lastUpload.at) + "): " + lastUpload.message;
      } else {
        uploadNote.textContent =
          "The same checks as an automatic update: the signature must be Waze's own (the installed one's), the version newer, and the splits for this Pi present. Nothing else is installed.";
      }
      this.content.appendChild(uploadNote);
      uploadButton.addEventListener("click", () => fileInput.click());
      fileInput.addEventListener("change", async () => {
        const file = fileInput.files && fileInput.files[0];
        if (!file) {
          return;
        }
        const max = info.maxUpload || 419430400;
        if (file.size > max) {
          uploadNote.textContent = file.name + " is " + Math.round(file.size / 1048576) + " MB, over the limit of " + Math.round(max / 1048576) + " MB.";
          return;
        }
        uploadButton.disabled = true;
        uploadButton.textContent = "Uploading…";
        let result;
        try {
          result = await this.apiClient.uploadAppBundle(file, (sent, total) => {
            const pct = total ? Math.floor((sent * 100) / total) : 0;
            uploadNote.textContent =
              sent < total
                ? "Uploading " + file.name + ": " + pct + " % (" + Math.round(sent / 1048576) + " of " + Math.round(total / 1048576) + " MB)"
                : "Verifying " + file.name + " (signature, version, splits)…";
          });
        } catch (error) {
          uploadNote.textContent = "Upload failed: " + (error && error.message ? error.message : "service error");
          uploadButton.disabled = false;
          uploadButton.textContent = "Choose file";
          return;
        }
        uploadButton.disabled = false;
        uploadButton.textContent = "Choose file";
        fileInput.value = "";
        if (uploadNote.isConnected) {
          await this._renderActiveSection();
        }
        return result;
      });

      // --- advanced: Google Play through a token dispenser (off by default)
      this._appendDivider();
      this._appendTile({
        icon: "dns",
        title: "Advanced: Google Play via a token dispenser",
        subtitle: playUsable ? "Enabled" : "Off",
        trailing: this._createSwitch(playUsable, (checked) => save({ playEnabled: checked })),
        dense: false,
      });
      this._appendDescription(
        "An anonymous Google Play login from a token dispenser (no Google account). Aurora Store's public dispenser refuses clients other than Aurora Store, so this stays off unless you point it at a dispenser that accepts this Pi.",
      );
      if (playUsable) {
        const input = document.createElement("input");
        input.type = "url";
        input.className = "settings-text-input";
        input.placeholder = info.defaultDispenser || "https://auroraoss.com/api/auth";
        input.value = settings.dispenserUrl || "";
        input.spellcheck = false;
        const saveButton = this._createActionButton("Save");
        const field = utils.createElement("div", "settings-inline-field", null);
        field.appendChild(input);
        field.appendChild(saveButton);
        this._appendTile({
          icon: "link",
          title: "Dispenser address",
          trailing: field,
          dense: false,
        });
        this._appendDescription("Empty: Aurora Store's dispenser. Otherwise an https:// address (a mirror or your own).");
        saveButton.addEventListener("click", async () => {
          const value = input.value.trim();
          if (value !== "" && !/^https:\/\/[A-Za-z0-9.-]+(:[0-9]{1,5})?(\/[A-Za-z0-9._~%!$&'()*+,;=:@\/-]*)?$/.test(value)) {
            input.classList.add("invalid");
            return;
          }
          input.classList.remove("invalid");
          await save({ dispenserUrl: value });
        });
      }
    }

    _createActionButton(text) {
      return h("button", { class: "btn settings-action-button", type: "button" }, text);
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
          h("span", { class: "gl-glyph" }, icon(ic)),
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
        netCell = cell(weak ? "warn" : "ok", up && up.kind === "lte" ? "signal_cellular_alt" : up && up.kind === "wifi" ? "wifi" : "public", "Online", [via, lat].filter(Boolean).join(", "), "connections", "net.internet");
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
        out.push(UI.group("Car interface look", [sr, dr, pr]));

        const skin = look.mapSkin;
        const named = (v) => (v.versionName ? v.versionName : "versionCode " + v.versionCode);
        const checked = skin && Array.isArray(skin.verifiedVersions) ? skin.verifiedVersions.map(named).join(", ") : "";
        const unverified = Boolean(skin && !skin.supported);
        const savedColours = look.mapColours;
        const mr = UI.row({
          id: "waze.mapcolours",
          title: "Map colours",
          stack: true,
          chips: [UI.CHIP.custom("chip--restart", "restart_alt", "Graphite restarts Waze")],
          more: [
            "Graphite: Waze's own map recoloured when it starts, grey land, white roads by day and dark roads by night, a blue route. Muted: this screen tones Waze's map down (with the Graphite panels). Waze's own: as Waze draws it.",
            checked ? "Graphite is checked per Waze version: " + checked + "." : "Graphite is checked per Waze version.",
          ],
          control: UI.tiles(
            [
              { value: "graphite", label: "Graphite", svg: UI.SVG.colours("graphite"), caption: unverified ? "Not for this Waze" : "Recoloured by the device" },
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
        const pacingSaved = iface.mapPacing === "full" ? "full" : "app";
        const support = iface.mapPacingSupport;
        // (a third choice, "Automatic", can join these two)
        const pc = UI.row({
          id: "waze.pacing",
          title: "Map frame rate",
          chips: [UI.CHIP.restart()],
          stack: true,
          detail: "Full is smoother, and the Pi works harder",
          after: support && !support.supported ? UI.warnbox(this._pacingUnavailable(support)) : null,
          control: UI.segmented(
            [
              { value: "app", label: "Waze's own", caption: "30 per second, default" },
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
        const showSkinWarning = unverified && this._staged("mapcolours", savedColours) === "graphite";
        out.push(UI.group("Map", [showSkinWarning ? UI.warnbox(this._skinUnavailable(look)) : null, mr, zr, pc, sh].filter(Boolean)));
      }
      return out;
    }

    /**
     * Graphite map colours apply only to the Waze versions they were checked with (the device's
     * verified list, docs/MAP-COLOURS.md); for any other version Waze keeps its own palette and the
     * page mutes it instead.
     */
    _skinUnavailable(look) {
      const skin = look.mapSkin || {};
      const named = (v) => (v.versionName ? v.versionName : "versionCode " + v.versionCode);
      const installed = skin.versionCode ? "Waze " + named(skin) : "this Waze";
      const why =
        {
          "unverified-files": "Graphite colours are not available: " + installed + " has map files that differ from the checked ones.",
          "not-installed": "Graphite colours are not available: no installed Waze was found.",
          "no-manifest": "Graphite colours are not available on this device (no verified list).",
        }[skin.state] || "Graphite colours are not available for " + installed + ".";
      return why + (look.style === "graphite" ? " Waze's own colours are used, toned down by this screen." : " Waze's own colours are used.");
    }

    /** the full map frame rate applies only to the Waze versions it was checked with (tools/apps/waze-map-pacing.py) */
    _pacingUnavailable(support) {
      const named = (v) => (v.versionName ? v.versionName : "versionCode " + v.versionCode);
      const installed = support.versionCode ? "Waze " + named(support) : "this Waze";
      const checked = (support.verifiedVersions || []).map(named).join(", ");
      const why =
        {
          "not-installed": "Full is not available: no installed Waze was found.",
          "no-tool": "Full is not available on this device.",
        }[support.state] || "Full is not available for " + installed + (checked ? " (checked for " + checked + ")" : "") + ".";
      return why + " Waze keeps its own rate.";
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

      if (this._ifaceNow() === "car") {
        const voice = this._voiceRows(token);
        if (voice.length) groups.push(UI.group("Voice", voice));
      }
      if (!groups.length) throw new Error("no answer");
      return groups;
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
     * car interface's search. Off by default, per browser: a Tesla's browser has none, and the car's
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
        more: "A microphone button in the car interface's search, using this browser's own speech recognition.",
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
      const [status, iface, audio, display, appUpdate, sys, config, gpu, keyboard] = await Promise.all([
        safe(api.fetchStatus()),
        safe(api.fetchAppInterface()),
        safe(api.fetchAudioOutput()),
        safe(api.fetchDisplayState()),
        safe(api.fetchAppUpdate()),
        safe(api.fetchSystemUpdate()),
        safe(api.fetchSystemConfiguration()),
        safe(api.fetchGpuSpeed()),
        safe(api.fetchKeyboard()),
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
        isRearDisplayEnabled: utils.toInt(raw.isRearDisplayEnabled, 0),
        isRearDisplayPrioritised: utils.toInt(raw.isRearDisplayPrioritised, 0),
      };
    }

    _networkPendingFromSystemConfig(config) {
      return {
        bandType: utils.mapSoftApBandFromConfig(config),
        isSoftApEnabled:
          utils.toInt(config["persist.tesla-android.softap.is_enabled"], 1) === 1,
        isOfflineModeEnabled:
          utils.toInt(config["persist.tesla-android.offline-mode.is_enabled"], 0) === 1,
        isOfflineModeTelemetryEnabled:
          utils.toInt(config["persist.tesla-android.offline-mode.telemetry.is_enabled"], 0) === 1,
        isOfflineModeTeslaFirmwareDownloadsEnabled:
          utils.toInt(
            config["persist.tesla-android.offline-mode.tesla-firmware-downloads"],
            0,
          ) === 1,
      };
    }

    _bandByKey(key) {
      for (let index = 0; index < constants.SOFT_AP_BANDS.length; index += 1) {
        if (constants.SOFT_AP_BANDS[index].key === key) {
          return constants.SOFT_AP_BANDS[index];
        }
      }
      return null;
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

    // ------------------------------------------------------------------ the old sections' helpers, on the new rows

    /** the group the old append helpers write into: the current one, else a new untitled one */
    _body() {
      const container = this._target || this.content;
      if (!this._curBody || !this._curBody.isConnected || this._curBody.closest(".group").parentNode !== container) {
        const g = UI.group(null, []);
        container.appendChild(g);
        this._curBody = g.querySelector(".group-body");
      }
      return this._curBody;
    }

    _appendTile(options) {
      const control = options.trailing || null;
      const stack = Boolean(control && control.classList && (control.classList.contains("seg") || control.classList.contains("slider")));
      const r = UI.row({ title: options.title || "", detail: options.subtitle || "", control, stack });
      // the old sections update their tile's subtitle in place
      let detail = r.querySelector(".row-detail");
      if (!detail) {
        detail = h("div", { class: "row-detail" });
        detail.hidden = !options.subtitle;
        r.querySelector(".row-main").append(detail);
      }
      detail.classList.add("settings-tile-subtitle");
      this._body().appendChild(r);
      return r;
    }

    _appendDescription(text) {
      this._body().appendChild(h("p", { class: "group-note" }, text));
    }

    _appendDivider() {
      this._curBody = null;
    }

    _appendHeading(text) {
      const g = UI.group(text, []);
      (this._target || this.content).appendChild(g);
      this._curBody = g.querySelector(".group-body");
    }

    _appendLoadingState() {
      this.content.replaceChildren(h("div", { class: "loading" }, "Loading…"));
      this._curBody = null;
    }

    /** an error above what the section shows (it no longer wipes the section) */
    _renderErrorState(message) {
      if (this.content.querySelector(":scope > .loading")) this.content.replaceChildren();
      this.content.querySelectorAll(":scope > .error-note").forEach((e) => e.remove());
      this.content.prepend(h("div", { class: "error-note", role: "alert" }, icon("error_outline"), h("span", null, message)));
    }

    /** a choice: up to six short ones in a segmented control, more in a picker sheet */
    _createSelect(options, value, onChange) {
      const after = async (v) => {
        await onChange(String(v));
        if (this.activeSectionId) await this._renderActiveSection(true);
      };
      if (options.length <= 6) {
        return UI.segmented(options.map((o) => ({ value: o.value, label: String(o.label) })), value, after);
      }
      const chosen = options.find((o) => String(o.value) === String(value)) || options[0];
      return UI.tapValue(chosen ? String(chosen.label) : "", async () => {
        const v = await UI.pickFrom({ title: "Choose", value, options: options.map((o) => ({ value: o.value, label: String(o.label) })) });
        if (v != null && String(v) !== String(value)) await after(v);
      });
    }

    _createSwitch(value, onChange) {
      return UI.toggle(value, async (checked) => {
        await onChange(Boolean(checked));
      });
    }

    _createTrailingText(text) {
      return UI.value(text);
    }
  }

  TAHtml.SettingsController = SettingsController;
})(window);
