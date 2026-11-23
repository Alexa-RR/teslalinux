// teslalinux: the app's own settings in the settings page (Settings > Waze > Waze's settings, Car
// interface only). The app's car settings screens (its Car App Library templates: lists of rows
// with switches, choices and sub-screens) are drawn with the settings page's components
// (js/settings/settings-ui.js) instead of over the map, in the app's own words; every change goes
// to the app through its own callbacks (the same click, checked and selected messages the car
// panels send), and what is shown is always the app's latest template, so it stays right for any
// app version. carapp-controller.js opens them (the app's settings action), keeps their screens
// apart from the map's and pops them again with back when the page stops showing them; this file
// only draws: the settings screen in the group, a sub-screen the app pushes from it in a sheet
// (its close is back to the settings screen, its back arrow one screen back).
(function (global) {
  "use strict";

  // how long a change waits for the app's new template before the view shows the app's state
  // again (the app refused the change, or did not answer)
  const SETTLE_MS = 2500;

  /** an app text as a string (a CarText's own text; its spans add nothing to settings) */
  function text(t) {
    if (t === undefined || t === null) return "";
    if (typeof t === "object") return t.$ === "CarText" ? String(t.text || "") : "";
    return String(t);
  }

  /** CarColor as CSS, or null for the host's own colours (DEFAULT, and the car's white tints) */
  function carColor(c) {
    if (!c || c.type !== 0) {
      // the standard colours the host maps (CarColor.BLUE ... YELLOW)
      const named = { 2: "var(--blue)", 3: "var(--green)", 4: "var(--red)", 5: "var(--amber, #d18a00)", 7: "var(--amber, #d18a00)" };
      return c ? named[c.type] || null : null;
    }
    const v = c.color >>> 0;
    const r = (v >> 16) & 255;
    const g = (v >> 8) & 255;
    const b = v & 255;
    // white or near-white: a tint made for the car's dark screens, the page's own text colour here
    if (r > 230 && g > 230 && b > 230) return null;
    return "rgb(" + r + "," + g + "," + b + ")";
  }

  class AppSettingsView {
    /**
     * @param {object} car  the car interface's controller (TLCarApp)
     * @param {object} o    {onMap()}: the settings page closes to the map (a screen the app shows only there)
     */
    constructor(car, o) {
      this.car = car;
      this.o = o || {};
      this.UI = global.TAHtml.SettingsUI;
      this.state = null;
      this.sheet = null; // {p, depth, ours} while a sub-screen is open in a sheet
      this.settle = null;
      this.titles = {}; // depth -> the title of the row tapped to open the screen at that depth
      this.listener = (s) => this.onState(s);
      const UI = this.UI;
      this.body = UI.h("div", { class: "group-body" });
      this.el = UI.h(
        "section",
        { class: "group waze-app", "data-group": "wazeapp", "data-id": "waze.appsettings" },
        UI.h("h2", { class: "group-title" }, "Waze's settings"),
        UI.h("p", { class: "group-note" }, "Waze's own settings for the car, as Waze shows them. A change goes to Waze at once."),
        this.body,
      );
    }

    /** The group, with the app's settings opened (or the ones already open taken over). */
    element() {
      this.car.openAppSettings(this.listener);
      return this.el;
    }

    /** whether this view is still the one the controller tells (a section drawn again makes a new one) */
    current() {
      const s = this.car.appSettings;
      return Boolean(s && s.listener === this.listener);
    }

    onState(s) {
      this.state = s;
      if (this.raf) return;
      // several changes in one frame (icons classified, a template): one drawing
      this.raf = global.requestAnimationFrame(() => {
        this.raf = 0;
        this.draw();
      });
    }

    draw() {
      const s = this.state;
      const UI = this.UI;
      const h = UI.h;
      this.el.classList.toggle("is-busy", Boolean(s && s.busy));
      if (!s || s.state === "opening") {
        this.closeSheet();
        this.body.replaceChildren(h("div", { class: "waze-app-wait" }, h("span", { class: "spin" }), h("span", null, "Opening Waze's settings…")));
        return;
      }
      // another screen: a search field of the screen before does not carry over
      if (s.state !== "open" || s.depth !== this.searchDepth) this.search = null;
      this.searchDepth = s.state === "open" ? s.depth : 0;
      if (s.state !== "open") {
        this.closeSheet();
        const again = UI.button("Try again", () => this.car.openAppSettings(this.listener), "btn--quiet", "refresh");
        const why = {
          offline: "Waze is not connected right now (it may be starting).",
          noAction: "Waze opens its settings from its map, and something else is on its map now (a question, a list or a search). Answer or close it there: Waze's settings open here by themselves once its map is back.",
          navigating: "Waze offers its settings only when it is not guiding you. They open here by themselves when the drive ends, or end the drive now.",
          noAnswer: "Waze did not open its settings.",
          noNames: "Waze's settings need a newer Teslalinux runtime on this device (it comes with the next update).",
          closed: "Waze closed its settings.",
        }[s.state === "closed" ? "closed" : s.reason] || "Waze's settings are not available.";
        const buttons = s.reason === "noNames" ? []
          : s.reason === "navigating" ? [UI.button("End the drive", () => this.car.endDriveForAppSettings(), "btn--quiet", "close")]
          : [s.state === "closed" ? UI.button("Open them again", () => this.car.openAppSettings(this.listener), "btn--quiet", "settings") : again];
        if ((s.reason === "noAction" || s.reason === "navigating") && this.o.onMap) buttons.push(UI.button("Show the map", () => this.o.onMap(), "btn--quiet", "map"));
        this.body.replaceChildren(h("div", { class: "waze-app-note" }, UI.warnbox(why, "info_outline"), h("div", { class: "waze-app-buttons" }, buttons)));
        return;
      }
      if (s.depth === 1) {
        this.closeSheet();
        this.body.replaceChildren(...this.content(s.template, true));
        return;
      }
      // a sub-screen: in a sheet over the settings screen, which is drawn as it last was (its
      // callbacks are no longer the app's current ones, so it is not tappable under the scrim)
      if (s.first) this.body.replaceChildren(...this.content(s.first, false));
      this.showSheet(s);
    }

    /** the sheet of a sub-screen, opened or brought up to date */
    showSheet(s) {
      const UI = this.UI;
      const h = UI.h;
      const t = s.template.template || {};
      // a screen without a title of its own (a search) is named by the row that opened it
      const title = text((t.header && t.header.title) || t.title) || this.titles[s.depth] || "Waze";
      const nodes = [h("div", { class: "group-body" }, this.content(s.template, true))];
      if (s.depth > 2) {
        // deeper: one screen back, as the car's back would
        nodes.unshift(h("div", { class: "waze-app-back" }, UI.button("Back", () => this.car.appSettingsBack(), "btn--quiet", "chevron_left")));
      }
      if (this.sheet && this.sheet.p.sheet.isConnected) {
        const sh = this.sheet.p.sheet;
        const head = sh.querySelector(".sheet-head h2");
        head.textContent = title;
        head.dir = "auto";
        sh.querySelector(".sheet-body").replaceChildren(...nodes);
        sh.classList.toggle("is-busy", Boolean(s.busy));
        this.sheet.depth = s.depth;
        return;
      }
      const p = UI.openSheet({ title: title, body: nodes });
      p.sheet.classList.add("waze-app-sheet");
      p.sheet.querySelector(".sheet-head h2").dir = "auto";
      const mine = { p: p, depth: s.depth, ours: false };
      this.sheet = mine;
      p.then(() => {
        if (this.sheet === mine) this.sheet = null;
        // closed by the driver (its close button, the scrim, Escape): back to the settings
        // screen; closed by this view (the app went back itself), or by a newer view: nothing
        if (!mine.ours && this.current()) this.car.appSettingsToTop();
      });
    }

    closeSheet() {
      const sh = this.sheet;
      if (!sh) return;
      sh.ours = true;
      this.sheet = null;
      sh.p.finish(null);
    }

    /** a change sent to the app: unless its new template comes first, the app's state is shown again */
    sent() {
      global.clearTimeout(this.settle);
      this.settle = global.setTimeout(() => this.draw(), SETTLE_MS);
    }

    send(msg) {
      this.car.send(msg);
      this.sent();
    }

    /** the nodes of one of the app's screens; live: its callbacks are the newest (taps go to the app) */
    content(wrapper, live) {
      const UI = this.UI;
      const h = UI.h;
      const t = (wrapper && wrapper.template) || {};
      const out = [];
      if (t.isLoading || t.loading || (t.pane && (t.pane.isLoading || t.pane.loading))) {
        out.push(h("div", { class: "waze-app-wait" }, h("span", { class: "spin" }), h("span", null, "Waze is loading…")));
        return out;
      }
      switch (t.$) {
        case "ListTemplate": {
          if (t.singleList) out.push(...this.list(t.singleList, live));
          for (const sec of t.sectionedLists || []) {
            const head = text(sec.header);
            if (head) out.push(h("h3", { class: "waze-app-sub", dir: "auto" }, head));
            out.push(...this.list(sec.itemList, live));
          }
          break;
        }
        case "GridTemplate":
          out.push(...this.list(t.singleList, live));
          break;
        case "PaneTemplate":
        case "MapTemplate":
        case "MapWithContentTemplate": {
          const inner = t.$ === "MapWithContentTemplate" ? t.contentTemplate || {} : t;
          if (inner.$ === "ListTemplate" || inner.$ === "GridTemplate") return this.content({ template: inner }, live);
          if (inner.itemList) {
            out.push(...this.list(inner.itemList, live));
            break;
          }
          const pane = inner.pane || {};
          for (const r of pane.rows || []) out.push(this.row(r, live));
          if (pane.actions && pane.actions.length) out.push(this.actions(pane.actions, live));
          break;
        }
        case "MessageTemplate":
        case "LongMessageTemplate": {
          out.push(h("p", { class: "waze-app-message", dir: "auto" }, text(t.message)));
          if (t.actions && t.actions.length) out.push(this.actions(t.actions, live));
          break;
        }
        case "SearchTemplate": {
          // a list with a filter (the app's toll passes): the field filters through the app
          if (t.searchCallbackDelegate) out.push(this.searchField(t, live));
          if (t.itemList) out.push(...this.list(t.itemList, live));
          break;
        }
        case "SignInTemplate": {
          // the app's sign-in or notice screen: its words, and what the method offers here
          const m = t.signInMethod || {};
          if (text(t.instructions)) out.push(h("p", { class: "waze-app-message", dir: "auto" }, text(t.instructions)));
          if (m.$ === "PinSignInMethod" && text(m.pinCode)) out.push(h("p", { class: "waze-app-pin" }, text(m.pinCode)));
          else if (m.$ === "QRCodeSignInMethod" && m.uri) out.push(h("p", { class: "waze-app-message waze-app-uri" }, String(m.uri.uriString || m.uri)));
          else if (m.$ === "ProviderSignInMethod" && m.action) out.push(this.actions([m.action], live));
          else if (m.$ === "InputSignInMethod") out.push(UI.warnbox("Waze asks for this on its map.", "info_outline"));
          if (text(t.additionalText)) out.push(h("p", { class: "group-note", dir: "auto" }, text(t.additionalText)));
          if (t.actions && t.actions.length) out.push(this.actions(t.actions, live));
          break;
        }
        default:
          // a screen made for the car's map (a search, a route): shown there
          out.push(
            h(
              "div",
              { class: "waze-app-note" },
              UI.warnbox("Waze shows this on its map.", "info_outline"),
              this.o.onMap
                ? h("div", { class: "waze-app-buttons" }, UI.button("Show it on the map", () => {
                    this.car.endAppSettings();
                    this.o.onMap();
                  }, "btn--quiet", "map"))
                : null,
            ),
          );
      }
      // the screen's own actions (header end actions, an action strip): buttons after it
      const extra = [];
      const hdr = t.header;
      for (const a of (hdr && hdr.endHeaderActions) || []) extra.push(a);
      for (const a of (t.actionStrip && t.actionStrip.actions) || []) extra.push(a);
      const named = extra.filter((a) => text(a.title) || (a.icon && a.icon.icon));
      if (named.length) out.push(this.actions(named, live));
      return out;
    }

    /**
     * A SearchTemplate's field: what is typed goes to the app as it is typed (it filters its
     * list), Enter submits; the text survives the app's new templates (the field is reused).
     */
    searchField(t, live) {
      const UI = this.UI;
      const cb = t.searchCallbackDelegate.$cb;
      if (!this.search) {
        const input = UI.h("input", {
          type: "search",
          class: "settings-field-input",
          autocomplete: "off",
          spellcheck: "false",
          dir: "auto",
        });
        let timer = 0;
        input.addEventListener("input", () => {
          global.clearTimeout(timer);
          timer = global.setTimeout(() => this.car.send({ type: "search", cb: this.search.cb, text: input.value, submit: false }), 250);
        });
        input.addEventListener("keydown", (e) => {
          if (e.key !== "Enter") return;
          global.clearTimeout(timer);
          this.car.send({ type: "search", cb: this.search.cb, text: input.value, submit: true });
        });
        input.value = text(t.initialSearchText);
        this.search = { input: input, cb: cb, box: UI.h("div", { class: "pick-filter waze-app-search" }, input) };
      }
      this.search.cb = cb;
      this.search.input.placeholder = text(t.searchHint) || "Search";
      this.search.input.setAttribute("aria-label", this.search.input.placeholder);
      this.search.input.disabled = !live;
      return this.search.box;
    }

    /** An ItemList: a choice (a selectable list) as picker rows with a tick, else setting rows. */
    list(list, live) {
      const UI = this.UI;
      const h = UI.h;
      if (!list) return [];
      let items = list.items || [];
      // while the car moves, as many items as the car's host shows (its content limit)
      const r = this.car.restrictions;
      const cap = r && r.active && r.maxItems > 0 ? r.maxItems : 0;
      const capped = cap && items.length > cap;
      if (capped) items = items.slice(0, cap);
      const out = [];
      if (list.onSelectedDelegate) {
        const box = h("div", { class: "waze-app-list", role: "radiogroup" });
        items.forEach((item, i) => {
          const chosen = list.selectedIndex === i;
          const b = h(
            "button",
            { class: "pick", type: "button", role: "radio", "aria-checked": String(chosen), disabled: live ? null : true },
            this.iconEl(item.image),
            h("span", { class: "waze-app-pick-text" }, h("span", { dir: "auto" }, text(item.title)),
              (item.texts || []).map(text).filter((x) => x.trim()).map((x) => h("small", { dir: "auto" }, x))),
            UI.icon(chosen ? "check" : ""),
          );
          b.addEventListener("click", () => {
            for (const o of box.querySelectorAll(".pick")) {
              o.setAttribute("aria-checked", String(o === b));
              o.lastElementChild.textContent = o === b ? "check" : "";
            }
            this.send({ type: "selected", cb: list.onSelectedDelegate.$cb, index: i });
          });
          box.append(b);
        });
        out.push(box);
      } else {
        for (const item of items) out.push(this.row(item, live));
      }
      if (!(list.items || []).length && list.noItemsMessage) out.push(h("p", { class: "group-note", dir: "auto" }, text(list.noItemsMessage)));
      if (capped) out.push(h("p", { class: "group-note" }, "Waze shows the rest while the car is parked."));
      return out;
    }

    /** A Row (or GridItem) as a settings row: a switch, a sub-screen or an action, or plain text. */
    row(r, live) {
      const UI = this.UI;
      const h = UI.h;
      const title = text(r.title);
      const lines = (r.texts || []).map(text).filter((x) => x.trim());
      if (r.text) lines.push(text(r.text));
      // a row without a title, a switch or a tap: the app's heading over the rows after it
      if (!title.trim() && lines.length && !r.toggle && !r.onClickDelegate && !(r.actions || []).length)
        return h("h3", { class: "waze-app-sub", dir: "auto" }, lines.join(" "));
      const off = r.enabled === false || r.isEnabled === false || !live;
      let control = null;
      if (r.toggle) {
        const tg = r.toggle;
        const cb = tg.onCheckedChangeDelegate;
        control = UI.toggle(Boolean(tg.isChecked || tg.checked), (on) => {
          if (!cb) return false;
          this.send({ type: "checked", cb: cb.$cb, checked: on });
          return true;
        }, title);
        if (off || tg.enabled === false || tg.isEnabled === false || !cb) control.querySelector("input").disabled = true;
      } else if (r.onClickDelegate) {
        const cb = r.onClickDelegate.$cb;
        control = UI.tapValue("", () => {
          if (off) return;
          const s = this.state;
          if (s && s.depth) this.titles[s.depth + 1] = title;
          this.send({ type: "click", cb: cb });
        });
        if (off) control.disabled = true;
      }
      const acts = (r.actions || []).map((a) => this.actionButton(a, live && !off));
      if (acts.length) control = control ? [control, ...acts] : acts.length === 1 ? acts[0] : acts;
      const el = UI.row({
        title: [this.iconEl(r.image), h("span", { dir: "auto" }, title)],
        detail: lines.length ? h("span", { dir: "auto" }, lines.join("\n")) : null,
        control: control,
        dim: off && live,
      });
      el.classList.add("waze-app-row");
      return el;
    }

    actions(list, live) {
      return this.UI.h("div", { class: "waze-app-buttons" }, list.map((a) => this.actionButton(a, live)));
    }

    /** an Action as a button: its title (or its icon alone), primary when the app flags it */
    actionButton(a, live) {
      const UI = this.UI;
      const h = UI.h;
      const label = text(a.title);
      const b = h(
        "button",
        { class: "btn" + (a.flags & 1 ? " btn--primary" : ""), type: "button", "aria-label": label || null },
        this.iconEl(a.icon),
        label ? h("span", { dir: "auto" }, label) : null,
      );
      const cb = a.onClickDelegate && a.onClickDelegate.$cb;
      if (!live || cb === undefined || a.enabled === false || a.isEnabled === false) b.disabled = true;
      else b.addEventListener("click", () => this.send({ type: "click", cb: cb }));
      return b;
    }

    /** a CarIcon: the app's image, a single-colour glyph in the page's text colour (or the icon's own tint) */
    iconEl(carIcon) {
      const ic = carIcon && carIcon.icon;
      if (!ic || ic.icon === undefined) return null;
      const url = this.car.appIcon(ic.icon);
      if (!url) return null;
      const el = this.UI.h("span", { class: "waze-app-icon", "aria-hidden": "true" });
      if (this.car.appIconIsGlyph(ic.icon)) {
        el.classList.add("glyph");
        const tint = carColor(carIcon.tint);
        if (tint) el.style.backgroundColor = tint;
        el.style.webkitMaskImage = el.style.maskImage = "url(" + url + ")";
      } else {
        el.style.backgroundImage = "url(" + url + ")";
      }
      return el;
    }
  }

  global.TLCarAppSettingsView = AppSettingsView;
})(window);
