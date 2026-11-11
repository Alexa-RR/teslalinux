(function (global) {
  "use strict";

  const TAHtml = global.TAHtml;
  const utils = TAHtml.utils;
  const constants = TAHtml.constants;

  class SettingsController {
    constructor(options) {
      this.apiClient = options.apiClient;
      this.displayController = options.displayController;
      this.gpsController = options.gpsController;
      this.audioController = options.audioController;
      this.flavor = options.flavor;
      this.onSystemConfigurationChanged = options.onSystemConfigurationChanged;

      this.sidebar = options.sidebar;
      this.content = options.content;

      this.sections = [
        // teslalinux: what the device is connected to right now (GET /api/status)
        { id: "status", title: "Status", icon: "monitor_heart" },
        { id: "display", title: "Display", icon: "monitor" },
        // teslalinux: hotspot and LTE (GET/POST /api/network); upstream's Rear Display is gone (the
        // app is a navigation app shown on the main screen)
        { id: "network", title: "Wi-Fi & LTE", icon: "wifi" },
        { id: "audio", title: "Audio", icon: "speaker" },
        { id: "gps", title: "GPS", icon: "gps_fixed" },
        // teslalinux: the device's language/region and time zone (GET/POST /api/locale)
        { id: "locale", title: "Language & region", icon: "language" },
        // teslalinux: the app's built-in keyboard or this device's own (GET/POST /api/keyboard)
        { id: "keyboard", title: "Keyboard", icon: "keyboard" },
        // teslalinux: automatic Waze updates from Google Play (GET/POST /api/appUpdate*)
        { id: "updates", title: "App updates", icon: "system_update" },
        // teslalinux: the operating system's own updates (GET/POST /api/systemUpdate)
        { id: "system", title: "System update", icon: "system_security_update" },
        { id: "device", title: "Device", icon: "developer_board" },
      ];

      this.activeSectionId = "status";
      this._pollTimer = null;
      this._renderToken = 0;
      this._activeDropdown = null;

      this.systemConfigCache = null;
      this._bindGlobalEvents();
    }

    async initialize() {
      this._renderSidebar();
      await this.setActiveSection(this.activeSectionId);
    }

    async setActiveSection(sectionId) {
      this.activeSectionId = sectionId;
      this._renderSidebar();
      await this._renderActiveSection();
    }

    async _renderActiveSection() {
      const token = ++this._renderToken;
      this._setActiveDropdown(null);
      if (this._pollTimer) {
        global.clearTimeout(this._pollTimer);
        this._pollTimer = null;
      }
      this.content.innerHTML = "";

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
      if (this.activeSectionId === "audio") {
        await this._renderAudioSection(token);
        return;
      }
      if (this.activeSectionId === "gps") {
        await this._renderGpsSection(token);
        return;
      }
      if (this.activeSectionId === "locale") {
        await this._renderLocaleSection(token);
        return;
      }
      if (this.activeSectionId === "keyboard") {
        await this._renderKeyboardSection(token);
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

    // teslalinux: language/region and time zone of the device, handed to the app's runtime the way
    // Android hands it persist.sys.locale / persist.sys.timezone; a change restarts the app once.
    async _renderLocaleSection(token) {
      this._appendLoadingState();
      let state;
      try {
        state = await this.apiClient.fetchLocale();
      } catch (_error) {
        this._renderErrorState("Service error");
        return;
      }
      if (token !== this._renderToken || this.activeSectionId !== "locale") {
        return;
      }
      this.content.innerHTML = "";

      const localeLabel = (code) => {
        const tag = String(code).replace("_", "-");
        try {
          const lang = new Intl.DisplayNames([tag, "en"], { type: "language" });
          const name = lang.of(tag);
          return name && name !== tag ? name + " (" + code + ")" : code;
        } catch (_error) {
          return code;
        }
      };
      const offsetLabel = (zone) => {
        try {
          const parts = new Intl.DateTimeFormat("en-US", { timeZone: zone, timeZoneName: "shortOffset" }).formatToParts(new Date());
          const off = parts.find((p) => p.type === "timeZoneName");
          return zone.replace(/_/g, " ") + (off ? " (" + off.value + ")" : "");
        } catch (_error) {
          return zone.replace(/_/g, " ");
        }
      };
      const locales = (state.locales || []).map((code) => ({ value: code, label: localeLabel(code) }));
      const zones = (state.timezones || []).map((zone) => ({ value: zone, label: offsetLabel(zone) }));

      const apply = async (locale, timezone) => {
        if (!locale || !timezone) {
          return;
        }
        try {
          await this.apiClient.setLocale(locale, timezone);
          this._showLocaleNotice("Saved. Waze restarts with the new setting (about a minute).");
        } catch (_error) {
          this._renderErrorState("Service error");
        }
      };

      this._appendTile({
        icon: "language",
        title: "Language and region",
        subtitle: "Units, date and time formats, and the app's language where it has one",
        trailing: this._createSelect(locales, state.locale, async (value) => {
          state.locale = String(value);
          await apply(state.locale, state.timezone);
        }),
        dense: false,
      });
      this._appendTile({
        icon: "schedule",
        title: "Time zone",
        subtitle: "Arrival times and the clock",
        trailing: this._createSelect(zones, state.timezone, async (value) => {
          state.timezone = String(value);
          await apply(state.locale, state.timezone);
        }),
        dense: false,
      });
      this._localeNotice = utils.createElement("div", "settings-description", "");
      this._localeNotice.hidden = true;
      this.content.appendChild(this._localeNotice);
      this._appendDescription(
        "Android apps take these from the system settings. A change restarts Waze once; the display and this page stay connected.",
      );
    }

    // teslalinux: type with this device's keyboard (the car's, a phone's) instead of the app's own
    // on-screen keyboard. js/keyboard/device-keyboard.js does the typing; the app restarts once.
    async _renderKeyboardSection(token) {
      this._appendLoadingState();
      let state;
      try {
        state = await this.apiClient.fetchKeyboard();
      } catch (_error) {
        this._renderErrorState("Service error");
        return;
      }
      if (token !== this._renderToken || this.activeSectionId !== "keyboard") {
        return;
      }
      this.content.innerHTML = "";
      const notice = utils.createElement("div", "settings-description", "");
      notice.hidden = true;
      this._appendTile({
        icon: "keyboard",
        title: "Use this device's keyboard",
        subtitle: "Type with the car's (or phone's) own keyboard instead of the app's built-in one",
        trailing: this._createSwitch(state.mode === "device", async (checked) => {
          const mode = checked ? "device" : "builtin";
          try {
            await this.apiClient.setKeyboard(mode);
            global.dispatchEvent(new CustomEvent("teslalinux-keyboard-mode", { detail: mode }));
            notice.textContent = "Saved. Waze restarts with the new keyboard (about a minute).";
            notice.hidden = false;
          } catch (_error) {
            this._renderErrorState("Service error");
          }
        }),
        dense: false,
      });
      this.content.appendChild(notice);
      this._appendDescription(
        "When a text field in the app is tapped, this page opens your device's keyboard and sends what you type to the app. Some browsers (Safari on iPhone) open the keyboard only from a tap: tap the keyboard button that appears in the corner.",
      );
    }

    _showLocaleNotice(text) {
      if (this._localeNotice) {
        this._localeNotice.textContent = text;
        this._localeNotice.hidden = false;
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
      if (busy) {
        this.content.lastElementChild.querySelector(".settings-tile-icon").classList.add("settings-spin");
      }
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

    _appendHeading(text) {
      (this._target || this.content).appendChild(utils.createElement("h3", "settings-subheading", text));
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

    async _renderAudioSection(token) {
      this._appendLoadingState();

      const systemConfiguration = await this._fetchSystemConfigurationSafe();
      if (!systemConfiguration) {
        this._renderErrorState("Service error");
        return;
      }

      if (token !== this._renderToken || this.activeSectionId !== "audio") {
        return;
      }

      const isEnabled =
        utils.toInt(systemConfiguration["persist.tesla-android.browser_audio.is_enabled"], 1) === 1;
      const volume = utils.toInt(
        systemConfiguration["persist.tesla-android.browser_audio.volume"],
        100,
      );

      this.content.innerHTML = "";

      this._appendTile({
        icon: "speaker",
        title: "Browser audio",
        subtitle: "Disable if you intend to use Bluetooth audio",
        trailing: this._createSwitch(isEnabled, async (checked) => {
          try {
            await this.apiClient.setBrowserAudioState(checked ? 1 : 0);
            await this.apiClient.setBrowserAudioVolume(100);
            await this._handleSystemConfigurationChanged();
            await this._renderActiveSection();
          } catch (_error) {
            this._renderErrorState("Service error");
          }
        }),
      });
      this._appendDivider();

      const sliderWrap = utils.createElement("div", "settings-volume-wrap", null);
      const sliderValue = utils.createElement("span", "settings-volume-value", String(volume) + " %");
      const slider = utils.createElement("input", "settings-volume-slider", null);
      slider.type = "range";
      slider.min = "0";
      slider.max = "150";
      slider.step = "10";
      slider.value = String(volume);
      slider.addEventListener("input", () => {
        sliderValue.textContent = String(slider.value) + " %";
      });
      slider.addEventListener("change", async () => {
        try {
          await this.apiClient.setBrowserAudioState(1);
          await this.apiClient.setBrowserAudioVolume(utils.toInt(slider.value, volume));
          await this._handleSystemConfigurationChanged();
          await this._renderActiveSection();
        } catch (_error) {
          this._renderErrorState("Service error");
        }
      });
      sliderWrap.appendChild(sliderValue);
      sliderWrap.appendChild(slider);

      this._appendTile({
        icon: "volume_down",
        title: "Volume",
        trailing: sliderWrap,
        dense: false,
      });

      this._appendDescription(
        "If you plan to use browser audio continuously in conjunction with video playback, it's essential to note that it can be bandwidth-intensive. To optimize your experience, you may want to consider pairing your car with the Teslalinux device over Bluetooth, particularly if your Tesla is equipped with MCU2.",
      );
      this._appendDescription(
        "In case you encounter a situation where the browser in your Tesla fails to produce sound, a simple reboot of the vehicle should resolve the issue. Please note that this is a known issue with the browser itself.",
      );
    }

    async _renderGpsSection(token) {
      this._appendLoadingState();

      const systemConfiguration = await this._fetchSystemConfigurationSafe();
      if (!systemConfiguration) {
        this._renderErrorState("Service error");
        return;
      }

      if (token !== this._renderToken || this.activeSectionId !== "gps") {
        return;
      }

      const isEnabled =
        utils.toInt(systemConfiguration["persist.tesla-android.gps.is_active"], 1) === 1;

      this.content.innerHTML = "";

      this._appendTile({
        icon: "gps_fixed",
        title: "GPS",
        subtitle: "The car's position for the app",
        trailing: this._createSwitch(isEnabled, async (checked) => {
          try {
            await this.apiClient.setGpsState(checked ? 1 : 0);
            await this._handleSystemConfigurationChanged();
            this.gpsController.setEnabled(checked);
            await this._renderActiveSection();
          } catch (_error) {
            this._renderErrorState("Service error");
          }
        }),
      });

      this._appendDescription(
        "NOTE: GPS via Browser can cause crashes on Tesla Software 2024.14 or newer, the integration is disabled by default until this issue is solved by Tesla. Please be assured that your location data never leaves your car. The car's position goes only to your Teslalinux device, where it stands in for a GPS receiver for the app.",
      );
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
      this._appendTile({
        icon: "refresh",
        title: "Restart Waze",
        subtitle: describe(appState.state),
        trailing: this._createActionButton("Restart"),
        dense: false,
      });
      const tile = this.content.lastElementChild;
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
        this._appendTile({
          icon: "sync",
          title: "Check for an update now",
          trailing: this._createActionButton("Check"),
          dense: false,
        });
        const checkTile = this.content.lastElementChild;
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
      const button = utils.createElement("button", "settings-action-button", text);
      button.type = "button";
      return button;
    }

    _bindGlobalEvents() {
      if (!global.document || typeof global.document.addEventListener !== "function") {
        return;
      }

      global.document.addEventListener("pointerdown", (event) => {
        this._handleDocumentPointerDown(event);
      });
      global.document.addEventListener("keydown", (event) => {
        this._handleDocumentKeyDown(event);
      });
    }

    _handleDocumentPointerDown(event) {
      if (!this._activeDropdown) {
        return;
      }
      if (this._activeDropdown.contains(event.target)) {
        return;
      }
      // teslalinux: the open menu lives in <body> (see _openDropdownMenu)
      if (this._activeMenu && this._activeMenu.contains(event.target)) {
        return;
      }
      this._setActiveDropdown(null);
    }

    // teslalinux: an open dropdown menu is shown above everything, positioned from its trigger, so a
    // scrolling settings panel (overflow: auto) cannot cut it off. It moves to <body> while open,
    // opens downwards or upwards where there is more room, and closes on scroll or resize.
    _openDropdownMenu(dropdown) {
      const menu = dropdown.querySelector(".settings-dropdown-menu");
      const trigger = dropdown.querySelector(".settings-dropdown-trigger");
      if (!menu || !trigger) {
        return;
      }
      const rect = trigger.getBoundingClientRect();
      const margin = 8;
      const below = global.innerHeight - rect.bottom - margin * 2;
      const above = rect.top - margin * 2;
      const openUp = below < 200 && above > below;
      global.document.body.appendChild(menu);
      menu.classList.add("settings-dropdown-menu-floating");
      menu.style.minWidth = Math.max(rect.width, 160) + "px";
      menu.style.right = Math.max(margin, global.innerWidth - rect.right) + "px";
      menu.style.maxHeight = Math.max(120, Math.min(openUp ? above : below, 420)) + "px";
      if (openUp) {
        menu.style.top = "";
        menu.style.bottom = global.innerHeight - rect.top + margin + "px";
      } else {
        menu.style.bottom = "";
        menu.style.top = rect.bottom + margin + "px";
      }
      const active = menu.querySelector(".settings-dropdown-option.active");
      if (active) {
        // the chosen entry in view, scrolling only the menu (scrollIntoView could scroll the page too)
        menu.scrollTop = Math.max(0, active.offsetTop - (menu.clientHeight - active.offsetHeight) / 2);
      }
      this._activeMenu = menu;
      this._activeMenuHome = dropdown;
      if (!this._closeOnViewportChange) {
        this._closeOnViewportChange = (event) => {
          // scrolling inside the open menu itself is fine
          if (event && event.type === "scroll" && this._activeMenu && this._activeMenu.contains(event.target)) {
            return;
          }
          this._setActiveDropdown(null);
        };
      }
      global.addEventListener("resize", this._closeOnViewportChange);
      global.document.addEventListener("scroll", this._closeOnViewportChange, true);
    }

    _closeDropdownMenu() {
      const menu = this._activeMenu;
      const home = this._activeMenuHome;
      this._activeMenu = null;
      this._activeMenuHome = null;
      if (this._closeOnViewportChange) {
        global.removeEventListener("resize", this._closeOnViewportChange);
        global.document.removeEventListener("scroll", this._closeOnViewportChange, true);
      }
      if (!menu) {
        return;
      }
      menu.classList.remove("settings-dropdown-menu-floating");
      menu.style.top = menu.style.bottom = menu.style.right = menu.style.maxHeight = menu.style.minWidth = "";
      if (home) {
        home.appendChild(menu);
      } else if (menu.parentNode) {
        menu.parentNode.removeChild(menu);
      }
    }

    _handleDocumentKeyDown(event) {
      if (!this._activeDropdown) {
        return;
      }
      if (event.key === "Escape") {
        this._setActiveDropdown(null);
      }
    }

    _setActiveDropdown(dropdown) {
      if (this._activeDropdown && this._activeDropdown !== dropdown) {
        this._closeDropdownMenu();
        this._activeDropdown.classList.remove("open");
        const previousTrigger = this._activeDropdown.querySelector(".settings-dropdown-trigger");
        if (previousTrigger) {
          previousTrigger.setAttribute("aria-expanded", "false");
        }
      }

      if (!dropdown) {
        this._activeDropdown = null;
        return;
      }

      if (this._activeDropdown === dropdown) {
        return;
      }

      dropdown.classList.add("open");
      const trigger = dropdown.querySelector(".settings-dropdown-trigger");
      if (trigger) {
        trigger.setAttribute("aria-expanded", "true");
      }
      this._activeDropdown = dropdown;
      this._openDropdownMenu(dropdown);
    }

    _renderSidebar() {
      this.sidebar.innerHTML = "";

      for (let index = 0; index < this.sections.length; index += 1) {
        const section = this.sections[index];
        const button = utils.createElement("button", "settings-menu-item", null);
        button.type = "button";
        if (section.id === this.activeSectionId) {
          button.classList.add("active");
        }

        const icon = utils.createElement(
          "span",
          "material-symbols-rounded settings-menu-icon",
          this._iconName(section.icon),
        );
        const label = utils.createElement("span", "settings-menu-label", section.title);

        button.appendChild(icon);
        button.appendChild(label);
        button.addEventListener("click", async () => {
          await this.setActiveSection(section.id);
        });
        this.sidebar.appendChild(button);
      }
    }

    async _updateDisplayConfig(mutator, showRebootBanner) {
      const shouldShowRebootBanner = showRebootBanner !== false;

      try {
        const config = this._normalizeDisplayState(await this.apiClient.fetchDisplayState());
        const draft = { ...config };
        mutator(draft);
        await this.apiClient.postDisplayState(draft);
        if (shouldShowRebootBanner) {
          global.alert(
            "Display configuration has been updated. Please restart the device in case of any issues.",
          );
        }
        this.displayController.triggerResizeFromViewport();
      } catch (_error) {
        this._renderErrorState("Service error");
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

    _appendTile(options) {
      const dense = options.dense !== false;
      const tile = utils.createElement(
        "div",
        dense ? "settings-tile settings-tile-dense" : "settings-tile settings-tile-wide",
        null,
      );

      const leading = utils.createElement("div", "settings-tile-leading", null);
      const leadingIcon = utils.createElement(
        "span",
        "material-symbols-rounded settings-tile-icon",
        this._iconName(options.icon || ""),
      );
      leading.appendChild(leadingIcon);
      const content = utils.createElement("div", "settings-tile-content", null);
      const title = utils.createElement("div", "settings-tile-title", options.title || "");
      content.appendChild(title);
      if (options.subtitle) {
        content.appendChild(utils.createElement("div", "settings-tile-subtitle", options.subtitle));
      }

      const trailing = utils.createElement("div", "settings-tile-trailing", null);
      if (options.trailing) {
        trailing.appendChild(options.trailing);
      }

      tile.appendChild(leading);
      tile.appendChild(content);
      tile.appendChild(trailing);
      (this._target || this.content).appendChild(tile);
    }

    _appendDescription(text) {
      const description = utils.createElement("p", "settings-description", text);
      (this._target || this.content).appendChild(description);
    }

    _appendDivider() {
      (this._target || this.content).appendChild(utils.createElement("hr", "settings-divider", null));
    }

    _appendLoadingState() {
      this.content.innerHTML = "";
      this.content.appendChild(utils.createElement("div", "settings-loading", "Loading..."));
    }

    _renderErrorState(message) {
      this.content.innerHTML = "";
      this.content.appendChild(utils.createElement("div", "settings-error", message));
    }

    _createSelect(options, value, onChange) {
      const selectedOption = this._findOptionByValue(options, value);

      const root = utils.createElement("div", "settings-dropdown", null);
      const trigger = utils.createElement("button", "settings-dropdown-trigger", null);
      trigger.type = "button";
      trigger.setAttribute("aria-haspopup", "listbox");
      trigger.setAttribute("aria-expanded", "false");

      const valueLabel = utils.createElement(
        "span",
        "settings-dropdown-value",
        selectedOption ? selectedOption.label : "",
      );
      const arrow = utils.createElement(
        "span",
        "material-symbols-rounded settings-dropdown-arrow",
        this._iconName("arrow_drop_down"),
      );
      trigger.appendChild(valueLabel);
      trigger.appendChild(arrow);

      const menu = utils.createElement("div", "settings-dropdown-menu", null);
      menu.setAttribute("role", "listbox");

      for (let index = 0; index < options.length; index += 1) {
        const option = options[index];
        const item = utils.createElement(
          "button",
          "settings-dropdown-option",
          String(option.label),
        );
        item.type = "button";
        item.dataset.value = String(option.value);
        if (String(option.value) === String(value)) {
          item.classList.add("active");
        }

        item.addEventListener("click", async () => {
          if (trigger.disabled) {
            return;
          }
          this._setActiveDropdown(null);
          root.classList.add("is-busy");
          trigger.disabled = true;
          try {
            await onChange(item.dataset.value || "");
            if (this.activeSectionId) {
              await this._renderActiveSection();
            }
          } finally {
            trigger.disabled = false;
            root.classList.remove("is-busy");
          }
        });

        menu.appendChild(item);
      }

      trigger.addEventListener("click", () => {
        if (this._activeDropdown === root) {
          this._setActiveDropdown(null);
          return;
        }
        this._setActiveDropdown(root);
      });

      root.appendChild(trigger);
      root.appendChild(menu);
      return root;
    }

    _createSwitch(value, onChange) {
      const label = utils.createElement("label", "settings-switch", null);
      const input = document.createElement("input");
      input.type = "checkbox";
      input.checked = Boolean(value);
      const slider = utils.createElement("span", "settings-switch-slider", null);

      input.addEventListener("change", async () => {
        input.disabled = true;
        try {
          await onChange(Boolean(input.checked));
        } finally {
          input.disabled = false;
        }
      });

      label.appendChild(input);
      label.appendChild(slider);
      return label;
    }

    _createTrailingText(text) {
      return utils.createElement("span", "settings-trailing-text", text);
    }

    _findOptionByValue(options, value) {
      for (let index = 0; index < options.length; index += 1) {
        if (String(options[index].value) === String(value)) {
          return options[index];
        }
      }
      return options.length > 0 ? options[0] : null;
    }

    _iconName(iconName) {
      const source = String(iconName || "");
      switch (source) {
        case "monitor":
          return "desktop_windows";
        case "speaker":
          return "volume_up";
        case "developer_board":
          return "memory";
        case "display_settings":
          return "settings_overscan";
        case "photo_size_select_actual_outlined":
          return "photo_size_select_actual";
        case "photo_size_select_large":
          return "aspect_ratio";
        case "screenshot_monitor":
          return "desktop_windows";
        case "wifi_channel":
          return "wifi";
        case "data_thresholding_sharp":
          return "data_usage";
        case "device_thermostat":
          return "thermostat";
        case "developer_board_rounded":
          return "developer_board";
        case "broadcast_on_home_sharp":
          return "phonelink";
        case "cell_tower":
          return "network_cell";
        // teslalinux: names of the newer Material Symbols set, drawn with the bundled Material Icons font
        case "monitor_heart":
          return "dashboard";
        case "wifi_find":
          return "wifi_tethering";
        case "lan":
          return "settings_ethernet";
        case "memory_alt":
          return "sd_storage";
        case "data_thresholding":
          return "data_usage";
        case "key":
          return "vpn_key";
        case "terminal":
          return "computer";
        default:
          return source;
      }
    }
  }

  TAHtml.SettingsController = SettingsController;
})(window);
