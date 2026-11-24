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
  // the day/night choice, which goes to the car-app host on every connection, the map size and
  // the map colours).
  const LOOK_STYLE_KEY = "teslalinux.carInterfaceStyle";
  const LOOK_DAY_NIGHT_KEY = "teslalinux.carDayNight";
  const LOOK_MAP_KEY = "teslalinux.carMapColours";
  const STYLES = ["rounded", "graphite"];
  const DAY_NIGHT = ["auto", "day", "night"];
  // The map's colours are the device's choice (/api/appInterface "mapColours"), since "graphite"
  // changes what the app draws: ATL serves the app its map palette recoloured, for verified app
  // versions only (docs/MAP-COLOURS.md). This browser's earlier "muted"/"app" choice is used until
  // one is made on the device.
  const MAP_COLOURS = ["graphite", "muted", "app"];
  const PAGE_MAP_COLOURS = ["muted", "app"];
  // The size of the app's map text and symbols: a scale on the display's density that the car-app
  // host gives the app as the car display's density (the panels drawn here keep theirs). Kept by
  // this browser and sent to the host while no page has chosen one since the app started; once
  // one has, every page follows the host's ("mapScale" state), so all of them agree.
  const LOOK_MAP_SIZE_KEY = "teslalinux.carMapSize";
  const MAP_SIZE_SCALES = { smaller: 0.85, default: 1, larger: 1.25, largest: 1.5 };
  const MAP_SIZES = Object.keys(MAP_SIZE_SCALES);
  // The size of the panels drawn here (cards, lists, buttons): a scale on the density they are
  // laid out at; the app's map is not affected. Kept by this browser, applied at once.
  // The map's sharpness: the fraction of the display's pixels the app draws its map in, scaled up
  // to the display by the car-app host (ATLCarSurface render scale); the app lays it out the same,
  // so a lower one only looks softer, and moves more smoothly (the Pi's GPU draws fewer pixels).
  // Kept by this browser and sent to the host when connected and when it changes.
  const LOOK_MAP_SHARPNESS_KEY = "teslalinux.carMapSharpness";
  const MAP_SHARPNESS_SCALES = { full: 1, high: 0.9, medium: 0.8, low: 0.7 };
  const MAP_SHARPNESSES = Object.keys(MAP_SHARPNESS_SCALES);
  const LOOK_PANEL_SIZE_KEY = "teslalinux.carPanelSize";
  const PANEL_SIZE_SCALES = { smallest: 0.7, smaller: 0.85, default: 1, larger: 1.15 };
  const PANEL_SIZES = Object.keys(PANEL_SIZE_SCALES);
  // The speedometer this page draws over the map (carapp-speedometer.js), where the device sends
  // the app's readings (/api/appInterface "speedometerSupport"): "both" (speed and the road's
  // limit, the default), "limit" (the limit only) or "off". Kept by this browser, applied at once;
  // the app's own speedometer stays off on the device in all three.
  const LOOK_SPEEDOMETER_KEY = "teslalinux.carSpeedometer";
  const SPEEDOMETERS = ["both", "limit", "off"];
  // where it stands: "bottomRight" (where the app drew its own, the default) or "topLeft" (under
  // the directions). Kept by this browser, applied at once.
  const LOOK_SPEEDOMETER_POSITION_KEY = "teslalinux.carSpeedometerPosition";
  const SPEEDOMETER_POSITIONS = ["bottomRight", "topLeft"];

  // The app's own settings in the settings page (Settings > Waze > Waze's settings,
  // carapp-settings-view.js): the app's settings action is found by its icon's resource name (the
  // host's "resName", the same in every app version and language), tapped like any action, and
  // the screens the app pushes for it are drawn in the settings page instead of over the map. The
  // app's screen stack (TemplateWrapper.templateInfosForScreenStack, top first) tells them apart:
  // every template whose stack lies above the one the settings were opened from is one of the
  // settings' screens. Closing pops them with the host's back (AppManager.onBackPressed, which the
  // app's ScreenManager answers with pop), one at a time, until the stack is the opening one.
  const APP_SETTINGS_ICON = /(^|_)settings?(_|$)/i;
  const APP_SETTINGS_OPEN_MS = 5000; // the app's settings screen arrives within this, or it did not open
  const APP_SETTINGS_STEP_MS = 3000; // one back answered with a template within this
  const APP_SETTINGS_MAX_BACKS = 12;

  /** the screen ids of a TemplateWrapper's stack, top first ([] when the app sends none) */
  function stackIds(wrapper) {
    const infos = (wrapper && wrapper.templateInfosForScreenStack) || [];
    return infos.map((i) => i && i.templateId).filter(Boolean);
  }

  /** whether the bottom of the stack ids is exactly base (both top first) */
  function endsWith(ids, base) {
    if (ids.length < base.length) return false;
    const tail = ids.slice(ids.length - base.length);
    for (let i = 0; i < base.length; i++) if (tail[i] !== base[i]) return false;
    return true;
  }

  /**
   * How many screens the stack has above the base stack (the one under the app's settings
   * screen), or 0 when it is the base or not above it. The base's top screen keeps its id while
   * screens lie above it (the library asks only the top screen for templates, and a screen popped
   * back to reuses its last id), so the whole base is compared.
   */
  function depthAbove(ids, base) {
    if (!base.length || ids.length <= base.length || !endsWith(ids, base)) return 0;
    return ids.length - base.length;
  }

  /** whether the host names the template's resource icons (a host from before "resName" does not) */
  function hasIconNames(o) {
    if (!o || typeof o !== "object") return false;
    if (o.$ === "IconCompat" && o.resName) return true;
    for (const k in o) if (o[k] && typeof o[k] === "object" && hasIconNames(o[k])) return true;
    return false;
  }

  /** the app's settings action in a template: an action with a click whose icon is the app's settings drawable */
  function findSettingsAction(o) {
    if (!o || typeof o !== "object") return null;
    if (Array.isArray(o)) {
      for (const x of o) {
        const a = findSettingsAction(x);
        if (a) return a;
      }
      return null;
    }
    if (o.$ === "Action" && o.onClickDelegate && o.enabled !== false) {
      const ic = o.icon && o.icon.icon;
      if (ic && ic.resName && APP_SETTINGS_ICON.test(ic.resName)) return o;
    }
    for (const k in o) {
      const v = o[k];
      if (v && typeof v === "object") {
        const a = findSettingsAction(v);
        if (a) return a;
      }
    }
    return null;
  }

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
      this.mapColours = readPref(LOOK_MAP_KEY, PAGE_MAP_COLOURS, "muted");
      this.mapSize = readPref(LOOK_MAP_SIZE_KEY, MAP_SIZES, "default");
      this.panelSize = readPref(LOOK_PANEL_SIZE_KEY, PANEL_SIZES, "default");
      this.mapSharpness = readPref(LOOK_MAP_SHARPNESS_KEY, MAP_SHARPNESSES, "full");
      // whether the graphite map palette applies to the installed app (/api/appInterface "mapSkin")
      this.mapSkin = null;
      this.speedometer = readPref(LOOK_SPEEDOMETER_KEY, SPEEDOMETERS, "both");
      this.speedometerPosition = readPref(LOOK_SPEEDOMETER_POSITION_KEY, SPEEDOMETER_POSITIONS, "bottomRight");
      // whether the device sends the app's speedometer, and the app's sign style and
      // over-the-limit offset (/api/appInterface "speedometerSupport")
      this.speedometerSupport = null;
      this.speedReading = null; // the latest {"type":"speedometer"} from the host
      this.mapArea = null; // the map area the panels leave free (sendVisibleArea), surface pixels
      this.hostNight = null; // the car's night mode as the host reports it (null: not reported)
      // the app's own settings while the settings page shows them (openAppSettings), else null
      this.appSettings = null;
      // the settings page's listener while it waits for the app to offer its settings action again
      this.appSettingsWaiting = null;
    }

    // ---- the app's own settings, drawn by the settings page (carapp-settings-view.js) ----------

    /**
     * Opens the app's own settings for the settings page, or takes over the ones already open:
     * listener(state) hears {state, template, depth, busy, reason} now and on every change:
     * "opening" (the settings action tapped, its screen not here yet), "open" (template: the
     * app's screen on top, depth: how many screens above the map, 1 for the settings screen
     * itself; busy while a back is on its way), "closed" (the app closed its settings itself) or
     * "unavailable" (reason: "offline", "noAction" when the current screen has no settings
     * action, e.g. while the app shows a dialog, "noNames" when the host is too old to name the
     * icons, "noAnswer").
     */
    openAppSettings(listener) {
      const s = this.appSettings;
      if (s && (s.state === "open" || s.state === "opening")) {
        // the page again (a section drawn again, or back before the screens were popped)
        if (s.target === 0) s.target = null;
        s.listener = listener;
        this.tellAppSettings();
        return;
      }
      if (s) this.endAppSettings();
      const wrapper = this.template;
      const action = wrapper ? findSettingsAction(wrapper.template || wrapper) : null;
      if (!this.socket || this.socket.readyState !== WebSocket.OPEN || !wrapper) {
        listener({ state: "unavailable", reason: "offline", depth: 0 });
        return;
      }
      if (!action) {
        // "noNames": the car-app host does not name icons yet (an older runtime). "navigating": Waze
        // offers its settings action only on its map while it does not navigate (Waze 5.27.5: during
        // a drive its map has search, routes and sound, and none of their screens leads to the
        // settings). "noAction": something else is on its map (a question, a list, a search).
        // Either way they open by themselves as soon as Waze offers the action again, while the
        // settings page still asks (closeAppSettings stops waiting).
        const reason = !hasIconNames(wrapper) ? "noNames" : this.navigating ? "navigating" : "noAction";
        this.appSettingsWaiting = reason === "noNames" ? null : listener;
        listener({ state: "unavailable", reason, depth: 0 });
        return;
      }
      this.appSettingsWaiting = null;
      const t = {
        listener: listener,
        base: stackIds(wrapper), // the map's stack; corrected by the settings screen's own
        state: "opening",
        template: null,
        first: null, // the latest template of the settings screen itself (depth 1)
        depth: 0,
        target: null, // the depth backs are popping to (0: closing), or null
        pending: false, // a back sent, its template not here yet
        timer: null,
        reason: null,
      };
      this.appSettings = t;
      t.timer = setTimeout(() => {
        if (this.appSettings !== t || t.state !== "opening") return;
        if (t.target === 0) {
          this.endAppSettings();
          return;
        }
        // kept: the screen arriving late still counts as the settings
        t.state = "unavailable";
        t.reason = "noAnswer";
        this.tellAppSettings();
      }, APP_SETTINGS_OPEN_MS);
      // the host first: if this page goes away while they are open (closed, out of the car's
      // Wi-Fi), the host pops them back to the map itself (ATLCarHost, docs/UPSTREAM-NOTES.md)
      this.send({ type: "appSettings", state: "open" });
      this.send({ type: "click", cb: action.onClickDelegate.$cb });
      this.tellAppSettings();
    }

    /**
     * The settings page's way to Waze's settings during a drive: the drive ends (the end-drive
     * control's message), Waze goes back to its map, which offers the settings action, and the
     * waiting settings page opens them (the template handler above).
     */
    endDriveForAppSettings() {
      if (!this.navigating) return;
      this.send({ type: "stopNavigation" });
    }

    /**
     * This page is going away for good (pagehide) with the app's settings open: no back can be
     * waited for any more, so the host pops them (the same happens when the socket just closes).
     */
    abandonAppSettings() {
      const s = this.appSettings;
      if (!s) return;
      clearTimeout(s.timer);
      this.appSettings = null;
      this.send({ type: "appSettings", state: "abandon" });
    }

    /** The settings page goes back one screen of the app's settings (a sub-screen's back). */
    appSettingsBack() {
      const s = this.appSettings;
      if (!s || s.state !== "open" || s.depth <= 1) return;
      this.popAppSettings(s.depth - 1);
    }

    /** Back to the app's settings screen itself (a sub-screen closed). */
    appSettingsToTop() {
      const s = this.appSettings;
      if (s && s.state === "open" && s.depth > 1) this.popAppSettings(1);
    }

    /**
     * The settings page no longer shows the app's settings (another section, the map): their
     * screens are popped until the map is the app's screen again, and from then on templates go
     * over the map as before.
     */
    closeAppSettings() {
      this.appSettingsWaiting = null;
      const s = this.appSettings;
      if (!s) return;
      s.listener = null;
      if (s.state === "opening") {
        // the settings screen is on its way: popped when it arrives (or forgotten at the timeout)
        s.target = 0;
        return;
      }
      if (!s.depth) {
        this.endAppSettings();
        return;
      }
      this.popAppSettings(0);
    }

    popAppSettings(depth) {
      const s = this.appSettings;
      s.target = depth;
      this.tellAppSettings();
      this.pumpAppSettingsBack();
    }

    /** One back at a time, each sent after the template answering the one before. */
    pumpAppSettingsBack() {
      const s = this.appSettings;
      if (!s || s.target === null || s.pending) return;
      if (s.depth <= s.target) {
        this.reachedAppSettingsTarget();
        return;
      }
      s.pending = true;
      clearTimeout(s.timer);
      s.timer = setTimeout(() => {
        // the app kept its screen (a question of its own, or no answer): the page stays where it is
        if (this.appSettings !== s) return;
        s.pending = false;
        if (s.target === 0) {
          // closing: what the app shows now goes over the map, where the driver sees it
          this.endAppSettings();
          return;
        }
        s.target = null;
        this.tellAppSettings();
      }, APP_SETTINGS_STEP_MS);
      this.send({ type: "back" });
    }

    reachedAppSettingsTarget() {
      const s = this.appSettings;
      const closing = s.target === 0;
      s.target = null;
      if (closing) this.endAppSettings();
      else this.tellAppSettings();
    }

    /** the app's settings forgotten: the latest template goes over the map */
    endAppSettings() {
      const s = this.appSettings;
      if (!s) return;
      clearTimeout(s.timer);
      this.appSettings = null;
      this.send({ type: "appSettings", state: "closed" });
      if (s.depth && s.template) this.template = s.template;
      this.draw();
    }

    tellAppSettings() {
      const s = this.appSettings;
      if (!s || !s.listener) return;
      s.listener({
        state: s.state,
        template: s.template,
        depth: s.depth,
        busy: s.target !== null,
        reason: s.reason,
        first: s.first,
      });
    }

    /**
     * A template while the app's settings are open: true when it is one of their screens (kept
     * for the settings page, not drawn over the map), false for the map's own.
     */
    routeAppSettings(wrapper) {
      const s = this.appSettings;
      const ids = stackIds(wrapper);
      if (!s.depth && s.state !== "open") {
        // waiting for the settings screen: one screen above the map's stack, whose own top may
        // have been refreshed (a new id) between the tap and the push; a refresh of the map
        // itself moves the base along
        const n = s.base.length;
        if (ids.length === n + 1 && endsWith(ids, s.base.slice(1))) {
          s.base = ids.slice(1);
        } else {
          if (ids.length === n && endsWith(ids, s.base.slice(1))) s.base = ids;
          return false;
        }
      }
      const depth = depthAbove(ids, s.base);
      const fewer = depth < s.depth;
      if (fewer || depth === 0) s.pending = false;
      if (s.pending === false) clearTimeout(s.timer);
      if (depth === 0) {
        // back at the map: popped by the page, or the app closed its settings itself
        s.depth = 0;
        s.template = null;
        s.first = null;
        if (s.target === 0) {
          this.endAppSettings();
        } else {
          s.state = "closed";
          s.target = null;
          this.tellAppSettings();
        }
        return false;
      }
      s.state = "open";
      s.reason = null;
      s.depth = depth;
      s.template = wrapper;
      if (depth === 1) s.first = wrapper;
      if (s.target !== null && depth <= s.target) this.reachedAppSettingsTarget();
      else if (s.target !== null) this.pumpAppSettingsBack();
      else this.tellAppSettings();
      return true;
    }

    /** an icon of the templates for the settings page: its URL and whether it is a single-colour glyph */
    appIcon(id) {
      return this.icons.get(id) || null;
    }

    appIconIsGlyph(id) {
      return this.mono.get(id) === true;
    }

    /** The car interface's look as chosen in the settings: {style, dayNight, mapColours, mapSkin, mapSize, panelSize, mapSharpness, speedometer, speedometerPosition, speedometerSupport}. */
    getLook() {
      return {
        style: this.lookStyle,
        dayNight: this.dayNight,
        mapColours: this.mapColours,
        mapSkin: this.mapSkin,
        mapSize: this.mapSize,
        panelSize: this.panelSize,
        mapSharpness: this.mapSharpness,
        speedometer: this.speedometer,
        speedometerPosition: this.speedometerPosition,
        speedometerSupport: this.speedometerSupport,
      };
    }

    /** the device's map colours state (/api/appInterface), from start() or the settings page */
    setDeviceMapColours(state) {
      if (!state) return;
      if (MAP_COLOURS.indexOf(state.mapColours) >= 0) this.mapColours = state.mapColours;
      if (state.mapSkin) this.mapSkin = state.mapSkin;
      if (state.speedometerSupport) {
        this.speedometerSupport = state.speedometerSupport;
        this.drawSpeedometer();
      }
      this.applyMapColours();
    }

    /**
     * The app's speedometer over the map: the latest reading, at the bottom right of the free map
     * area, or under the directions (the turn card) when chosen.
     */
    drawSpeedometer() {
      if (!this.speedo) return;
      const support = this.speedometerSupport || {};
      const topLeft = this.speedometerPosition === "topLeft";
      this.speedo.update(support.supported === false ? null : this.speedReading, {
        mode: this.speedometer,
        position: this.speedometerPosition,
        under: topLeft && this.surface.width ? TLCarSpeedometer.underDirections(this.surfaceEl, this.surface.width) : null,
        signStyle: support.signStyle,
        alertOffset: support.alertOffset,
        area: this.mapArea,
        width: this.surface.width,
        height: this.surface.height,
      });
    }

    /** "graphite" only applies where the installed app version is on the verified list */
    graphiteMapApplies() {
      return this.mapColours === "graphite" && !!(this.mapSkin && this.mapSkin.supported);
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
      if (look && MAP_SIZES.indexOf(look.mapSize) >= 0 && look.mapSize !== this.mapSize) {
        this.mapSize = look.mapSize;
        writePref(LOOK_MAP_SIZE_KEY, look.mapSize);
        this.send({ type: "mapScale", scale: MAP_SIZE_SCALES[this.mapSize] });
      }
      if (look && MAP_SHARPNESSES.indexOf(look.mapSharpness) >= 0 && look.mapSharpness !== this.mapSharpness) {
        this.mapSharpness = look.mapSharpness;
        writePref(LOOK_MAP_SHARPNESS_KEY, look.mapSharpness);
        this.send({ type: "renderScale", scale: MAP_SHARPNESS_SCALES[this.mapSharpness] });
      }
      if (look && SPEEDOMETERS.indexOf(look.speedometer) >= 0 && look.speedometer !== this.speedometer) {
        this.speedometer = look.speedometer;
        writePref(LOOK_SPEEDOMETER_KEY, look.speedometer);
        this.drawSpeedometer();
      }
      if (look && SPEEDOMETER_POSITIONS.indexOf(look.speedometerPosition) >= 0 && look.speedometerPosition !== this.speedometerPosition) {
        this.speedometerPosition = look.speedometerPosition;
        writePref(LOOK_SPEEDOMETER_POSITION_KEY, look.speedometerPosition);
        this.drawSpeedometer();
      }
      if (look && PANEL_SIZES.indexOf(look.panelSize) >= 0 && look.panelSize !== this.panelSize) {
        this.panelSize = look.panelSize;
        writePref(LOOK_PANEL_SIZE_KEY, look.panelSize);
        // the panels are laid out again at the new density; the map area they leave free follows
        this.dropRenderer();
        this.draw();
      }
      let saved = Promise.resolve();
      if (look && MAP_COLOURS.indexOf(look.mapColours) >= 0) {
        this.mapColours = look.mapColours;
        if (PAGE_MAP_COLOURS.indexOf(look.mapColours) >= 0) writePref(LOOK_MAP_KEY, look.mapColours);
        // the device keeps it (and restarts the app when "graphite" is switched on or off)
        saved = fetch(TAHtml.createFlavor().apiBaseUrl + "/appInterface", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ mapColours: look.mapColours }),
        }).then((r) => {
          if (!r.ok) throw new Error("HTTP " + r.status);
        });
      }
      this.applyLook();
      return saved;
    }

    /** night: the driver's choice, else the car's night mode from the host (day until it tells) */
    isNight() {
      if (this.dayNight === "night") return true;
      if (this.dayNight === "day") return false;
      return this.hostNight === true;
    }

    applyLook() {
      if (this.renderer) this.renderer.setLook(this.lookStyle, this.isNight());
      // the other look's panels have other sizes: the speedometer follows the turn card and the area
      this.sendVisibleArea();
      // the page's own buttons over the map (#home-controls) follow the look too (carapp.css)
      const graphite = this.mode === "car" && this.lookStyle === "graphite";
      document.body.classList.toggle("carapp-page-graphite", graphite);
      document.body.classList.toggle("carapp-page-night", graphite && this.isNight());
      this.applyMapColours();
      // the settings page follows the car interface's day and night
      global.dispatchEvent(new CustomEvent("teslalinux-carapp-look"));
    }

    /**
     * The app's map in the Graphite look's muted colours (js/display/map-tone.js, a shader pass of
     * the H.264 renderers; the MJPEG one shows it as it is): only with the Graphite look, and only
     * while the page knows whether the app draws its day or its night map (the driver's choice, or
     * the host's night mode), since each has its own palette. With "graphite" map colours the app
     * draws them itself; where its version is not verified for them it draws its own palette, which
     * this page then mutes instead (Settings shows a warning).
     */
    applyMapColours() {
      const known = this.dayNight !== "auto" || this.hostNight !== null;
      const muted = this.mapColours === "muted" || (this.mapColours === "graphite" && !this.graphiteMapApplies());
      const tone = this.mode === "car" && this.lookStyle === "graphite" && muted && known
        ? (this.isNight() ? "night" : "day") : null;
      global.displayMapTone = tone;
      if (typeof global.setDisplayMapTone === "function") global.setDisplayMapTone(tone);
    }

    async start() {
      // on the way to the screen emulator (js/core/screen-emulation.js): the car interface starts in its frame
      if (global.TLScreenEmulation && global.TLScreenEmulation.leaving) return;
      let mode = "phone";
      let state = null;
      try {
        const r = await fetch(TAHtml.createFlavor().apiBaseUrl + "/appInterface", { cache: "no-store" });
        if (!r.ok) return; // no API route: Phone mode, as upstream
        state = await r.json();
        mode = state.interface || "phone";
      } catch (e) {
        return;
      }
      this.mode = mode;
      this.setDeviceMapColours(state);
      // a switch made elsewhere: the page's own health checks carry the interface
      global.addEventListener("teslalinux-app-interface", (e) => {
        if (e.detail && e.detail !== this.mode) global.location.reload();
      });
      if (mode !== "car") return;
      // the page's buttons in the look, and the map colours for the display's renderer, whether it
      // starts before or after this
      this.applyLook();
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
      // the app's speedometer: above the templates, under toasts and alerts
      this.speedoEl = document.createElement("div");
      this.speedoEl.className = "carapp-surface carapp-speedo-surface";
      this.overlay.appendChild(this.speedoEl);
      this.speedo = new TLCarSpeedometer(this.speedoEl);
      this.overlay.appendChild(this.popupsEl);
      displayRoot.appendChild(this.overlay);
      // panels measured while they slide in are measured again where they come to rest
      for (const el of [this.surfaceEl, this.popupsEl]) el.addEventListener("animationend", () => this.movePanelsBelowPageControls());
      this.style = style;
      new ResizeObserver(() => this.fit()).observe(displayRoot);
      // the page closed or left (not merely hidden: the driver can come back to it) with the app's
      // settings open
      global.addEventListener("pagehide", () => this.abandonAppSettings());
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
        if (this.mapSharpness !== "full") this.send({ type: "renderScale", scale: MAP_SHARPNESS_SCALES[this.mapSharpness] });
      };
      ws.onmessage = (e) => this.onMessage(JSON.parse(e.data));
      ws.onclose = () => {
        if (this.socket === ws) this.socket = null;
        // the host went (the app restarting): its screens with it
        const s = this.appSettings;
        if (s) {
          clearTimeout(s.timer);
          this.appSettings = null;
          if (s.listener) s.listener({ state: "unavailable", reason: "offline", depth: 0 });
        }
        this.checkModeOnce();
        clearTimeout(this.retry);
        this.retry = setTimeout(() => this.connect(), RETRY_MS);
      };
    }

    send(msg) {
      if (this.socket && this.socket.readyState === WebSocket.OPEN) this.socket.send(JSON.stringify(msg));
    }

    /**
     * The car's UX restriction mode, as the host last said (its "restrictions" message): "baseline"
     * (typing off while the car moves, as Android Auto) or "passenger" (typing allowed); null while
     * the host has not said, or when its runtime has no restriction modes (no "mode" in the message).
     */
    getRestrictionMode() {
      const r = this.restrictions;
      return r && typeof r.mode === "string" ? r.mode : null;
    }

    /**
     * Asks the host for a restriction mode ("baseline" or "passenger"); the host applies it at once
     * and keeps it for the app's next starts. Resolves when the host's restrictions name the mode,
     * rejects when it does not within 4 s (no channel, or a runtime without restriction modes).
     */
    setRestrictionMode(mode) {
      if (this.getRestrictionMode() === mode) return Promise.resolve();
      return new Promise((resolve, reject) => {
        if (!this.socket || this.socket.readyState !== WebSocket.OPEN) {
          reject(new Error("no car channel"));
          return;
        }
        const waiter = { mode, resolve, timer: null };
        waiter.timer = setTimeout(() => {
          this.modeWaiters = (this.modeWaiters || []).filter((w) => w !== waiter);
          reject(new Error("the host did not apply restriction mode " + mode));
        }, 4000);
        this.modeWaiters = (this.modeWaiters || []).concat(waiter);
        this.send({ type: "restrictionMode", mode });
      });
    }

    onMessage(m) {
      switch (m.type) {
        case "surface": {
          // the panels are laid out at the display's density (dpi); the app's map may be drawn at
          // another (mapDpi, the map size): only a new size or display density needs new tokens
          const s = this.surface;
          if (m.width !== s.width || m.height !== s.height || m.dpi !== s.dpi) this.dropRenderer();
          this.surface = { width: m.width, height: m.height, dpi: m.dpi, mapDpi: m.mapDpi || m.dpi };
          this.fit();
          this.draw();
          break;
        }
        case "mapScale":
          // the map size (state): set by a page since the app started, which this one follows,
          // else this page's own choice goes to the host
          if (m.set) {
            const named = MAP_SIZES.find((k) => Math.abs(MAP_SIZE_SCALES[k] - m.scale) < 0.005);
            if (named && named !== this.mapSize) {
              this.mapSize = named;
              writePref(LOOK_MAP_SIZE_KEY, named);
            }
          } else {
            this.send({ type: "mapScale", scale: MAP_SIZE_SCALES[this.mapSize] });
          }
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
          // the app's settings screens go to the settings page while it shows them
          if (this.appSettings && this.routeAppSettings(m.template)) break;
          this.template = m.template;
          this.draw();
          // the settings page waits for Waze's settings action (navigating, a question on the map)
          if (this.appSettingsWaiting && !this.appSettings && findSettingsAction(m.template.template || m.template)) {
            this.openAppSettings(this.appSettingsWaiting);
          } else if (this.appSettingsWaiting && !this.appSettings) {
            // still not offered: the reason may have changed (the drive started or ended)
            const reason = this.navigating ? "navigating" : "noAction";
            this.appSettingsWaiting({ state: "unavailable", reason, depth: 0 });
          }
          break;
        case "trip": {
          // the app's trip (state): the page shows its destination's name beside the estimate's
          // trip text (Waze's "1 stop": the estimate is then to the stop, the name the destination's)
          const d = m.trip && m.trip.destinations && m.trip.destinations[0];
          const name = d && d.name ? d.name : null;
          if (this.destinationName !== name) {
            this.destinationName = name;
            if (this.renderer) {
              this.renderer.destinationName = name;
              this.draw();
            }
          }
          break;
        }
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
          // the car's driving restrictions (state): lists capped, typing off while moving (unless
          // the car is in the passenger restriction mode: Settings > Waze > Typing while driving)
          this.restrictions = m;
          if (this.renderer) this.renderer.setRestrictions(m);
          if (this.modeWaiters && this.modeWaiters.length) {
            const done = this.modeWaiters.filter((w) => w.mode === m.mode);
            this.modeWaiters = this.modeWaiters.filter((w) => w.mode !== m.mode);
            for (const w of done) {
              clearTimeout(w.timer);
              w.resolve();
            }
          }
          break;
        case "toast":
          if (this.renderer) this.renderer.toast(this.popupsEl, m.text, m.long);
          break;
        case "alert":
          if (this.renderer) this.renderer.alert(this.popupsEl, m.alert);
          this.sendVisibleArea(); // below the page's buttons where it reaches under them
          break;
        case "alertDismiss":
          if (this.renderer) this.renderer.removeAlert(this.popupsEl, m.id);
          break;
        case "speedometer":
          // the app's speedometer (state): speed, unit and the road's limit, drawn here
          this.speedReading = m;
          this.drawSpeedometer();
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
        // the settings page draws glyphs in its own text colour
        if (mono && n > 0 && this.appSettings && this.appSettings.template) this.tellAppSettings();
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
      for (const el of [this.surfaceEl, this.speedoEl, this.popupsEl]) {
        const s = el.style;
        s.width = this.surface.width + "px";
        s.height = this.surface.height + "px";
        s.transform = "scale(" + box.width / this.surface.width + "," + box.height / this.surface.height + ")";
      }
      this.placePageControls(box);
      // the page's own buttons (sound, settings: #home-controls) stand over the display's top right
      // corner: the strip at the top right starts below them (carapp.css, --page-inset-top, in
      // surface pixels), and the map area sent to the app follows
      let inset = 0;
      // and full-screen panels keep their right side clear of them (--page-inset-right)
      let side = 0;
      const controls = document.getElementById("home-controls");
      if (controls && box.height > 0) {
        const b = controls.getBoundingClientRect();
        if (b.height > 0 && b.left < box.right && b.right > box.left && b.bottom > box.top && b.top < box.bottom) {
          inset = Math.ceil((b.bottom - box.top) * this.surface.height / box.height);
          side = Math.ceil((box.right - b.left) * this.surface.width / box.width);
        }
      }
      const value = inset + "px";
      this.surfaceEl.style.setProperty("--page-inset-right", side + "px");
      if (this.surfaceEl.style.getPropertyValue("--page-inset-top") !== value) {
        this.surfaceEl.style.setProperty("--page-inset-top", value);
        this.popupsEl.style.setProperty("--page-inset-top", value);
        this.sendVisibleArea();
      }
    }

    /**
     * Panels that reach under the buttons at the top right, the page's own and the app's top strip
     * (on a narrow display a card is as wide as the display), start below the lowest of them
     * (carapp.css .under-page-controls, --clear-top in surface pixels), so those buttons never
     * cover a card's own buttons, the turn card or an alert.
     */
    movePanelsBelowPageControls() {
      if (!this.surfaceEl || !this.overlay || !this.surface.height) return;
      const box = this.overlay.getBoundingClientRect();
      if (!(box.height > 0)) return;
      const rect = (e) => {
        const b = e.getBoundingClientRect();
        return b.width > 0 && b.height > 0 ? b : null;
      };
      // the page's buttons count wherever a panel reaches under them; the app's top strip buttons
      // only where a panel reaches past their middle (a card's corner may touch them, as the
      // host's layout has it on a car display)
      const controls = document.getElementById("home-controls");
      const ours = controls ? [rect(controls)].filter(Boolean) : [];
      const strip = [...this.surfaceEl.querySelectorAll(".carapp-strip.top > *")].map(rect).filter(Boolean);
      const panels = [
        ...this.surfaceEl.querySelectorAll(".carapp-card:not(.travel), .carapp-pill"),
        ...this.popupsEl.querySelectorAll(".carapp-alert"),
      ];
      for (const el of panels) {
        const r = el.getBoundingClientRect();
        let bottom = -Infinity; // nothing above it (the display may start above the window: Native)
        if (r.width > 0) {
          for (const b of ours) if (r.right > b.left && r.left < b.right) bottom = Math.max(bottom, b.bottom);
          for (const b of strip) {
            const mid = (b.left + b.right) / 2;
            if (r.right > mid && r.left < mid) bottom = Math.max(bottom, b.bottom);
          }
        }
        const clear = bottom > box.top ? Math.ceil((bottom - box.top) * this.surface.height / box.height) : 0;
        el.classList.toggle("under-page-controls", clear > 0);
        if (clear > 0) el.style.setProperty("--clear-top", clear + "px");
        else el.style.removeProperty("--clear-top");
      }
    }

    /**
     * The page's own buttons (#home-controls, css/app.css) as the car interface's map buttons: a
     * map button's size (never below a 56 px touch target), the map buttons' gap, and their inset
     * from the shown display's top right corner, all in CSS px; the app's map buttons are measured
     * where there are some, else the host's touch target and strip padding stand in. Returns
     * whether anything changed.
     */
    placePageControls(box) {
      const controls = document.getElementById("home-controls");
      if (!controls || !this.surfaceEl || !this.surface.width) return false;
      box = box || this.overlay.getBoundingClientRect();
      const stage = controls.offsetParent ? controls.offsetParent.getBoundingClientRect() : null;
      if (!(box.width > 0) || !stage) return false;
      const scale = box.width / this.surface.width;
      const root = this.surfaceEl.querySelector(".carapp-root");
      const cs = root ? getComputedStyle(root) : null;
      const prop = (name) => (cs ? parseFloat(cs.getPropertyValue(name)) || 0 : 0);
      let size = prop("--touch-target") * scale;
      let inset = prop("--strip-pad") * scale;
      let gap = prop("--pad-2") * scale;
      const fab = this.surfaceEl.querySelector(".carapp-strip.map .carapp-fab") || this.surfaceEl.querySelector(".carapp-strip .carapp-fab");
      if (fab) {
        const f = fab.getBoundingClientRect();
        if (f.height > 0) {
          size = f.height; // a titled button is wider than it is tall: its height
          inset = Math.max(0, box.right - f.right);
          const strip = fab.closest(".carapp-strip");
          const g = parseFloat(getComputedStyle(strip).rowGap) || 0;
          if (g > 0) gap = g * scale;
        }
      }
      // a screen without map buttons (a full-screen list, search) keeps the map buttons' measure
      // of the same look, panel size and display, so the page's buttons keep their size
      const key = this.lookStyle + "/" + this.panelSize + "/" + Math.round(box.width) + "x" + Math.round(box.height);
      const measured = fab && fab.getBoundingClientRect().height > 0;
      if (measured) this.pageControlMeasure = { key, size, inset, gap };
      else if (this.pageControlMeasure && this.pageControlMeasure.key === key) ({ size, inset, gap } = this.pageControlMeasure);
      const px = (v) => Math.round(v * 2) / 2 + "px";
      const vars = {
        "--hc-size": px(Math.max(56, size)),
        "--hc-gap": px(Math.max(8, gap)),
        "--hc-top": px(Math.max(0, box.top - stage.top) + Math.max(8, inset)),
        "--hc-right": px(Math.max(0, stage.right - box.right) + Math.max(8, inset)),
      };
      let changed = false;
      for (const k in vars) {
        if (controls.style.getPropertyValue(k) === vars[k]) continue;
        controls.style.setProperty(k, vars[k]);
        changed = true;
      }
      return changed;
    }

    /** The map area the panels leave free goes to the app (it keeps its car and route there). */
    sendVisibleArea() {
      if (!this.renderer || !this.surface.width) return;
      requestAnimationFrame(() => {
        if (!this.renderer) return;
        // the panels as now drawn (a new look or panel size redraws them): the page's buttons follow
        // the map buttons, and the top strip moves below them again
        if (this.placePageControls()) this.fit();
        this.movePanelsBelowPageControls();
        const a = this.renderer.visibleArea(this.surface.width, this.surface.height);
        // the speedometer stands at the area's bottom right, as the app placed its own; none over
        // a full-screen panel
        this.mapArea = a;
        this.drawSpeedometer();
        if (!a) return;
        const key = JSON.stringify(a);
        if (key === this.lastArea) return;
        this.lastArea = key;
        this.send(Object.assign({ type: "visibleArea" }, a));
      });
    }

    /** The renderer goes (a new size or density): its listeners with it, or each tap went once per renderer made. */
    dropRenderer() {
      if (this.renderer) this.renderer.destroy();
      this.renderer = null;
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
          // the display's density, scaled by the panel size chosen in the settings
          density: this.surface.dpi * PANEL_SIZE_SCALES[this.panelSize],
        });
        this.renderer.listen(this.popupsEl);
        this.renderer.listen(this.speedoEl);
        this.renderer.setLook(this.lookStyle, this.isNight());
        this.renderer.navigating = !!this.navigating;
        this.renderer.destinationName = this.destinationName || null;
        if (this.restrictions) this.renderer.setRestrictions(this.restrictions);
      }
      this.renderer.render(this.template);
      this.sendVisibleArea();
    }
  }

  const controller = new CarAppController();
  global.TLCarApp = controller;
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", () => controller.start());
  else controller.start();
})(window);
