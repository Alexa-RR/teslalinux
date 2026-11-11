// teslalinux: draws an app's car templates (the Car App Library's NavigationTemplate,
// MapWithContentTemplate, ListTemplate, PaneTemplate, MessageTemplate, GridTemplate) as HTML, the
// way the AOSP car Templates Host lays them out: its layouts (steps card top left, travel estimate
// card bottom left, floating action strip top right, map action strip bottom right, content card
// on the left over the map, full-screen lists), with its sizes and colours only, read from the
// host APK's resources (host-style.json, tools/carhost/host-style.py). The map under the panels is
// the app's own drawing, streamed as the display.
//
// Input: the template JSON of /sockets/carapp (ATL's car-app host, CarModelJson) and the icons
// it sent. Output: DOM inside a root element sized in display pixels; interactions go to
// ctx.send({type:"click", cb:ID}, ...).
(function (global) {
  "use strict";

  // ---- units and tokens -------------------------------------------------------------------------

  /** CarColor types (androidx.car.app.model.CarColor) to the host's standard colours */
  const CAR_COLOR = {
    2: "template_standard_blue",   // PRIMARY
    3: "template_standard_yellow", // SECONDARY
    4: "template_standard_red",
    5: "template_standard_green",
    6: "template_standard_blue",
    7: "template_standard_yellow",
  };
  /** Distance.displayUnit to the unit text (androidx.car.app.model.Distance) */
  const DISTANCE_UNIT = { 1: "m", 2: "km", 3: "km", 4: "mi", 5: "mi", 6: "ft", 7: "yd" };
  /**
   * Maneuver types (androidx.car.app.navigation.model.Maneuver) to Material Symbols glyphs
   * (fonts/MaterialSymbolsRounded-car.woff2): for a maneuver without an icon of its own.
   * Roundabouts: counterclockwise (right-hand traffic) is roundabout_right, clockwise _left.
   */
  const G = { turnL: 0xeba6, turnR: 0xebab, slightL: 0xeba4, slightR: 0xeb9a, sharpL: 0xeba7, sharpR: 0xebaa,
    uL: 0xeba1, uR: 0xeba2, roundL: 0xeb99, roundR: 0xeba3, straight: 0xeb95, merge: 0xeb98, forkL: 0xeba0,
    forkR: 0xebac, rampL: 0xeb9c, rampR: 0xeb96, depart: 0xe57b, flag: 0xf0c6, boat: 0xeff5, train: 0xe570 };
  const MANEUVER_GLYPH = {
    1: G.depart, 2: G.straight, 3: G.slightL, 4: G.slightR, 5: G.slightL, 6: G.slightR, 7: G.turnL, 8: G.turnR,
    9: G.sharpL, 10: G.sharpR, 11: G.uL, 12: G.uR,
    13: G.rampL, 14: G.rampR, 15: G.rampL, 16: G.rampR, 17: G.rampL, 18: G.rampR, 19: G.uL, 20: G.uR,
    21: G.rampL, 22: G.rampR, 23: G.rampL, 24: G.rampR, 25: G.forkL, 26: G.forkR,
    27: G.merge, 28: G.merge, 29: G.merge,
    32: G.roundL, 33: G.roundL, 34: G.roundR, 35: G.roundR, 43: G.roundL, 44: G.roundL, 45: G.roundR, 46: G.roundR,
    36: G.straight, 37: G.boat, 38: G.train, 47: G.boat, 48: G.boat, 49: G.train, 50: G.train,
    39: G.flag, 40: G.flag, 41: G.flag, 42: G.flag,
  };

  /** Action standard types (androidx.car.app.model.Action) */
  const ACTION_APP_ICON = 65538;
  const ACTION_BACK = 65539;
  const ACTION_PAN = 65540;
  const ACTION_FLAG_PRIMARY = 1;
  /** Action.FLAG_IS_PERSISTENT: stays on screen while the host hides the other map actions */
  const ACTION_FLAG_PERSISTENT = 2;
  /** Action.FLAG_DEFAULT: the template's default action, which the host runs when its timer ends */
  const ACTION_FLAG_DEFAULT = 4;
  /** how long the default action's countdown runs before the host runs it */
  const DEFAULT_ACTION_MS = 10000;
  /** how long the expanded map actions (zoom, recentre) stay out after the last touch */
  const MAP_ACTIONS_OPEN_MS = 8000;
  /** CarIconSpan alignments (androidx.car.app.model.CarIconSpan) to CSS vertical-align */
  const ICON_SPAN_ALIGN = { 0: "text-bottom", 1: "baseline", 2: "middle" };

  /**
   * The direction of an app text: that of its first letter. (dir="auto" also counts the
   * invisible direction marks the app puts in front of addresses, which made a Hebrew address
   * left-to-right.)
   */
  function textDir(s) {
    const m = s.match(/[A-Za-zÀ-ɏͰ-ϿЀ-ӿ֐-ࣿיִ-﷿ﹰ-ﻼ]/);
    if (!m) return "auto";
    return /[֐-ࣿיִ-﷿ﹰ-ﻼ]/.test(m[0]) ? "rtl" : "ltr";
  }

  /** What makes a screen a different one: its template kind, pan mode and header title. */
  function screenKey(layer) {
    const title = layer.querySelector(".carapp-header-title");
    return layer.className.replace(/\b(enter|restricted)\b/g, "").trim() + "|" + (title ? title.textContent : "") +
      "|" + (layer.querySelector(".carapp-search") ? "search" : "");
  }

  /** The ± symbol of the collapsed map actions (inline SVG, in the button's colour). */
  function zoomGlyph() {
    const ns = "http://www.w3.org/2000/svg";
    const svg = document.createElementNS(ns, "svg");
    svg.setAttribute("viewBox", "0 0 24 24");
    svg.setAttribute("class", "carapp-zoom-glyph");
    const p = document.createElementNS(ns, "path");
    p.setAttribute("d", "M7 4v6M4 7h6M14 17h6M18 4 6 20");
    p.setAttribute("fill", "none");
    p.setAttribute("stroke", "currentColor");
    p.setAttribute("stroke-width", "2");
    p.setAttribute("stroke-linecap", "round");
    svg.appendChild(p);
    return svg;
  }

  /** A duration as car hosts show it: "45 sec" under a minute, "8 min", "1 hr 5 min". */
  function formatDuration(seconds) {
    if (!(seconds >= 0)) return "";
    if (seconds < 60) return Math.round(seconds) + " sec";
    const m = Math.round(seconds / 60);
    if (m < 60) return m + " min";
    return Math.floor(m / 60) + " hr" + (m % 60 ? " " + (m % 60) + " min" : "");
  }

  /** Makes the element a like b (same structure: attributes and text updated, nodes kept). */
  function morph(a, b) {
    if (a.nodeType !== b.nodeType || a.nodeName !== b.nodeName) {
      a.replaceWith(b);
      return;
    }
    if (a.nodeType === 3) {
      if (a.data !== b.data) a.data = b.data;
      return;
    }
    if (a.nodeType !== 1) return;
    for (const at of Array.from(a.attributes)) if (!b.hasAttribute(at.name)) a.removeAttribute(at.name);
    for (const at of Array.from(b.attributes)) if (a.getAttribute(at.name) !== at.value) a.setAttribute(at.name, at.value);
    const ac = Array.from(a.childNodes);
    const bc = Array.from(b.childNodes);
    for (let i = 0; i < bc.length; i++) {
      if (i < ac.length) morph(ac[i], bc[i]);
      else a.appendChild(bc[i]);
    }
    for (let i = bc.length; i < ac.length; i++) ac[i].remove();
  }

  function argbToCss(argb) {
    // a Java int: signed 32-bit ARGB
    const v = argb >>> 0;
    const a = ((v >>> 24) & 0xff) / 255;
    return "rgba(" + ((v >>> 16) & 0xff) + "," + ((v >>> 8) & 0xff) + "," + (v & 0xff) + "," + a.toFixed(3) + ")";
  }

  function hexToCss(hex) {
    // #aarrggbb (aapt) to rgba()
    if (!hex || hex[0] !== "#" || hex.length !== 9) return hex || "transparent";
    const a = parseInt(hex.substr(1, 2), 16) / 255;
    return "rgba(" + parseInt(hex.substr(3, 2), 16) + "," + parseInt(hex.substr(5, 2), 16) + "," + parseInt(hex.substr(7, 2), 16) + "," + a.toFixed(3) + ")";
  }

  class Renderer {
    /**
     * @param {HTMLElement} root  element the templates are drawn in (display-sized, see layout())
     * @param {object} style      host-style.json
     * @param {object} ctx        {send(msg), icon(id) -> url or null, density (dpi)}
     */
    constructor(root, style, ctx) {
      this.root = root;
      this.style = style;
      this.ctx = ctx;
      this.dp = (ctx.density || 160) / 160;
      root.classList.add("carapp-root");
      this.applyTokens();
      this.listen(root);
    }

    /**
     * One listener for every tap in a container: what to do and the app's callback id are
     * attributes, so a panel updated in place (show) works with the newest template's callbacks.
     */
    listen(root) {
      if (root !== this.root) {
        root.classList.add("carapp-root");
        this.applyTokens(root);
      }
      // touching the panel that runs a default action's countdown stops it (the driver is choosing)
      root.addEventListener("pointerdown", (e) => {
        if (this.countdown && e.target.closest(".carapp-card.content")) this.stopCountdown();
      });
      root.addEventListener("click", (e) => {
        const el = e.target.closest("[data-act]");
        if (!el || !root.contains(el)) return;
        e.stopPropagation();
        const cb = el.dataset.cb !== undefined ? Number(el.dataset.cb) : undefined;
        // a touch on the map actions keeps them out
        if (el.closest(".carapp-strip.map") && this.mapActionsOpen) this.openMapActions(true);
        switch (el.dataset.act) {
          case "click":
            this.ctx.send({ type: "click", cb: cb });
            break;
          case "map-actions":
            this.openMapActions(!this.mapActionsOpen);
            break;
          case "page-up":
          case "page-down": {
            const body = el.closest(".carapp-layer").querySelector(".carapp-body");
            if (body) body.scrollBy({ top: (el.dataset.act === "page-up" ? -1 : 1) * body.clientHeight * 0.9, behavior: "smooth" });
            break;
          }
          case "checked": {
            const on = !el.classList.contains("on");
            el.classList.toggle("on", on);
            this.ctx.send({ type: "checked", cb: cb, checked: on });
            break;
          }
          case "selected":
            for (const o of el.parentNode.querySelectorAll(".selectable.selected")) o.classList.remove("selected");
            el.classList.add("selected");
            this.ctx.send({ type: "selected", cb: cb, index: Number(el.dataset.index) });
            // a route list: the route's start action goes with the selection
            if (el.parentNode.classList.contains("routes")) {
              this.localSelection = { cb: cb, index: Number(el.dataset.index) };
              if (this.lastWrapper) this.render(this.lastWrapper);
            }
            break;
          case "back":
            this.ctx.send({ type: "back" });
            break;
          case "stop-nav":
            this.ctx.send({ type: "stopNavigation" });
            break;
          case "pan-on":
            this.setPanMode(true);
            break;
          case "pan-off":
            this.setPanMode(false);
            break;
          case "alert-action": {
            // an alert action: its own callback, then the alert is done (AlertCallback.onDismiss)
            const card = el.closest(".carapp-alert");
            this.ctx.send({ type: "click", cb: cb });
            this.finishAlert(card, "dismiss");
            break;
          }
          case "alert-close":
            this.finishAlert(el.closest(".carapp-alert"), 2 /* REASON_USER_ACTION */);
            break;
        }
      });
    }

    dimen(name, fallback) {
      const v = this.style.dimen[name];
      return (v === undefined ? fallback : v) * this.dp;
    }

    /** one of the host's integers (its content limits) */
    limit(name, fallback) {
      const v = this.style.integer && this.style.integer[name];
      return v === undefined ? fallback : v;
    }

    color(name, fallback) {
      const v = this.style.color[name];
      return v === undefined ? fallback : hexToCss(v);
    }

    /** the host's values as CSS custom properties, in display pixels */
    applyTokens(el) {
      const s = (el || this.root).style;
      const px = (n) => this.dimen(n, 0) + "px";
      s.setProperty("--card-bg", this.color("template_card_background_color", "#000"));
      s.setProperty("--card-border", this.color("template_card_content_container_border_color", "transparent"));
      s.setProperty("--card-border-width", px("template_card_content_container_border_width"));
      s.setProperty("--card-radius", px("template_card_corner_radius") === "0px" ? px("template_row_corner_radius") : px("template_card_corner_radius"));
      s.setProperty("--card-start", px("template_card_content_container_start_margin"));
      s.setProperty("--card-top", px("template_card_content_container_top_margin"));
      s.setProperty("--card-bottom", px("template_card_content_container_bottom_margin"));
      s.setProperty("--card-width", px("template_card_content_container_default_width"));
      s.setProperty("--card-min-height", px("template_card_content_container_min_height"));
      s.setProperty("--routing-card-width", px("template_card_content_container_min_width"));
      s.setProperty("--nav-pad-h", px("template_nav_card_padding_horizontal"));
      s.setProperty("--nav-pad-v", px("template_nav_card_padding_vertical"));
      s.setProperty("--nav-pad-v-small", px("template_nav_card_small_padding_vertical"));
      s.setProperty("--nav-large-image", px("template_nav_card_large_image_size"));
      s.setProperty("--nav-small-image", px("template_nav_card_small_image_size"));
      s.setProperty("--strip-pad", px("template_action_strip_padding"));
      s.setProperty("--fab-size", px("template_action_fab_focus_ring_size"));
      s.setProperty("--fab-bg", this.color("template_action_strip_fab_background_color", "#fff"));
      s.setProperty("--fab-fg", this.color("template_action_strip_fab_content_color", "#000"));
      s.setProperty("--action-icon", px("car_app_ui_button_image_size"));
      s.setProperty("--action-height", px("car_app_ui_button_height"));
      s.setProperty("--action-radius", px("car_app_ui_button_corner_radius"));
      s.setProperty("--action-text-pad", px("car_app_ui_button_text_horizontal_spacing"));
      s.setProperty("--action-min-width", px("template_action_with_text_min_width"));
      s.setProperty("--action-bg", this.color("car_app_ui_action_button_default_background_color", "#2e3134"));
      s.setProperty("--action-primary-bg", this.color("car_app_ui_action_button_primary_background_color", "#669df6"));
      s.setProperty("--action-fg", this.color("car_app_ui_action_button_text_color", "#fff"));
      s.setProperty("--touch-target", px("car_app_ui_touch_target_size"));
      s.setProperty("--row-min-height", px("template_row_min_height"));
      s.setProperty("--row-pad-h", px("template_row_horizontal_padding"));
      s.setProperty("--row-text-pad", px("template_row_text_horizontal_padding"));
      s.setProperty("--row-icon", px("template_row_icon_size"));
      s.setProperty("--row-image-small", px("template_row_image_size_small"));
      s.setProperty("--row-image-large", px("template_row_image_size_large"));
      s.setProperty("--row-radius", px("template_row_corner_radius"));
      s.setProperty("--row-bg", this.color("car_app_ui_row_background_color", "#000"));
      s.setProperty("--ripple", this.color("template_ripple_color", "rgba(255,255,255,.4)"));
      s.setProperty("--text", this.color("template_content_text_color", "#f8f9fa"));
      s.setProperty("--text-secondary", this.color("template_content_text_color_secondary", "#9aa0a6"));
      s.setProperty("--body1", px("car_ui_body1_size"));
      s.setProperty("--body2", px("car_ui_body2_size"));
      s.setProperty("--body3", px("car_ui_body3_size"));
      s.setProperty("--pad-0", px("template_padding_0"));
      s.setProperty("--pad-6", px("template_padding_6"));
      s.setProperty("--row-list-max", px("template_row_list_max_width"));
      s.setProperty("--pad-1", px("template_padding_1"));
      s.setProperty("--pad-2", px("template_padding_2"));
      s.setProperty("--pad-3", px("template_padding_3"));
      s.setProperty("--pad-4", px("template_padding_4"));
      s.setProperty("--pad-5", px("template_padding_5"));
      s.setProperty("--divider", this.color("template_gray_846", "#2e3134"));
      s.setProperty("--blue", this.color("template_standard_blue", "#669df6"));
    }

    // ---- model helpers --------------------------------------------------------------------------

    carColor(c, fallback) {
      if (!c) return fallback;
      if (c.type === 0 || c.type === undefined) {
        // CUSTOM: the dark variant on the car's dark theme
        const v = c.colorDark !== undefined ? c.colorDark : c.color;
        return v === undefined ? fallback : argbToCss(v);
      }
      if (c.type === 1) return fallback; // DEFAULT
      return this.color(CAR_COLOR[c.type], fallback);
    }

    /**
     * An app text as a string: a CarText with spans gets its durations and distances formatted
     * in place of their placeholder characters (as car hosts show "8 min", "3.2 km"); inline icons
     * drop out.
     */
    text(t) {
      if (t === undefined || t === null) return "";
      if (typeof t !== "object" || t.$ !== "CarText") return String(t);
      let s = t.text || "";
      const spans = (t.spans || []).slice().sort((a, b) => b.start - a.start);
      for (const sp of spans) {
        const kind = sp.span && sp.span.$;
        let value = null;
        if (kind === "DurationSpan") value = formatDuration(sp.span.durationSeconds);
        else if (kind === "DistanceSpan") value = this.distance(sp.span.distance);
        else if (kind === "CarIconSpan") value = "";
        if (value !== null && sp.start >= 0 && sp.end <= s.length) s = s.slice(0, sp.start) + value + s.slice(sp.end);
      }
      return s;
    }

    /**
     * Puts an app text into el as car hosts draw it: a CarText's spans become coloured runs
     * (ForegroundCarColorSpan), inline images (CarIconSpan, in the text's colour when they are
     * single-colour glyphs) and formatted durations and distances in place of their placeholder
     * characters; line breaks stay (CSS white-space).
     */
    setText(el, t) {
      el.textContent = "";
      if (t === undefined || t === null) return el;
      if (typeof t !== "object" || t.$ !== "CarText" || !(t.spans && t.spans.length)) {
        el.textContent = this.text(t);
        return el;
      }
      const s = t.text || "";
      const spans = t.spans.filter((sp) => sp.span && sp.start >= 0 && sp.end <= s.length && sp.start < sp.end);
      const cuts = new Set([0, s.length]);
      for (const sp of spans) {
        cuts.add(sp.start);
        cuts.add(sp.end);
      }
      const points = Array.from(cuts).sort((a, b) => a - b);
      const done = new Set();
      for (let i = 0; i + 1 < points.length; i++) {
        const a = points[i];
        const b = points[i + 1];
        const over = spans.filter((sp) => sp.start <= a && sp.end >= b);
        // a span that stands for its characters (a duration, a distance, an image): once
        const rep = over.find((sp) => /^(DurationSpan|DistanceSpan|CarIconSpan)$/.test(sp.span.$));
        let node;
        if (rep) {
          if (done.has(rep)) continue;
          done.add(rep);
          node = this.spanNode(rep.span);
          if (!node) continue;
        } else {
          node = document.createTextNode(s.slice(a, b));
        }
        // the innermost colour span over this run
        const colour = over.filter((sp) => sp.span.$ === "ForegroundCarColorSpan")
          .sort((x, y) => (x.end - x.start) - (y.end - y.start))[0];
        const c = colour ? this.carColor(colour.span.color, null) : null;
        if (c) {
          const run = document.createElement("span");
          run.style.color = c;
          run.appendChild(node);
          node = run;
        }
        el.appendChild(node);
      }
      return el;
    }

    /** what a replacing span shows: a formatted duration or distance, or an inline image */
    spanNode(span) {
      switch (span.$) {
        case "DurationSpan":
          return document.createTextNode(formatDuration(span.durationSeconds));
        case "DistanceSpan":
          return document.createTextNode(this.distance(span.distance));
        case "CarIconSpan": {
          const ic = this.icon(span.icon, null, "currentColor");
          if (!ic) return null;
          ic.classList.add("inline");
          ic.style.verticalAlign = ICON_SPAN_ALIGN[span.alignment] || "middle";
          return ic;
        }
      }
      return null;
    }

    /** a CarIcon as an element: the app's image (tinted when the icon has a tint), else null */
    icon(carIcon, sizeVar, defaultTint) {
      if (!carIcon) return null;
      const ic = carIcon.icon;
      const url = ic && ic.icon !== undefined ? this.ctx.icon(ic.icon) : null;
      const el = document.createElement("span");
      el.className = "carapp-icon";
      if (sizeVar) el.style.setProperty("--icon-size", "var(" + sizeVar + ")");
      if (!url) return el;
      // CarColor.DEFAULT as a tint: the host's default content colour
      let tint = carIcon.tint ? (carIcon.tint.type === 1 ? defaultTint || null : this.carColor(carIcon.tint, null)) : null;
      // the host's default tint: a single-colour glyph without a tint of its own takes the
      // colour of what it is on (a button's content colour)
      if (!tint && defaultTint && this.ctx.iconIsGlyph && this.ctx.iconIsGlyph(ic.icon)) tint = defaultTint;
      if (tint) {
        el.classList.add("tinted");
        el.style.backgroundColor = tint;
        el.style.webkitMaskImage = el.style.maskImage = "url(" + url + ")";
      } else {
        el.style.backgroundImage = "url(" + url + ")";
      }
      return el;
    }

    /**
     * A maneuver's image: the app's icon when it gives one, else the standard glyph for its type
     * (as car hosts draw a maneuver the app sends without an icon), with the exit number for a
     * roundabout.
     */
    maneuverIcon(m, sizeVar, defaultTint) {
      if (!m) return null;
      if (m.icon) return this.icon(m.icon, sizeVar, defaultTint);
      const code = MANEUVER_GLYPH[m.type];
      if (!code) return null;
      const el = document.createElement("span");
      el.className = "carapp-glyph";
      el.style.setProperty("--icon-size", "var(" + sizeVar + ")");
      el.textContent = String.fromCharCode(code);
      if (m.roundaboutExitNumber > 0) {
        const n = document.createElement("span");
        n.className = "carapp-glyph-badge";
        n.textContent = String(m.roundaboutExitNumber);
        el.appendChild(n);
      }
      return el;
    }

    clickable(el, delegate) {
      if (!delegate || delegate.$cb === undefined) return;
      el.classList.add("carapp-clickable");
      el.dataset.act = "click";
      el.dataset.cb = delegate.$cb;
    }

    distance(d) {
      if (!d) return "";
      const v = d.displayDistance;
      const unit = DISTANCE_UNIT[d.displayUnit] || "";
      // the _P1 units always with one decimal; km and miles with one below 10, as car hosts show them
      let shown;
      if (d.displayUnit === 3 || d.displayUnit === 5) shown = v.toFixed(1);
      else if ((d.displayUnit === 2 || d.displayUnit === 4) && v < 10) shown = (Math.round(v * 10) / 10).toString();
      else shown = Math.round(v).toString();
      return shown + " " + unit;
    }

    // ---- shared pieces ----------------------------------------------------------------------------

    /** an Action as the host's action button: icon and/or title, primary flag, background colour */
    action(a, fab) {
      const b = document.createElement("div");
      b.className = fab ? "carapp-fab" : "carapp-action";
      if (a.type === ACTION_BACK) {
        b.classList.add("back");
        // the standard Back action: the host's back, which the app gets as onBackPressed
        if (!a.onClickDelegate) {
          b.classList.add("carapp-clickable");
          b.dataset.act = "back";
        }
      }
      if (a.type === ACTION_PAN) {
        b.classList.add("pan");
        // the host's own action: pan mode, which the app hears through its PanModeDelegate
        if (this.panDelegate) {
          b.classList.add("carapp-clickable");
          b.dataset.act = "pan-on";
        }
      }
      if (a.flags & ACTION_FLAG_PRIMARY) b.classList.add("primary");
      if (a.flags & ACTION_FLAG_PERSISTENT) b.classList.add("persistent");
      const bg = this.carColor(a.backgroundColor, null);
      if (a.flags & ACTION_FLAG_DEFAULT && !fab && a.onClickDelegate && a.enabled !== false) {
        // the default action: its button fills up as the host's countdown runs, then it runs
        // (armCountdown); the fill takes the action's colour
        b.classList.add("default-action");
        if (bg) b.style.setProperty("--fill", bg);
      } else if (bg) {
        b.style.backgroundColor = bg;
      }
      let ic = this.icon(a.icon, "--action-icon", "currentColor");
      if (!ic && a.type === ACTION_APP_ICON) {
        // the app's own icon (the host's channel sends it as icon 0)
        b.classList.add("app-icon");
        ic = this.icon({ icon: { icon: 0 } }, "--action-icon");
      }
      if (ic) b.appendChild(ic);
      else if (a.type === ACTION_BACK || a.type === ACTION_PAN) {
        const std = document.createElement("span");
        std.className = "carapp-std-icon " + (a.type === ACTION_BACK ? "back" : "pan");
        b.appendChild(std);
      }
      if (a.title) {
        const t = document.createElement("span");
        t.className = "carapp-action-title";
        this.setText(t, a.title);
        b.appendChild(t);
        b.classList.add("with-title");
      }
      if (a.enabled === false || a.isEnabled === false) b.classList.add("disabled");
      else this.clickable(b, a.onClickDelegate);
      return b;
    }

    /** ActionStrip as the floating strip (action_strip_view_floating) */
    actionStrip(strip, cls, opts) {
      const wrap = document.createElement("div");
      wrap.className = "carapp-strip " + cls;
      if (!strip || !strip.actions) return wrap;
      opts = opts || {};
      let actions = strip.actions;
      // an action the content's header already shows (apps give both for hosts without headers)
      if (opts.skipIcons) actions = actions.filter((a) => !(a.icon && a.icon.icon && opts.skipIcons.has(a.icon.icon.icon)));
      // the map actions over the bare map: the ones that are not persistent fold into one ±
      // button, which lays them out for a while (MAP_ACTIONS_OPEN_MS after the last touch)
      const folding = opts.fold && actions.filter((a) => !(a.flags & ACTION_FLAG_PERSISTENT)).length > 1;
      if (folding && !this.mapActionsOpen) {
        const toggle = document.createElement("div");
        toggle.className = "carapp-fab carapp-clickable map-toggle";
        toggle.dataset.act = "map-actions";
        toggle.setAttribute("aria-label", "Map controls");
        toggle.appendChild(zoomGlyph());
        wrap.appendChild(toggle);
        actions = actions.filter((a) => a.flags & ACTION_FLAG_PERSISTENT);
      }
      if (folding) wrap.classList.add(this.mapActionsOpen ? "open" : "folded");
      for (const a of actions) wrap.appendChild(this.action(a, true));
      return wrap;
    }

    /** the folded map actions out (and the timer that folds them again restarted) or in */
    openMapActions(open) {
      clearTimeout(this.mapActionsTimer);
      const was = this.mapActionsOpen;
      this.mapActionsOpen = open;
      if (open) this.mapActionsTimer = setTimeout(() => this.openMapActions(false), MAP_ACTIONS_OPEN_MS);
      if (was !== open && this.lastWrapper) this.render(this.lastWrapper);
    }

    /** the icon ids of a header's end actions */
    headerIcons(t) {
      const ids = new Set();
      const ends = t && t.header && t.header.endHeaderActions;
      for (const a of ends || []) if (a.icon && a.icon.icon) ids.add(a.icon.icon.icon);
      return ids;
    }

    card(cls) {
      const c = document.createElement("div");
      c.className = "carapp-card " + (cls || "");
      return c;
    }

    header(title, headerAction, endActions) {
      const h = document.createElement("div");
      h.className = "carapp-header";
      if (headerAction) h.appendChild(this.action(headerAction, false)).classList.add("header-action");
      const t = document.createElement("div");
      t.className = "carapp-header-title";
      this.setText(t, title);
      h.appendChild(t);
      if (endActions) for (const a of endActions) h.appendChild(this.action(a, false)).classList.add("header-end");
      return h;
    }

    row(r) {
      const el = document.createElement("div");
      el.className = "carapp-row";
      if (r.image) {
        const img = this.icon(r.image, r.rowImageType === 2 ? "--row-image-large" : "--row-image-small", "currentColor");
        if (img) {
          img.classList.add("row-image");
          if (r.numericDecoration >= 0) {
            // a number the host draws over the image's corner (Row.setNumericDecoration)
            const wrap = document.createElement("span");
            wrap.className = "carapp-decorated";
            wrap.appendChild(img);
            const n = document.createElement("span");
            n.className = "carapp-decoration";
            n.textContent = String(r.numericDecoration);
            wrap.appendChild(n);
            el.appendChild(wrap);
          } else {
            el.appendChild(img);
          }
        }
      }
      const body = document.createElement("div");
      body.className = "carapp-row-body";
      const title = document.createElement("div");
      title.className = "carapp-row-title";
      this.setText(title, r.title);
      body.appendChild(title);
      for (const t of r.texts || []) {
        // an empty line takes no room (apps fill the text list to a fixed length)
        if (!this.text(t).trim()) continue;
        const line = document.createElement("div");
        line.className = "carapp-row-text";
        this.setText(line, t);
        body.appendChild(line);
      }
      el.appendChild(body);
      // the row's own actions (Row.addAction, car API 6): icon buttons, or a coloured text button
      for (const a of r.actions || []) {
        const b = this.action(a, false);
        b.classList.add("row-action");
        el.appendChild(b);
      }
      if (r.toggle) {
        const tg = document.createElement("span");
        tg.className = "carapp-toggle" + (r.toggle.isChecked || r.toggle.checked ? " on" : "");
        el.appendChild(tg);
        const cb = r.toggle.onCheckedChangeDelegate;
        if (cb) {
          tg.classList.add("carapp-clickable");
          tg.dataset.act = "checked";
          tg.dataset.cb = cb.$cb;
        }
      }
      if (r.isBrowsable || r.browsable) {
        const chev = document.createElement("span");
        chev.className = "carapp-chevron";
        el.appendChild(chev);
      }
      if (r.enabled === false || r.isEnabled === false) el.classList.add("disabled");
      else this.clickable(el, r.onClickDelegate);
      return el;
    }

    /**
     * An ItemList. A selectable list shows its choice: radio marks, or for routes
     * (opts.routes) the chosen route highlighted with the start action (opts.go) in it.
     * While driving, a list shows only as many items as the host allows (opts.limit).
     */
    itemList(list, opts) {
      opts = opts || {};
      const box = document.createElement("div");
      box.className = "carapp-list" + (opts.routes ? " routes" : "");
      if (!list) return box;
      let items = list.items || [];
      const limit = this.restricted ? opts.limit || this.limit("content_limit_list", 6) : 0;
      if (limit && items.length > limit) {
        items = items.slice(0, limit);
        box.classList.add("capped");
      }
      let selected = list.selectedIndex;
      if (list.onSelectedDelegate && this.localSelection && this.localSelection.cb === list.onSelectedDelegate.$cb)
        selected = this.localSelection.index;
      items.forEach((item, i) => {
        const el = item.$ === "GridItem" ? this.gridItem(item) : this.row(item);
        if (list.onSelectedDelegate) {
          el.classList.add("carapp-clickable", "selectable");
          if (selected === i) el.classList.add("selected");
          if (!opts.routes) {
            // a selectable list's choice mark (the host's radio button)
            const radio = document.createElement("span");
            radio.className = "carapp-radio";
            el.appendChild(radio);
          } else if (selected === i && opts.go) {
            const go = this.action(opts.go, false);
            go.classList.add("route-go");
            el.querySelector(".carapp-row-body").appendChild(go);
          }
          el.dataset.act = "selected";
          el.dataset.cb = list.onSelectedDelegate.$cb;
          el.dataset.index = i;
        }
        box.appendChild(el);
      });
      if (list.noItemsMessage && !(list.items || []).length) {
        const empty = document.createElement("div");
        empty.className = "carapp-empty";
        empty.textContent = this.text(list.noItemsMessage);
        box.appendChild(empty);
      }
      return box;
    }

    gridItem(g) {
      const el = document.createElement("div");
      el.className = "carapp-grid-item";
      const img = this.icon(g.image, g.imageType === 2 ? "--row-image-large" : "--row-image-small");
      if (img) el.appendChild(img);
      const t = document.createElement("div");
      t.className = "carapp-row-title";
      this.setText(t, g.title);
      el.appendChild(t);
      if (g.text) {
        const s = document.createElement("div");
        s.className = "carapp-row-text";
        this.setText(s, g.text);
        el.appendChild(s);
      }
      this.clickable(el, g.onClickDelegate);
      return el;
    }

    loading() {
      const p = document.createElement("div");
      p.className = "carapp-progress";
      return p;
    }

    // ---- content templates (in a card over the map, or full screen) ------------------------------

    /**
     * A content template: its header (back, title, end actions; for a search, the search field
     * in place of the title), its body (lists, pane rows, message), which scrolls, and for panes
     * and messages their actions, which stay in view under the body.
     */
    content(t) {
      const box = document.createElement("div");
      box.className = "carapp-content";
      const hdr = t.header || null;
      const title = hdr ? hdr.title : t.title;
      const start = hdr ? hdr.startHeaderAction : t.headerAction;
      const ends = hdr ? hdr.endHeaderActions : null;
      const search = t.$ === "SearchTemplate";
      if (search || this.text(title).trim() || start || (ends && ends.length)) {
        const h = this.header(title, start, ends);
        if (search) h.querySelector(".carapp-header-title").replaceWith(this.searchField(t));
        box.appendChild(h);
      }
      const body = document.createElement("div");
      body.className = "carapp-body";
      box.appendChild(body);
      if (t.isLoading || t.loading) {
        body.appendChild(this.loading());
        return box;
      }
      switch (t.$) {
        case "ListTemplate": {
          if (t.singleList) body.appendChild(this.itemList(t.singleList));
          // while driving, the sections share one limit (the host counts every row it shows)
          let budget = this.restricted ? this.limit("content_limit_list", 6) : Infinity;
          for (const sec of t.sectionedLists || []) {
            if (budget <= 0) break;
            const h = document.createElement("div");
            h.className = "carapp-section-header";
            this.setText(h, sec.header);
            body.appendChild(h);
            const n = ((sec.itemList && sec.itemList.items) || []).length;
            body.appendChild(this.itemList(sec.itemList, { limit: Math.min(budget, n) || budget }));
            budget -= n;
          }
          break;
        }
        case "GridTemplate": {
          const g = this.itemList(t.singleList, { limit: this.limit("content_limit_grid", 6) });
          g.classList.add("grid");
          body.appendChild(g);
          break;
        }
        case "RoutePreviewNavigationTemplate": {
          // routes: the chosen one highlighted, with the action that starts it inside it
          const list = t.itemList;
          if (list && list.onSelectedDelegate) {
            body.appendChild(this.itemList(list, { routes: true, go: t.navigateAction, limit: this.limit("content_limit_route_list", 3) }));
          } else {
            if (list) body.appendChild(this.itemList(list, { limit: this.limit("content_limit_route_list", 3) }));
            if (t.navigateAction) box.appendChild(this.actionBar([t.navigateAction]));
          }
          break;
        }
        case "PlaceListNavigationTemplate":
        case "PlaceListMapTemplate":
          if (t.itemList) body.appendChild(this.itemList(t.itemList, { limit: this.limit("content_limit_place_list", 6) }));
          break;
        case "MapTemplate":
          // a MapTemplate holds a pane or (car API 6+) an item list
          if (t.itemList) {
            body.appendChild(this.itemList(t.itemList));
            break;
          }
        // falls through
        case "PaneTemplate": {
          const pane = t.pane || {};
          box.classList.add("pane");
          if (pane.isLoading || pane.loading) {
            body.appendChild(this.loading());
            break;
          }
          if (pane.image) {
            const im = this.icon(pane.image, "--pane-image");
            if (im) {
              im.classList.add("pane-image");
              body.appendChild(im);
            }
          }
          // a pane is a details page: its first row is the subject, the others are sections
          // (a heading and its lines), not tappable list rows
          const rows = document.createElement("div");
          rows.className = "carapp-pane-rows";
          let list = pane.rows || [];
          if (this.restricted) list = list.slice(0, this.limit("content_limit_pane", 4));
          for (const r of list) rows.appendChild(this.row(r));
          body.appendChild(rows);
          if (pane.actions && pane.actions.length) box.appendChild(this.actionBar(pane.actions));
          break;
        }
        case "MessageTemplate":
        case "LongMessageTemplate": {
          box.classList.add("message");
          const msg = document.createElement("div");
          msg.className = "carapp-message";
          const ic = this.icon(t.icon, "--message-image");
          if (ic) msg.appendChild(ic);
          const m = document.createElement("div");
          m.className = "carapp-message-text";
          this.setText(m, t.message);
          msg.appendChild(m);
          if (t.actions && t.actions.length) msg.appendChild(this.actionBar(t.actions));
          body.appendChild(msg);
          break;
        }
        case "SearchTemplate":
          if (t.itemList) body.appendChild(this.itemList(t.itemList));
          break;
        default: {
          const unk = document.createElement("div");
          unk.className = "carapp-message-text";
          unk.textContent = t.$;
          body.appendChild(unk);
        }
      }
      return box;
    }

    /**
     * Actions under a pane or a message: the ones with a title share the width (the primary one
     * filled), icon-only ones are round buttons beside them.
     */
    actionBar(actions) {
      const acts = document.createElement("div");
      acts.className = "carapp-actions";
      for (const a of actions) {
        const b = this.action(a, false);
        if (!a.title) b.classList.add("round");
        acts.appendChild(b);
      }
      return acts;
    }

    /**
     * The search field of a SearchTemplate: a pill with a magnifier, typed with the device's own
     * keyboard. While the car moves without keyboard input (UX restriction NO_KEYBOARD) it is
     * off and says so.
     */
    searchField(t) {
      const wrap = document.createElement("label");
      wrap.className = "carapp-search-box";
      const ns = "http://www.w3.org/2000/svg";
      const svg = document.createElementNS(ns, "svg");
      svg.setAttribute("viewBox", "0 0 24 24");
      svg.setAttribute("class", "carapp-search-glyph");
      const p = document.createElementNS(ns, "path");
      p.setAttribute("d", "M10.5 4a6.5 6.5 0 1 1 0 13 6.5 6.5 0 0 1 0-13zM15.5 15.5 20 20");
      p.setAttribute("fill", "none");
      p.setAttribute("stroke", "currentColor");
      p.setAttribute("stroke-width", "2");
      p.setAttribute("stroke-linecap", "round");
      svg.appendChild(p);
      wrap.appendChild(svg);
      const bar = document.createElement("input");
      bar.className = "carapp-search";
      bar.type = "search";
      bar.dataset.hint = this.text(t.searchHint);
      if (t.initialSearchText) bar.value = this.text(t.initialSearchText);
      // the callback id lives on the element: a later SearchTemplate keeps this input (see
      // updateSearch) and only gives it the new id
      const cb = t.searchCallbackDelegate;
      if (cb) bar.dataset.cb = cb.$cb;
      bar.enterKeyHint = "search";
      bar.dir = "auto";
      this.applySearchRestriction(bar);
      wrap.classList.toggle("off", bar.disabled);
      bar.addEventListener("input", () => {
        if (bar.dataset.cb) this.ctx.send({ type: "search", cb: Number(bar.dataset.cb), text: bar.value, submit: false });
      });
      bar.addEventListener("keydown", (e) => {
        if (e.key === "Enter" && bar.dataset.cb) {
          this.ctx.send({ type: "search", cb: Number(bar.dataset.cb), text: bar.value, submit: true });
          bar.blur();
        }
      });
      wrap.appendChild(bar);
      return wrap;
    }

    /** the search field on or off by the keyboard restriction, with the matching hint */
    applySearchRestriction(bar) {
      const off = !!(this.restrictions && this.restrictions.noKeyboard);
      bar.disabled = off;
      bar.placeholder = off ? "Typing is off while driving" : bar.dataset.hint || "";
      if (bar.parentNode) bar.parentNode.classList.toggle("off", off);
      if (off) bar.blur();
    }

    // ---- navigation ---------------------------------------------------------------------------------

    /** RoutingInfo in the steps card (steps_card_container: detailed step, divider, compact step) */
    stepsCard(info) {
      const card = this.card("steps");
      if (!info) return card;
      if (info.isLoading || info.loading) {
        card.appendChild(this.loading());
        return card;
      }
      if (info.$ === "MessageInfo") {
        const m = document.createElement("div");
        m.className = "carapp-nav-message";
        const ic = this.icon(info.image, "--nav-large-image");
        if (ic) m.appendChild(ic);
        const t = document.createElement("div");
        t.className = "carapp-nav-title";
        this.setText(t, info.title);
        m.appendChild(t);
        if (info.text) {
          const s = document.createElement("div");
          s.className = "carapp-row-text";
          this.setText(s, info.text);
          m.appendChild(s);
        }
        card.appendChild(m);
        return card;
      }
      const step = info.currentStep;
      if (step) {
        const d = document.createElement("div");
        d.className = "carapp-step detailed";
        const ic = this.maneuverIcon(step.maneuver, "--nav-large-image", "#ffffff");
        if (ic) {
          const tile = document.createElement("div");
          tile.className = "carapp-maneuver";
          tile.appendChild(ic);
          d.appendChild(tile);
        }
        const col = document.createElement("div");
        col.className = "carapp-step-text";
        const dist = document.createElement("div");
        dist.className = "carapp-nav-title";
        dist.textContent = this.distance(info.currentDistance);
        col.appendChild(dist);
        const cue = document.createElement("div");
        cue.className = "carapp-step-cue";
        this.setText(cue, step.cue);
        col.appendChild(cue);
        d.appendChild(col);
        card.appendChild(d);
        if (step.lanesImage) {
          const lanes = this.icon(step.lanesImage, "--nav-large-image");
          if (lanes) {
            lanes.classList.add("lanes");
            card.appendChild(lanes);
          }
        }
      }
      const next = info.nextStep;
      if (next) {
        const div = document.createElement("div");
        div.className = "carapp-divider";
        card.appendChild(div);
        const c = document.createElement("div");
        c.className = "carapp-step compact";
        const lead = document.createElement("span");
        lead.className = "carapp-row-text";
        lead.textContent = "Then";
        c.appendChild(lead);
        const ic = this.maneuverIcon(next.maneuver, "--nav-small-image", "currentColor");
        if (ic) c.appendChild(ic);
        const cue = document.createElement("span");
        cue.className = "carapp-row-text";
        this.setText(cue, next.cue);
        c.appendChild(cue);
        card.appendChild(c);
      }
      if (info.junctionImage) {
        const j = this.icon(info.junctionImage, "--junction-size");
        if (j) {
          j.classList.add("junction");
          card.appendChild(j);
        }
      }
      return card;
    }

    /** TravelEstimate in the travel estimate card (travel_estimate_view) */
    travelCard(est) {
      const card = this.card("travel");
      if (!est) return card;
      // the travel estimate as car hosts lay it out: the time left large, then the arrival time
      // and the distance left; the host's end-drive control in front while the app navigates
      // (NavigationManagerCallback.onStopNavigation)
      const row = document.createElement("div");
      row.className = "carapp-travel";
      if (this.navigating) {
        const end = document.createElement("div");
        end.className = "carapp-end carapp-clickable";
        end.dataset.act = "stop-nav";
        end.setAttribute("aria-label", "End drive");
        const x = document.createElement("span");
        x.className = "carapp-glyph";
        x.style.setProperty("--icon-size", "var(--action-icon)");
        x.textContent = "\ue5cd";
        end.appendChild(x);
        row.appendChild(end);
      }
      const col = document.createElement("div");
      col.className = "carapp-travel-text";
      if (est.remainingTimeSeconds >= 0) {
        const t = document.createElement("div");
        t.className = "carapp-nav-title";
        t.textContent = formatDuration(est.remainingTimeSeconds < 60 ? 60 : est.remainingTimeSeconds);
        const c = this.carColor(est.remainingTimeColor, null);
        if (c) t.style.color = c;
        col.appendChild(t);
      }
      const sub = document.createElement("div");
      sub.className = "carapp-row-text";
      const at = est.arrivalTimeAtDestination;
      if (at && at.timeSinceEpochMillis) {
        const d = new Date(at.timeSinceEpochMillis + (at.zoneOffsetSeconds || 0) * 1000);
        sub.appendChild(document.createTextNode(String(d.getUTCHours()).padStart(2, "0") + ":" + String(d.getUTCMinutes()).padStart(2, "0")));
      }
      if (est.remainingDistance) {
        if (sub.textContent) sub.appendChild(document.createTextNode(" \u2022 "));
        const dd = document.createElement("span");
        dd.textContent = this.distance(est.remainingDistance);
        const c = this.carColor(est.remainingDistanceColor, null);
        if (c) dd.style.color = c;
        sub.appendChild(dd);
      }
      if (sub.textContent) col.appendChild(sub);
      row.appendChild(col);
      card.appendChild(row);
      return card;
    }

    // ---- top level -------------------------------------------------------------------------------------

    render(wrapper) {
      const t = wrapper && wrapper.template ? wrapper.template : wrapper;
      this.lastWrapper = wrapper;
      if (t && t.$ === "SearchTemplate" && this.updateSearch(t)) return;
      if (!t) {
        this.root.textContent = "";
        return;
      }
      const layer = document.createElement("div");
      layer.className = "carapp-layer " + t.$;
      const mapStrip = (t.mapController && t.mapController.mapActionStrip) || t.mapActionStrip;
      this.panDelegate = (t.mapController && t.mapController.panModeDelegate) || t.panModeDelegate ||
        (t.contentTemplate && t.contentTemplate.panModeDelegate) || null;
      if (this.panMode && this.panDelegate) {
        // pan mode (AOSP host): only the map and its action strip, and the way out of it
        layer.classList.add("pan-mode");
        const exit = document.createElement("div");
        exit.className = "carapp-strip top";
        const b = document.createElement("div");
        b.className = "carapp-fab carapp-clickable";
        b.dataset.act = "pan-off";
        const icon = document.createElement("span");
        icon.className = "carapp-std-icon back";
        b.appendChild(icon);
        exit.appendChild(b);
        layer.appendChild(exit);
        const strip = this.actionStrip(mapStrip, "map");
        for (const p of strip.querySelectorAll(".pan")) p.remove();
        layer.appendChild(strip);
        this.show(layer);
        return;
      }
      if (!this.panDelegate) this.panMode = false;
      if (!this.navigating) this.lastRouting = null;
      if (this.restricted) layer.classList.add("restricted");
      switch (t.$) {
        case "NavigationTemplate":
          if (t.navigationInfo) {
            layer.appendChild(this.stepsCard(t.navigationInfo));
            if (this.navigating) this.lastRouting = t.navigationInfo;
          }
          if (t.destinationTravelEstimate) layer.appendChild(this.travelCard(t.destinationTravelEstimate));
          layer.appendChild(this.actionStrip(t.actionStrip, "top"));
          // over the bare map the zoom and recentre buttons fold into one
          layer.appendChild(this.actionStrip(mapStrip, "map", { fold: true }));
          break;
        case "MapWithContentTemplate":
        case "MapTemplate":
        case "PlaceListMapTemplate":
        case "PlaceListNavigationTemplate":
        case "RoutePreviewNavigationTemplate": {
          const inner = t.contentTemplate || t;
          // a MapTemplate without a pane is the bare map
          const bare = inner.$ === "MapTemplate" && !inner.pane && !inner.header && !inner.itemList;
          if (!bare) {
            const card = this.card("content");
            card.appendChild(this.content(inner));
            layer.appendChild(card);
            // while the app navigates, the next turn stays in view as a compact pill
            if (this.navigating && this.lastRouting) layer.appendChild(this.routingPill(this.lastRouting));
          }
          layer.appendChild(this.actionStrip(t.actionStrip || inner.actionStrip, "top", { skipIcons: this.headerIcons(inner) }));
          layer.appendChild(this.actionStrip(mapStrip, "map", { fold: bare }));
          break;
        }
        default: {
          // full-screen templates (lists, panes, messages, search, grids): the host draws them on
          // its plain background, not over the map; long lists page with the arrows at the left
          layer.classList.add("fullscreen");
          layer.appendChild(this.pager());
          layer.appendChild(this.content(t));
          layer.appendChild(this.actionStrip(t.actionStrip, "top", { skipIcons: this.headerIcons(t) }));
        }
      }
      // the app's text in its own direction (Hebrew, Arabic, ...): each text runs by its content
      for (const e of layer.querySelectorAll(".carapp-row-title,.carapp-row-text,.carapp-header-title,.carapp-step-cue,.carapp-message-text,.carapp-section-header,.carapp-action-title,.carapp-empty"))
        e.dir = textDir(e.textContent);
      this.show(layer);
    }

    /**
     * Puts the new layer on screen. A template of the same kind as the one shown (the app sends a
     * new one every second while navigating) updates the shown one in place: only what changed is
     * touched, so images are not reloaded and nothing flickers.
     */
    show(layer) {
      const old = this.root.firstElementChild;
      // the same screen updated (a new distance, a new list of results): in place; a new screen
      // (another template kind, another title): it comes in with the entry animation
      const key = screenKey(layer);
      if (old && old.dataset.screen === key) {
        layer.dataset.screen = key;
        morph(old, layer);
        old.classList.remove("enter");
        this.afterShow();
        return;
      }
      layer.dataset.screen = key;
      if (old) layer.classList.add("enter");
      this.root.textContent = "";
      this.root.appendChild(layer);
      layer.addEventListener("animationend", () => layer.classList.remove("enter"), { once: true });
      // a new screen ends the previous screen's countdown
      if (this.countdown && this.countdown.screen !== key) this.stopCountdown(true);
      this.afterShow();
    }

    /** what depends on the laid-out screen: the paging arrows, the default action's countdown */
    afterShow() {
      const layer = this.root.firstElementChild;
      if (!layer) return;
      this.updatePager(layer);
      this.armCountdown(layer);
    }

    // ---- paging (full-screen lists) ----------------------------------------------------------

    /** the up and down arrows at the left of a full-screen list (the host's paged list scrollbar) */
    pager() {
      const p = document.createElement("div");
      p.className = "carapp-pager";
      for (const dir of ["up", "down"]) {
        const b = document.createElement("div");
        b.className = "carapp-page carapp-clickable " + dir;
        b.dataset.act = "page-" + dir;
        b.setAttribute("aria-label", dir === "up" ? "Page up" : "Page down");
        p.appendChild(b);
      }
      return p;
    }

    /** the arrows shown while the list is longer than the screen, each off at its end */
    updatePager(layer) {
      const pager = layer.querySelector(":scope > .carapp-pager");
      const body = layer.querySelector(":scope > .carapp-content > .carapp-body");
      if (!pager || !body) return;
      const update = () => {
        const more = body.scrollHeight > body.clientHeight + 2;
        pager.classList.toggle("shown", more);
        pager.querySelector(".up").classList.toggle("disabled", body.scrollTop <= 1);
        pager.querySelector(".down").classList.toggle("disabled", body.scrollTop + body.clientHeight >= body.scrollHeight - 1);
      };
      if (!body._paged) {
        body._paged = true;
        body.addEventListener("scroll", update, { passive: true });
      }
      update();
      requestAnimationFrame(update);
    }

    // ---- the default action's countdown ------------------------------------------------------

    /**
     * Action.FLAG_DEFAULT: the host runs the template's default action by itself after a while,
     * unless the driver touches the panel first. The button fills up meanwhile; a screen updated
     * in place keeps its running countdown.
     */
    armCountdown(layer) {
      const b = layer.querySelector(".default-action");
      if (!b) return;
      const screen = layer.dataset.screen;
      const title = b.textContent;
      if (this.countdownDone && this.countdownDone === screen + "|" + title) return;
      if (!this.countdown || this.countdown.screen !== screen || this.countdown.title !== title) {
        this.stopCountdown(true);
        const c = { screen: screen, title: title, start: performance.now() };
        c.timer = setTimeout(() => {
          const el = this.root.querySelector(".default-action.counting");
          this.countdown = null;
          this.countdownDone = screen + "|" + title;
          if (el && el.dataset.cb !== undefined) this.ctx.send({ type: "click", cb: Number(el.dataset.cb) });
        }, DEFAULT_ACTION_MS);
        this.countdown = c;
      }
      const elapsed = performance.now() - this.countdown.start;
      b.classList.add("counting");
      b.style.setProperty("--countdown-ms", DEFAULT_ACTION_MS + "ms");
      b.style.setProperty("--countdown-delay", -elapsed + "ms");
    }

    /** the countdown off: the driver touched the panel (the action stays, without its timer) */
    stopCountdown(silent) {
      if (!this.countdown) return;
      clearTimeout(this.countdown.timer);
      if (!silent) this.countdownDone = this.countdown.screen + "|" + this.countdown.title;
      this.countdown = null;
      for (const el of this.root.querySelectorAll(".default-action.counting")) el.classList.remove("counting");
    }

    // ---- driving restrictions ----------------------------------------------------------------

    /**
     * The car's UX restrictions (the host's "restrictions" message): while they apply, lists show
     * no more than the host's content limits and the search field takes no typing.
     */
    setRestrictions(r) {
      this.restrictions = r;
      this.restricted = !!(r && r.active && r.maxItems > 0);
      for (const bar of this.root.querySelectorAll("input.carapp-search")) this.applySearchRestriction(bar);
      if (this.lastWrapper) this.render(this.lastWrapper);
    }

    /** the next step as a one-line pill while a panel covers the steps card (navigating) */
    routingPill(info) {
      const pill = document.createElement("div");
      pill.className = "carapp-pill";
      const strong = document.createElement("b");
      const cue = document.createElement("span");
      if (info.$ === "MessageInfo") {
        this.setText(strong, info.title);
        if (info.text) this.setText(cue, info.text);
      } else {
        strong.textContent = this.distance(info.currentDistance);
        if (info.currentStep) this.setText(cue, info.currentStep.cue);
      }
      pill.appendChild(strong);
      if (cue.textContent) {
        const dot = document.createElement("span");
        dot.className = "dot";
        dot.textContent = "·";
        pill.appendChild(dot);
        pill.appendChild(cue);
        cue.dir = textDir(cue.textContent);
      }
      return pill;
    }

    // ---- toasts and alerts ----------------------------------------------------------------------

    /** A toast: a short message at the bottom (LENGTH_SHORT 2 s, LENGTH_LONG 3.5 s, as Android). */
    toast(container, text, long) {
      const el = document.createElement("div");
      el.className = "carapp-toast";
      el.textContent = text;
      el.dir = textDir(text);
      for (const old of container.querySelectorAll(".carapp-toast")) old.remove();
      container.appendChild(el);
      setTimeout(() => el.remove(), long ? 3500 : 2000);
    }

    /**
     * An alert (car API 5): a card at the top with its icon, title, subtitle and actions, for
     * durationMillis. Running out: AlertCallback.onCancel(REASON_TIMEOUT); the close button:
     * onCancel(REASON_USER_ACTION); an action: its callback, then onDismiss.
     */
    alert(container, a) {
      for (const old of container.querySelectorAll(".carapp-alert")) old.remove();
      const card = document.createElement("div");
      card.className = "carapp-alert";
      card.dataset.id = a.id;
      if (a.callbackDelegate) card.dataset.cb = a.callbackDelegate.$cb;
      const top = document.createElement("div");
      top.className = "carapp-alert-top";
      const ic = this.icon(a.icon, "--nav-small-image", "currentColor");
      if (ic) top.appendChild(ic);
      const texts = document.createElement("div");
      texts.className = "carapp-row-body";
      const title = document.createElement("div");
      title.className = "carapp-row-title";
      this.setText(title, a.title);
      title.dir = textDir(title.textContent);
      texts.appendChild(title);
      if (a.subtitle) {
        const sub = document.createElement("div");
        sub.className = "carapp-row-text";
        this.setText(sub, a.subtitle);
        sub.dir = textDir(sub.textContent);
        texts.appendChild(sub);
      }
      top.appendChild(texts);
      const close = document.createElement("div");
      close.className = "carapp-action back carapp-clickable";
      close.dataset.act = "alert-close";
      const x = document.createElement("span");
      x.className = "carapp-glyph";
      x.style.setProperty("--icon-size", "var(--action-icon)");
      x.textContent = "\ue5cd";
      close.appendChild(x);
      top.appendChild(close);
      card.appendChild(top);
      if (a.actions && a.actions.length) {
        const acts = document.createElement("div");
        acts.className = "carapp-actions";
        for (const action of a.actions) {
          const b = this.action(action, false);
          if (b.dataset.act === "click") b.dataset.act = "alert-action";
          acts.appendChild(b);
        }
        card.appendChild(acts);
      }
      const ms = a.durationMillis > 0 ? a.durationMillis : 0;
      if (ms) {
        const bar = document.createElement("div");
        bar.className = "carapp-alert-timer";
        bar.style.animationDuration = ms + "ms";
        card.appendChild(bar);
        card._timer = setTimeout(() => this.finishAlert(card, 1 /* REASON_TIMEOUT */), ms);
      }
      container.appendChild(card);
    }

    /** The app took the alert back (AppManager.dismissAlert): it goes without a callback. */
    removeAlert(container, id) {
      for (const card of container.querySelectorAll(".carapp-alert")) {
        if (Number(card.dataset.id) === id) {
          clearTimeout(card._timer);
          card.remove();
        }
      }
    }

    finishAlert(card, how) {
      if (!card || !card.isConnected) return;
      clearTimeout(card._timer);
      if (card.dataset.cb !== undefined) {
        if (how === "dismiss") this.ctx.send({ type: "alertDismiss", cb: Number(card.dataset.cb) });
        else this.ctx.send({ type: "alertCancel", cb: Number(card.dataset.cb), reason: how });
      }
      card.remove();
    }

    /** pan mode on or off: the app hears it through the template's PanModeDelegate */
    setPanMode(on) {
      if (!this.panDelegate) return;
      this.panMode = on;
      this.ctx.send({ type: "pan", cb: this.panDelegate.$cb, on: on });
      if (this.lastWrapper) this.render(this.lastWrapper);
    }

    /**
     * A new SearchTemplate while one is shown (the app answers each typed letter with one): the
     * search field stays the same element, with what the driver typed, its focus and the open
     * keyboard; only the results under it are updated (in place).
     */
    updateSearch(t) {
      const input = this.root.querySelector(".carapp-layer.SearchTemplate input.carapp-search");
      if (!input) return false;
      const box = input.closest(".carapp-content");
      const fresh = this.content(t);
      const newInput = fresh.querySelector("input.carapp-search");
      const oldBody = box && box.querySelector(":scope > .carapp-body");
      const newBody = fresh.querySelector(":scope > .carapp-body");
      if (!newInput || !oldBody || !newBody) return false;
      input.dataset.cb = newInput.dataset.cb || "";
      input.dataset.hint = newInput.dataset.hint || "";
      this.applySearchRestriction(input);
      for (const e of newBody.querySelectorAll(".carapp-row-title,.carapp-row-text,.carapp-message-text,.carapp-empty"))
        e.dir = textDir(e.textContent);
      morph(oldBody, newBody);
      this.afterShow();
      return true;
    }

    /**
     * The part of the map the panels leave free, in display pixels: right of the cards on the
     * left, left of the floating strips (onVisibleAreaChanged for the app, which keeps its car
     * and route inside it).
     */
    visibleArea(width, height) {
      if (this.root.querySelector(".carapp-layer.fullscreen")) return null;
      // the panels' own margin around the area: what the app draws in it (its car, its
      // speedometer, its route) keeps clear of the screen edges and of the panels
      const margin = this.dimen("template_card_content_container_start_margin", 16);
      let left = margin;
      let top = margin;
      let right = width - margin;
      const bottom = height - margin;
      const rootBox = this.root.getBoundingClientRect();
      const scale = rootBox.width / width || 1;
      for (const c of this.root.querySelectorAll(".carapp-card")) {
        const b = c.getBoundingClientRect();
        if (b.width) left = Math.max(left, (b.right - rootBox.left) / scale + margin);
      }
      for (const s of this.root.querySelectorAll(".carapp-strip.map")) {
        const b = s.getBoundingClientRect();
        if (b.width) right = Math.min(right, (b.left - rootBox.left) / scale);
      }
      for (const s of this.root.querySelectorAll(".carapp-strip.top, .carapp-pill")) {
        const b = s.getBoundingClientRect();
        if (b.height) top = Math.max(top, (b.bottom - rootBox.top) / scale);
      }
      if (right - left < width * 0.25) left = Math.max(margin, right - width * 0.25);
      return { left: Math.round(left), top: Math.round(top), right: Math.round(right), bottom: Math.round(bottom) };
    }
  }

  global.TLCarAppRenderer = Renderer;
})(window);
