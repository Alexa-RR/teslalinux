// teslalinux: the settings page's building blocks (README.teslalinux.md, "Settings"). Every row,
// choice and sheet of js/settings/settings-controller.js is made here, styled by css/settings.css
// under #page-settings. Rules: a choice of 2-6 short options is a segmented control, a longer list
// a value that opens a picker sheet, on/off a switch, a level a slider, a look picture tiles; each
// row has its own status line (Saving, Saved, Not saved + Try again) so a failure never wipes the
// section; anything that restarts the app wears a chip before it is touched.
(function (global) {
  "use strict";

  const TAHtml = global.TAHtml;
  const doc = global.document;

  /** the element everything floating (sheets, bars, toasts, search results) is put in: #page-settings */
  let root = null;

  function h(tag, attrs) {
    const el = doc.createElement(tag);
    if (attrs) {
      for (const k in attrs) {
        const v = attrs[k];
        if (v == null || v === false) continue;
        if (k.startsWith("on") && typeof v === "function") el.addEventListener(k.slice(2), v);
        else if (k === "html") el.innerHTML = v;
        else el.setAttribute(k, v === true ? "" : v);
      }
    }
    for (let i = 2; i < arguments.length; i += 1) append(el, arguments[i]);
    return el;
  }

  function append(el, c) {
    if (c == null || c === false) return;
    if (Array.isArray(c)) {
      c.forEach((x) => append(el, x));
      return;
    }
    el.append(c instanceof global.Node ? c : doc.createTextNode(String(c)));
  }

  const icon = (name, cls) => h("span", { class: "icon" + (cls ? " " + cls : ""), "aria-hidden": "true" }, name);
  const wait = (ms) => new Promise((r) => global.setTimeout(r, ms));

  let busyCount = 0;
  /** the thin line under the header while anything is being saved */
  function busy(d) {
    busyCount = Math.max(0, busyCount + d);
    const line = doc.getElementById("settings-busyline");
    if (line) line.classList.toggle("on", busyCount > 0);
  }

  function toast(text) {
    if (!root) return;
    root.querySelectorAll(".toast").forEach((t) => t.remove());
    const t = h("div", { class: "toast", role: "status" }, text);
    root.append(t);
    global.setTimeout(() => t.remove(), 3200);
  }

  const CHIP = {
    restart: () => h("span", { class: "chip chip--restart", title: "Changing this restarts Waze" }, icon("restart_alt"), "Restarts Waze"),
    later: () => h("span", { class: "chip chip--later", title: "Takes effect when the device restarts" }, icon("schedule"), "At next device start"),
    plain: (t) => h("span", { class: "chip chip--plain" }, t),
    custom: (cls, ic, t) => h("span", { class: "chip " + cls }, ic ? icon(ic) : null, t),
  };

  /**
   * A setting row: {id, title, detail, more, chips, control, stack, dim, after}. The title, one line
   * of detail, an info button that opens the longer text (more) in place, the control on the right
   * (or below with stack), and a status line for this row only. A row whose only control is a
   * switch or a value that opens a picker is one big button: a tap anywhere on it acts.
   */
  function row(o) {
    const r = h("div", { class: "row" + (o.stack ? " row--stack" : "") + (o.dim ? " row--dim" : ""), "data-id": o.id || null });
    const main = h("div", { class: "row-main" });
    const title = h("div", { class: "row-title" }, h("span", { class: "row-name" }, o.title), o.chips || null);
    main.append(title);
    if (o.detail) main.append(h("div", { class: "row-detail" }, o.detail));
    let more = null;
    if (o.more) {
      title.append(icon("info_outline", "row-info"));
      more = h("div", { class: "row-more" }, [].concat(o.more).map((p) => h("p", null, p)));
      more.hidden = true;
      main.classList.add("has-more");
      main.setAttribute("role", "button");
      main.setAttribute("aria-expanded", "false");
      main.tabIndex = 0;
      const flip = () => {
        more.hidden = !more.hidden;
        main.classList.toggle("open", !more.hidden);
        main.setAttribute("aria-expanded", String(!more.hidden));
      };
      main.addEventListener("click", flip);
      main.addEventListener("keydown", (e) => {
        if (e.key === "Enter" || e.key === " ") {
          e.preventDefault();
          flip();
        }
      });
    }
    r.append(main);
    const control = o.control ? h("div", { class: "row-control" }, o.control) : null;
    if (o.stack) {
      if (more) r.append(more);
      if (control) r.append(control);
    } else {
      if (control) r.append(control);
      if (more) r.append(more);
    }
    r.append(h("div", { class: "row-status", "aria-live": "polite" }));
    if (o.after) append(r, o.after);
    const target = control && !o.more && control.childElementCount === 1 && control.firstElementChild.matches(".toggle, .row-tap")
      ? control.firstElementChild : null;
    if (target) {
      r.classList.add("is-tappable");
      const hit = target.matches(".toggle") ? target.querySelector("input") : target;
      r.addEventListener("click", (e) => {
        if (e.target.closest(".toggle, button, a, input, .confirm")) return;
        if (!hit.disabled) hit.click();
      });
    }
    return r;
  }

  /** a row's control placed after the fact (a button made after the row) */
  function setControl(r, control) {
    let c = r.querySelector(":scope > .row-control");
    if (!c) {
      c = h("div", { class: "row-control" });
      r.insertBefore(c, r.querySelector(":scope > .row-status"));
    }
    c.replaceChildren();
    append(c, control);
    return r;
  }

  /** the status line of a row: pending, ok (fades), err (with Try again), info, clear */
  function status(r) {
    const s = r.querySelector(":scope > .row-status");
    const set = (cls, kids) => {
      global.clearTimeout(s._t);
      s.className = "row-status " + cls;
      s.replaceChildren();
      append(s, kids);
    };
    return {
      pending: (t) => set("is-pending", [h("span", { class: "spin" }), h("span", null, t || "Saving…")]),
      ok: (t, keep) => {
        set("is-ok", [icon("check_circle"), h("span", null, t || "Saved")]);
        if (!keep) {
          s._t = global.setTimeout(() => {
            s.className = "row-status";
            s.replaceChildren();
          }, 2800);
        }
      },
      err: (t, retry) =>
        set("is-err", [icon("error_outline"), h("span", null, t), retry ? h("button", { class: "link", type: "button", onclick: retry }, "Try again") : null]),
      info: (t) => set("is-info", [icon("info_outline"), h("span", null, t)]),
      clear: () => {
        global.clearTimeout(s._t);
        s.className = "row-status";
        s.replaceChildren();
      },
    };
  }

  /** Runs a write with this row's feedback; resolves true when it was saved. */
  async function save(r, fn, okText, errText) {
    const st = status(r);
    st.pending();
    busy(1);
    try {
      await fn();
      st.ok(okText);
      return true;
    } catch (e) {
      const why = e && e.userMessage ? e.userMessage : errText || "Not saved: the device did not answer.";
      st.err(why, () => save(r, fn, okText, errText));
      return false;
    } finally {
      busy(-1);
    }
  }

  /** an Error whose text is meant for the row's status line */
  function userError(text) {
    const e = new Error(text);
    e.userMessage = text;
    return e;
  }

  /**
   * Segmented control: 2-6 short choices, all visible, one tap. Each option {value, label, icon,
   * glyph, caption, disabled, why}. onChange returning false puts the thumb back.
   */
  function segmented(opts, value, onChange, o) {
    o = o || {};
    const el = h("div", { class: "seg", role: "radiogroup", "aria-label": o.label || null, style: "--n:" + opts.length });
    el.append(h("span", { class: "seg-thumb", "aria-hidden": "true" }));
    let cur = opts.findIndex((x) => String(x.value) === String(value));
    const buttons = opts.map((x, i) => {
      const b = h(
        "button",
        { class: "seg-opt", type: "button", role: "radio", disabled: x.disabled || null, title: x.why || null },
        h("span", { class: "seg-row" }, x.icon ? icon(x.icon) : null, x.glyph || null, x.label ? h("span", null, x.label) : null),
        x.caption ? h("span", { class: "seg-cap" }, x.caption) : null,
      );
      b.addEventListener("click", () => pick(i));
      el.append(b);
      return b;
    });
    const place = (i) => {
      el.classList.toggle("is-none", i < 0);
      el.style.setProperty("--i", Math.max(0, i));
      buttons.forEach((b, j) => b.setAttribute("aria-checked", String(j === i)));
    };
    async function pick(i) {
      if (i === cur || el.classList.contains("is-busy")) return;
      const prev = cur;
      place(i);
      el.classList.add("is-busy");
      let ok = true;
      try {
        ok = (await onChange(opts[i].value, opts[i])) !== false;
      } catch (_e) {
        ok = false;
      } finally {
        el.classList.remove("is-busy");
      }
      if (ok) cur = i;
      else place(prev);
    }
    place(cur);
    el.setValue = (v) => {
      cur = opts.findIndex((x) => String(x.value) === String(v));
      place(cur);
    };
    return el;
  }

  /** Picture choices for looks (interface, panel style, map colours): {value, label, caption, svg}. */
  function tiles(opts, value, onChange) {
    const el = h("div", { class: "tiles", role: "radiogroup", style: "--n:" + opts.length });
    let cur = value;
    const set = (v) => els.forEach((b, i) => b.setAttribute("aria-checked", String(opts[i].value === v)));
    const els = opts.map((x) => {
      const b = h(
        "button",
        { class: "tile", type: "button", role: "radio", "aria-checked": String(x.value === value) },
        h("span", { class: "tile-pic", html: x.svg }),
        h("span", { class: "tile-name" }, h("span", null, x.label), icon("check_circle", "tick")),
        x.caption ? h("span", { class: "tile-cap" }, x.caption) : null,
      );
      b.addEventListener("click", async () => {
        if (x.value === cur) return;
        const prev = cur;
        set(x.value);
        let ok = true;
        try {
          ok = (await onChange(x.value)) !== false;
        } catch (_e) {
          ok = false;
        }
        if (ok) cur = x.value;
        else set(prev);
      });
      el.append(b);
      return b;
    });
    return el;
  }

  /** a switch; onChange returning false (or throwing) puts it back */
  function toggle(value, onChange, label) {
    const input = h("input", { type: "checkbox", role: "switch", "aria-label": label || null });
    input.checked = Boolean(value);
    input.addEventListener("change", async () => {
      input.disabled = true;
      let ok = true;
      try {
        ok = (await onChange(input.checked)) !== false;
      } catch (_e) {
        ok = false;
      } finally {
        input.disabled = false;
      }
      if (!ok) input.checked = !input.checked;
    });
    return h("label", { class: "toggle" }, input, h("span", { class: "toggle-track" }, h("span", { class: "toggle-knob" })));
  }

  /** Slider for levels: {min, max, step, value, unit, iconLow, label, marks, onChange}; shows the value while dragging, saves on release. */
  function slider(o) {
    const input = h("input", { type: "range", min: o.min, max: o.max, step: o.step, "aria-label": o.label || null });
    input.value = o.value;
    const val = h("span", { class: "slider-value" });
    const paint = () => {
      const p = ((input.value - o.min) / (o.max - o.min)) * 100;
      input.style.setProperty("--p", p + "%");
      val.textContent = input.value + (o.unit || "");
    };
    input.addEventListener("input", paint);
    input.addEventListener("change", () => o.onChange(Number(input.value)));
    paint();
    const wrap = h("span", { class: "slider-wrap" }, input);
    (o.marks || []).forEach((m) => {
      const pos = ((m.at - o.min) / (o.max - o.min)) * 100;
      wrap.append(h("span", { class: "slider-mark", style: "inset-inline-start:calc(19px + (100% - 38px) * " + pos / 100 + ")" }, m.label));
    });
    const el = h("div", { class: "slider" }, o.iconLow ? icon(o.iconLow) : null, wrap, val);
    el.input = input;
    return el;
  }

  /** A value that opens an editor or picker (long lists, text). */
  function tapValue(text, onTap, changed) {
    return h("button", { class: "row-tap" + (changed ? " is-changed" : ""), type: "button", onclick: onTap }, h("span", null, text), icon("chevron_right", "flip"));
  }

  /** a read-only value at the end of a row */
  const value = (text) => h("span", { class: "row-value" }, text);

  function button(label, onClick, cls, ic) {
    return h("button", { class: "btn" + (cls ? " " + cls : ""), type: "button", onclick: onClick || null }, ic ? icon(ic) : null, h("span", null, label));
  }

  /** A button that needs a second tap within 4 s, with the time left drawn in it. */
  function armed(label, armedLabel, action, cls) {
    const b = h("button", { class: "btn " + (cls || "btn--danger"), type: "button" }, label);
    let t = null;
    b.addEventListener("click", async () => {
      if (b.disabled) return;
      if (!b.classList.contains("armed")) {
        b.classList.add("armed");
        b.textContent = armedLabel;
        t = global.setTimeout(() => {
          b.classList.remove("armed");
          b.textContent = label;
        }, 4000);
        return;
      }
      global.clearTimeout(t);
      b.classList.remove("armed");
      b.textContent = label;
      await action(b);
    });
    return b;
  }

  function group(title, kids, note, id) {
    return h(
      "section",
      { class: "group", "data-group": id || null },
      title ? h("h2", { class: "group-title" }, title) : null,
      note ? h("p", { class: "group-note" }, note) : null,
      h("div", { class: "group-body" }, kids),
    );
  }

  /** read-only status: [[label, value, "ok"|"warn"|"bad"]], falsy entries left out */
  function facts(list) {
    return h(
      "dl",
      { class: "facts" },
      list.filter(Boolean).map((f) => h("div", { class: "fact" }, h("dt", null, f[0]), h("dd", { class: f[2] || null }, f[1]))),
    );
  }

  /**
   * A signal strength drawn as rising bars filled up to its level, as a phone's status bar shows it
   * (the words stay for screen readers): green from 3 of 5, amber at 2, red below.
   */
  function signalBars(level, max) {
    const n = max || 5;
    const lv = Math.max(0, Math.min(n, Math.round(Number(level) || 0)));
    const tone = lv >= Math.ceil(n * 0.6) ? "ok" : lv >= Math.ceil(n * 0.4) ? "warn" : "bad";
    const words = lv + " of " + n + " bars";
    const el = h("span", { class: "sigbars sigbars--" + tone, role: "img", "aria-label": words, title: words });
    for (let i = 1; i <= n; i += 1) el.append(h("span", { class: "sigbar" + (i <= lv ? " on" : ""), style: "--h:" + (0.28 + (0.72 * i) / n).toFixed(3) }));
    return el;
  }

  function warnbox(text, ic) {
    return h("div", { class: "warnbox", role: "note" }, icon(ic || "warning"), h("span", null, text));
  }

  function calm(text) {
    return h("div", { class: "calm" }, icon("check_circle"), h("span", null, text));
  }

  /** a "Show more" button and its body: [button, body] */
  function disclosure(label, body, open) {
    const b = h("button", { class: "disclosure", type: "button", "aria-expanded": String(Boolean(open)) }, h("span", null, label), icon("expand_more"));
    const wrap = h("div", { class: "disclosure-body" }, body);
    wrap.hidden = !open;
    b.addEventListener("click", () => {
      wrap.hidden = !wrap.hidden;
      b.setAttribute("aria-expanded", String(!wrap.hidden));
    });
    return [b, wrap];
  }

  function listItem(ic, name, detail, end) {
    return h("div", { class: "list-item" }, icon(ic), h("div", null, h("div", { class: "list-name" }, name), detail ? h("div", { class: "list-detail" }, detail) : null), end || null);
  }

  /* ---------- sheets: text editors and long-list pickers ---------- */
  let sheetFinish = null;
  function openSheet(o) {
    closeSheet();
    const scrim = h("div", { class: "scrim", onclick: () => finish(null) });
    const body = h("div", { class: "sheet-body" }, o.body);
    const foot = o.actions ? h("div", { class: "sheet-foot" }, o.actions) : null;
    const sheet = h(
      "div",
      { class: "sheet", role: "dialog", "aria-modal": "true", "aria-label": o.title },
      h("div", { class: "sheet-head" }, h("h2", null, o.title), h("button", { class: "btn btn--quiet", type: "button", "aria-label": "Close", onclick: () => finish(null) }, icon("close"))),
      body,
      foot,
    );
    let resolve;
    const p = new Promise((r) => (resolve = r));
    function finish(v) {
      scrim.remove();
      sheet.remove();
      doc.removeEventListener("keydown", esc);
      sheetFinish = null;
      resolve(v);
    }
    const esc = (e) => {
      if (e.key === "Escape") finish(null);
    };
    doc.addEventListener("keydown", esc);
    (root || doc.body).append(scrim, sheet);
    sheetFinish = finish;
    p.finish = finish;
    p.sheet = sheet;
    return p;
  }

  function closeSheet() {
    if (sheetFinish) sheetFinish(null);
  }

  /** a text editor sheet: {title, label, value, type, placeholder, maxLength, help, okLabel, validate, transform}; resolves the text or null */
  function editText(o) {
    const input = h("input", {
      type: o.type || "text",
      placeholder: o.placeholder || "",
      maxlength: o.maxLength || null,
      autocomplete: o.type === "password" ? "new-password" : "off",
      spellcheck: "false",
      autocapitalize: "off",
      autocorrect: "off",
      id: "settings-sheet-input",
      class: "settings-field-input",
    });
    input.value = o.value || "";
    const err = h("div", { class: "field-err", "aria-live": "assertive" });
    const ok = h("button", { class: "btn btn--primary", type: "button" }, o.okLabel || "Done");
    const p = openSheet({
      title: o.title,
      body: [h("div", { class: "field" }, h("label", { for: "settings-sheet-input" }, o.label || o.title), input, o.help ? h("div", { class: "field-help" }, o.help) : null, err)],
      actions: [h("button", { class: "btn", type: "button", onclick: () => p.finish(null) }, "Cancel"), ok],
    });
    const submit = () => {
      const v = o.transform ? o.transform(input.value) : input.value;
      const why = o.validate ? o.validate(v) : "";
      if (why) {
        err.textContent = why;
        input.classList.add("invalid");
        input.focus();
        return;
      }
      p.finish(v);
    };
    ok.addEventListener("click", submit);
    input.addEventListener("keydown", (e) => {
      if (e.key === "Enter") submit();
    });
    if (o.transform) {
      input.addEventListener("input", () => {
        input.value = o.transform(input.value);
      });
    }
    global.setTimeout(() => input.focus(), 50);
    return p;
  }

  /** a picker sheet with a filter from 9 options: {title, value, options: [{value, label, small}]}; resolves the value or null */
  function pickFrom(o) {
    const list = h("div", { role: "listbox" });
    const draw = (q) => {
      list.replaceChildren();
      const ql = (q || "").toLowerCase();
      o.options
        .filter((x) => !ql || (x.label + " " + x.value + " " + (x.small || "")).toLowerCase().includes(ql))
        .forEach((x) => {
          const chosen = String(x.value) === String(o.value);
          list.append(
            h(
              "button",
              { class: "pick", type: "button", role: "option", "aria-selected": String(chosen), onclick: () => p.finish(x.value) },
              h("span", null, x.label, x.small ? h("small", null, "  " + x.small) : null),
              chosen ? icon("check") : null,
            ),
          );
        });
      if (!list.children.length) list.append(h("p", { class: "results-empty" }, "Nothing matches “" + q + "”."));
    };
    const filter = o.options.length > 8
      ? h("div", { class: "pick-filter" }, h("input", { type: "search", class: "settings-field-input", placeholder: "Filter", "aria-label": "Filter", autocomplete: "off", spellcheck: "false", oninput: (e) => draw(e.target.value) }))
      : null;
    const p = openSheet({ title: o.title, body: [filter, list] });
    draw("");
    const chosen = list.querySelector('[aria-selected="true"]');
    if (chosen) global.setTimeout(() => chosen.scrollIntoView({ block: "center" }), 0);
    return p;
  }

  /* ---------- small pictures for the look choices (our own drawings) ---------- */
  const SVG = {
    map: (route) =>
      '<rect width="120" height="68" fill="var(--map-land)"/><path d="M-4 50 C30 44 50 60 124 30" stroke="var(--map-road)" stroke-width="7" fill="none"/><path d="M40 -4 L58 72" stroke="var(--map-road)" stroke-width="5" fill="none"/><rect x="74" y="44" width="30" height="18" rx="4" fill="var(--map-park)"/>' +
      (route ? '<path d="M14 72 C24 52 40 48 56 40 S84 22 96 10" stroke="' + route + '" stroke-width="4" fill="none" stroke-linecap="round"/>' : ""),
    phone: () =>
      '<svg viewBox="0 0 120 68" preserveAspectRatio="xMidYMid slice">' + SVG.map("#8a5cf0") +
      '<rect x="6" y="5" width="108" height="11" rx="5.5" fill="var(--surface)"/><rect x="6" y="46" width="108" height="22" rx="6" fill="var(--surface)"/><rect x="12" y="52" width="46" height="4" rx="2" fill="var(--ink-3)"/><rect x="12" y="59" width="30" height="3" rx="1.5" fill="var(--ink-3)" opacity=".6"/><circle cx="104" cy="31" r="5" fill="var(--surface)"/></svg>',
    car: () =>
      '<svg viewBox="0 0 120 68" preserveAspectRatio="xMidYMid slice">' + SVG.map("#3b6cf0") +
      '<rect x="5" y="5" width="40" height="30" rx="6" fill="var(--surface)"/><path d="M14 22 v-8 l5 -4 l5 4 v8" stroke="var(--blue)" stroke-width="3" fill="none" stroke-linejoin="round"/><rect x="28" y="12" width="12" height="4" rx="2" fill="var(--ink-2)"/><rect x="28" y="20" width="9" height="3" rx="1.5" fill="var(--ink-3)"/><rect x="5" y="52" width="58" height="12" rx="6" fill="var(--surface)"/><circle cx="108" cy="42" r="6" fill="var(--surface)"/><circle cx="108" cy="57" r="6" fill="var(--surface)"/></svg>',
    rounded: () =>
      '<svg viewBox="0 0 120 68" preserveAspectRatio="xMidYMid slice">' + SVG.map("#3b6cf0") +
      '<rect x="6" y="6" width="46" height="34" rx="9" fill="#2b2b2e"/><rect x="12" y="13" width="22" height="5" rx="2.5" fill="#f2f2f2"/><rect x="12" y="23" width="32" height="10" rx="5" fill="#3b6cf0"/><circle cx="106" cy="54" r="8" fill="#2b2b2e"/></svg>',
    graphite: () =>
      '<svg viewBox="0 0 120 68" preserveAspectRatio="xMidYMid slice">' + SVG.map("#3b6cf0") +
      '<rect x="6" y="6" width="46" height="34" rx="6" fill="var(--surface)" opacity=".94"/><rect x="12" y="13" width="22" height="5" rx="2" fill="var(--ink)"/><path d="M10 25 H48" stroke="var(--line)" stroke-width="1.2"/><rect x="12" y="29" width="28" height="4" rx="2" fill="var(--ink-3)"/><rect x="98" y="42" width="15" height="21" rx="4" fill="var(--surface)" opacity=".94"/></svg>',
    colours: (kind) => {
      const c = {
        graphite: ["var(--map-land)", "var(--map-road)", "var(--map-park)", "#3b6cf0", "#c9cacd"],
        muted: ["#d6d8d4", "#f3f3ef", "#bfcabd", "#5b78cf", "#e2c88f"],
        app: ["#e9edf1", "#ffffff", "#b7e3a8", "#8a5cf0", "#f6c443"],
      }[kind];
      return (
        '<svg viewBox="0 0 120 68" preserveAspectRatio="xMidYMid slice"><rect width="120" height="68" fill="' + c[0] +
        '"/><rect x="72" y="40" width="34" height="22" rx="4" fill="' + c[2] + '"/><path d="M-4 48 C30 42 50 58 124 28" stroke="' + c[4] +
        '" stroke-width="8" fill="none"/><path d="M40 -4 L58 72" stroke="' + c[1] + '" stroke-width="5" fill="none"/><path d="M14 72 C24 52 40 46 56 38 S84 20 96 8" stroke="' + c[3] +
        '" stroke-width="4.5" fill="none" stroke-linecap="round"/></svg>'
      );
    },
  };

  /** a growing "A" for size choices */
  const A = (px) => h("span", { class: "seg-a", style: "font-size:" + px + "px" }, "A");

  /* ---------- formatting ---------- */
  const fmt = {
    bytes(n) {
      if (typeof n !== "number" || !isFinite(n)) return "?";
      const u = ["B", "KB", "MB", "GB", "TB"];
      let i = 0;
      while (n >= 1000 && i < u.length - 1) {
        n /= 1000;
        i += 1;
      }
      return (i ? n.toFixed(n < 10 ? 1 : 0) : String(n)) + " " + u[i];
    },
    dur(s) {
      const d = Math.floor(s / 86400);
      const hh = Math.floor((s % 86400) / 3600);
      const m = Math.floor((s % 3600) / 60);
      return d ? d + " d " + hh + " h" : hh ? hh + " h " + m + " min" : m ? m + " min" : Math.floor(s) + " s";
    },
    ago(t) {
      if (!t) return "never";
      const s = Math.max(0, Date.now() / 1000 - t);
      return s < 90 ? "just now" : s < 5400 ? Math.round(s / 60) + " min ago" : s < 129600 ? Math.round(s / 3600) + " h ago" : Math.round(s / 86400) + " days ago";
    },
    signal(dbm) {
      return dbm >= -60 ? "strong" : dbm >= -70 ? "good" : dbm >= -80 ? "weak" : "very weak";
    },
    secs(x) {
      return x.toFixed(1).replace(/\.0$/, "") + " s";
    },
  };

  TAHtml.SettingsUI = {
    mount(el) {
      root = el;
    },
    get root() {
      return root;
    },
    h,
    append,
    icon,
    wait,
    busy,
    toast,
    CHIP,
    row,
    setControl,
    status,
    save,
    userError,
    segmented,
    tiles,
    toggle,
    slider,
    tapValue,
    value,
    button,
    armed,
    group,
    facts,
    signalBars,
    warnbox,
    calm,
    disclosure,
    listItem,
    openSheet,
    closeSheet,
    editText,
    pickFrom,
    SVG,
    A,
    fmt,
  };
})(window);
