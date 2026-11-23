// teslalinux: Settings > Gates (PalGate; js/gates/gates-controller.js, services/gates/gates.py,
// docs/GATES.md). Linking with the QR code the PalGate app scans (Device Linking > Link a Device),
// unlinking, which gates the map's gate button shows and in what order, and per gate: its name
// on the map, the output it opens, Open now, and auto-open (off until turned on for that gate:
// its spot from the car's position, the circle, approach-only, the speeds, the cooldown).
// SettingsController installs it (install(controller)): one section and its search entries.
(function (global) {
  "use strict";

  const TAHtml = global.TAHtml;
  const UI = TAHtml.SettingsUI;
  const { h, icon } = UI;

  const G = () => global.TLGates;
  const gateName = (g) => g.label || g.name;

  function autoSummary(g) {
    const a = g.auto || {};
    if (!a.enabled) return null;
    return "Opens by itself within " + a.radius + " m";
  }

  /** the QR sheet: the code, the steps, and the link's state until it is linked, expires or is closed */
  async function linkSheet(ctrl, token) {
    const gates = G();
    const status = h("div", { class: "gates-link-status", role: "status", "aria-live": "polite" });
    const qr = h("div", { class: "gates-qr", "aria-label": "Link code for the PalGate app" });
    const again = UI.button("New code", null, "btn--primary", "refresh");
    again.hidden = true;
    const steps = h(
      "ol",
      { class: "gates-steps" },
      h("li", null, "Open the PalGate app on your phone."),
      h("li", null, "Open the menu, then Device Linking, then Link a Device."),
      h("li", null, "Scan this code. The gates appear here by themselves."),
    );
    const sheet = UI.openSheet({
      title: "Link to PalGate",
      body: [h("div", { class: "gates-link" }, qr, h("div", null, steps, status, again)),
        h("p", { class: "gates-fine" }, "This device becomes a linked device of your PalGate account: it can list and open your gates, nothing more. You can remove it in the app at any time.")],
    });
    let done = false;
    let timer = null;
    const show = (st) => {
      if (st.qr) qr.innerHTML = st.qr;
      qr.classList.toggle("is-stale", st.state !== "waiting");
      again.hidden = !(st.state === "expired" || st.state === "failed");
      if (st.state === "waiting") {
        const s = st.expiresIn || 0;
        status.className = "gates-link-status is-wait";
        status.replaceChildren(h("span", { class: "spin" }), h("span", null, "Waiting for the scan… " + Math.floor(s / 60) + ":" + String(s % 60).padStart(2, "0") + " left"));
      } else if (st.state === "linked") {
        status.className = "gates-link-status is-ok";
        status.replaceChildren(icon("check_circle"), h("span", null, "Linked. Reading your gates…"));
      } else if (st.state === "expired") {
        status.className = "gates-link-status is-err";
        status.replaceChildren(icon("timer_off"), h("span", null, st.message || "The code expired."));
      } else if (st.state === "failed") {
        status.className = "gates-link-status is-err";
        status.replaceChildren(icon("error_outline"), h("span", null, st.message || "Linking failed."));
      }
    };
    const poll = async () => {
      if (done) return;
      let st;
      try {
        st = await gates.linkState();
      } catch (e) {
        status.className = "gates-link-status is-err";
        status.replaceChildren(icon("error_outline"), h("span", null, String(e.message || e)));
        timer = global.setTimeout(poll, 3000);
        return;
      }
      show(st);
      if (st.state === "linked") {
        done = true;
        await gates.refresh();
        global.setTimeout(() => {
          sheet.finish(true);
        }, 1200);
        return;
      }
      if (st.state === "waiting") timer = global.setTimeout(poll, 1000);
    };
    const start = async () => {
      again.hidden = true;
      status.className = "gates-link-status is-wait";
      status.replaceChildren(h("span", { class: "spin" }), h("span", null, "Making a code…"));
      try {
        show(await gates.startLink());
        timer = global.setTimeout(poll, 1000);
      } catch (e) {
        show({ state: "failed", message: String(e.message || e) });
      }
    };
    again.addEventListener("click", start);
    start();
    const res = await sheet;
    done = true;
    global.clearTimeout(timer);
    if (!res) gates.cancelLink();
    if (token === ctrl._renderToken) ctrl._rerender();
  }

  /** a gate's options sheet; drawn again after each change from the service's answer */
  function gateSheet(ctrl, id) {
    const gates = G();
    const body = h("div", { class: "gates-sheet" });
    const sheet = UI.openSheet({ title: "Gate", body });
    // the car's distance to the gate, live while the sheet is open
    const live = global.setInterval(async () => {
      const st = await gates.refresh();
      const g = st && (st.gates || []).find((x) => x.id === id);
      const d = body.querySelector('[data-id="gate.spot"] .row-detail');
      if (g && d && g.auto && g.auto.lat != null && g.autoState && g.autoState.distance != null) d.textContent = "The car is " + fmtDist(g.autoState.distance) + " from it";
      const take = body.querySelector('[data-id="gate.spot"] .row-control .btn');
      if (take && st) take.disabled = !st.position;
    }, 3000);
    sheet.then(() => {
      global.clearInterval(live);
      ctrl._rerender();
    });
    const gate = () => (gates.state && gates.state.gates || []).find((g) => g.id === id);
    const save = (row, changes, okText) =>
      UI.save(row, () => gates.saveSettings({ gates: [Object.assign({ id }, changes)] }), okText);
    // a change that changes what the sheet shows (the name, the spot): drawn again after it
    const saveDraw = async (row, changes, okText) => {
      const ok = await save(row, changes, okText);
      if (ok) draw();
      return ok;
    };

    function draw() {
      const g = gate();
      if (!g) {
        sheet.finish(null);
        return;
      }
      const title = sheet.sheet && sheet.sheet.querySelector(".sheet-head h2");
      if (title) title.textContent = gateName(g);
      const a = g.auto || {};
      const pos = gates.state.position;
      const kids = [];

      // open now: the same action as the map's button
      const openRow = UI.row({ id: "gate.open", title: "Open now", detail: g.localOnly ? "This gate opens only over Bluetooth, from the phone" : g.address || null });
      const openBtn = UI.button("Open", async () => {
        openBtn.disabled = true;
        const st = UI.status(openRow);
        st.pending("Sending…");
        const r = await gates.open(g);
        if (r.ok) st.ok("Opened");
        else st.err(r.message, () => openBtn.click());
        openBtn.disabled = false;
      }, "btn--primary", "garage");
      openBtn.disabled = g.localOnly;
      UI.setControl(openRow, openBtn);
      kids.push(UI.group(null, [openRow]));

      // on the map
      const shown = UI.row({ id: "gate.shown", title: "Show on the map", detail: "In the gate button among the map's buttons" });
      UI.setControl(shown, UI.toggle(g.shown, (v) => save(shown, { shown: v }), "Show on the map"));
      const nameRow = UI.row({ id: "gate.label", title: "Name on the map", detail: g.label ? "PalGate's name: " + g.name : null });
      UI.setControl(nameRow, UI.tapValue(gateName(g), async () => {
        const v = await UI.editText({ title: "Name on the map", value: g.label || g.name, maxLength: 40, help: "Empty: PalGate's name, " + g.name + ".", validate: (x) => (x.length > 40 ? "At most 40 letters" : "") });
        if (v == null) return;
        const label = v.trim() === g.name ? "" : v.trim();
        await saveDraw(nameRow, { label });
      }, Boolean(g.label)));
      const rows = [shown, nameRow];
      if ((g.outputs || []).length > 1) {
        const outRow = UI.row({ id: "gate.output", title: "What it opens", stack: true });
        UI.setControl(outRow, UI.segmented(
          g.outputs.map((n) => ({ value: n, label: (g.outputNames && g.outputNames[String(n)]) || "Output " + n })),
          g.output,
          (v) => save(outRow, { output: v }),
          { label: "What it opens" },
        ));
        rows.push(outRow);
      }
      kids.push(UI.group("On the map", rows));

      // auto-open
      const auto = [];
      const spot = UI.row({
        id: "gate.spot",
        title: "Gate's position",
        detail: a.lat != null ? (g.autoState && g.autoState.distance != null ? "The car is " + fmtDist(g.autoState.distance) + " from it" : "Set") : "Not set. Stand at the gate and take the car's position",
      });
      const take = UI.button(a.lat != null ? "Set again here" : "Use the car's position", async () => {
        take.disabled = true;
        const st = UI.status(spot);
        st.pending("Taking the position…");
        try {
          await gates.takePosition(id);
          draw();
          UI.status(body.querySelector('[data-id="gate.spot"]')).ok("Set to where the car is now", true);
          return;
        } catch (e) {
          st.err(String(e.message || e));
        }
        take.disabled = false;
      }, null, "my_location");
      take.disabled = !pos;
      UI.setControl(spot, take);
      auto.push(spot);
      if (!pos) auto.push(UI.warnbox("The car's position is not known now. Turn on Car's position in Settings > Waze, and keep this page open in the car.", "location_off"));

      const en = UI.row({ id: "gate.auto", title: "Open when I arrive", detail: a.lat == null ? "Set the gate's position first" : "When the car comes into the circle from outside" });
      const tog = UI.toggle(a.enabled, (v) => save(en, { auto: { enabled: v } }, v ? "On. It opens when you arrive" : "Off"), "Open when I arrive");
      tog.querySelector("input").disabled = a.lat == null;
      UI.setControl(en, tog);
      auto.push(en);

      if (a.lat != null) {
        const rad = UI.row({ id: "gate.radius", title: "Circle around the gate", stack: true });
        UI.setControl(rad, UI.slider({ min: 15, max: 300, step: 5, value: a.radius, unit: " m", iconLow: "radio_button_checked", label: "Circle around the gate", onChange: (v) => save(rad, { auto: { radius: v } }) }));
        const appr = UI.row({ id: "gate.approach", title: "Only when driving towards it", detail: "Not while driving away or standing" });
        UI.setControl(appr, UI.toggle(a.approachOnly, (v) => save(appr, { auto: { approachOnly: v } }), "Only when driving towards it"));
        const slow = UI.row({ id: "gate.minspeed", title: "Slowest speed", detail: "Slower than this (parking, standing): no open", stack: true });
        UI.setControl(slow, UI.slider({ min: 0, max: 30, step: 1, value: a.minSpeed, unit: " km/h", iconLow: "speed", label: "Slowest speed",
          onChange: (v) => save(slow, { auto: { minSpeed: v } }) }));
        const fast = UI.row({ id: "gate.maxspeed", title: "Fastest speed", detail: "Faster than this (driving past on the road): no open", stack: true });
        UI.setControl(fast, UI.slider({ min: 10, max: 100, step: 5, value: a.maxSpeed, unit: " km/h", iconLow: "speed", label: "Fastest speed",
          onChange: (v) => save(fast, { auto: { maxSpeed: v } }) }));
        const cool = UI.row({ id: "gate.cooldown", title: "Not again for", detail: "After an open, and only once the car has left the circle", stack: true });
        UI.setControl(cool, UI.slider({ min: 1, max: 60, step: 1, value: a.cooldown, unit: " min", iconLow: "timer", label: "Not again for",
          onChange: (v) => save(cool, { auto: { cooldown: v } }) }));
        auto.push(rad, appr, slow, fast, cool);
      }
      kids.push(UI.group("Open automatically", auto, "Off until you turn it on here. The device opens the gate when the car arrives, and shows a notice on the map."));
      body.replaceChildren(...kids);
    }
    draw();
  }

  function fmtDist(m) {
    return m >= 1000 ? (m / 1000).toFixed(m >= 10000 ? 0 : 1) + " km" : Math.round(m) + " m";
  }

  async function render(token) {
    const gates = G();
    if (!gates) return [UI.warnbox("Gates are not available on this page.")];
    const st = await gates.refresh();
    if (!st) {
      return [UI.group("PalGate", [UI.warnbox("The gates service is not running on the device. Restart the device, or update it if this keeps happening.", "error_outline")])];
    }
    const groups = [];
    if (!st.linked) {
      const link = UI.row({ id: "gates.link", title: "Link this device", detail: "Show a code to scan with the PalGate app" });
      UI.setControl(link, UI.button("Link", () => linkSheet(this, token), "btn--primary", "qr_code_2"));
      groups.push(UI.group("PalGate", [link],
        "Open your PalGate gates and barriers from the car, without the phone. The device becomes a linked device of your PalGate account."));
      return groups;
    }

    // the account
    const acct = UI.row({ id: "gates.account", title: "PalGate account", detail: "Linked to " + (st.account.phone || "your account") +
      (st.account.linkedAt ? ", since " + new Date(st.account.linkedAt * 1000).toLocaleDateString() : "") });
    const list = UI.row({ id: "gates.refresh", title: "Gate list", detail: st.listError ? null : "Read " + UI.fmt.ago(st.listedAt) });
    const refresh = UI.button("Read again", async () => {
      refresh.disabled = true;
      const ok = await UI.save(list, () => gates.refreshList(), "Read");
      refresh.disabled = false;
      if (ok && token === this._renderToken) this._rerender();
    }, null, "refresh");
    UI.setControl(list, refresh);
    if (st.listError) UI.status(list).err(st.listError);
    const un = UI.row({ id: "gates.unlink", title: "Unlink", detail: "The device forgets the account and its gates" });
    UI.setControl(un, UI.armed("Unlink", "Tap again to unlink", async (b) => {
      b.disabled = true;
      const ok = await UI.save(un, () => gates.unlink(), "Unlinked");
      if (ok && token === this._renderToken) this._rerender();
      else b.disabled = false;
    }));
    groups.push(UI.group("PalGate", [acct, list, un]));

    // the gates
    const items = st.gates.map((g, i) => {
      const r = UI.row({ id: "gate." + g.id, title: gateName(g), detail: [g.localOnly ? "Bluetooth only" : null, autoSummary(g), g.label ? g.name : null].filter(Boolean).join(" · ") || null });
      const move = (d) => async () => {
        const ids = st.gates.map((x) => x.id);
        const j = i + d;
        if (j < 0 || j >= ids.length) return;
        [ids[i], ids[j]] = [ids[j], ids[i]];
        const ok = await UI.save(r, () => gates.saveSettings({ order: ids }), "Moved");
        if (ok && token === this._renderToken) this._rerender();
      };
      const up = h("button", { class: "btn btn--quiet gates-move", type: "button", "aria-label": "Move " + gateName(g) + " up", disabled: i === 0 || null, onclick: move(-1) }, icon("arrow_upward"));
      const down = h("button", { class: "btn btn--quiet gates-move", type: "button", "aria-label": "Move " + gateName(g) + " down", disabled: i === st.gates.length - 1 || null, onclick: move(1) }, icon("arrow_downward"));
      const opts = h("button", { class: "btn gates-opts", type: "button", "aria-label": "Options for " + gateName(g), onclick: () => gateSheet(this, g.id) }, icon("tune"));
      const tog = UI.toggle(g.shown, async (v) => {
        const ok = await UI.save(r, () => gates.saveSettings({ gates: [{ id: g.id, shown: v }] }), v ? "Shown on the map" : "Hidden");
        if (ok) this._renderSidebar();
        return ok;
      }, "Show " + gateName(g) + " on the map");
      UI.setControl(r, h("div", { class: "gates-ctl" }, up, down, opts, tog));
      return r;
    });
    if (!items.length) items.push(UI.row({ id: "gates.none", title: "No gates", detail: "This PalGate account has no gates yet" }));
    const shownN = st.gates.filter((g) => g.shown).length;
    groups.push(UI.group("Gates", items,
      shownN === 1 ? "The gate button among the map's buttons opens the one gate shown at once." : "The gate button among the map's buttons opens a list of the gates shown; one tap opens a gate. Show only one and it opens at once."));

    const anyAuto = st.gates.some((g) => g.auto && g.auto.enabled);
    if (anyAuto && !st.position) groups.push(UI.warnbox("A gate opens by itself only while the car's position is known: turn on Car's position in Settings > Waze, and keep the car page open.", "location_off"));
    return groups;
  }

  TAHtml.SettingsGates = {
    install(ctrl) {
      const at = ctrl.sections.findIndex((s) => s.id === "waze");
      ctrl.sections.splice(at + 1, 0, {
        id: "gates",
        title: "Gates",
        icon: "garage",
        render,
        railValue() {
          const st = G() && G().state;
          if (!st) return "";
          if (!st.linked) return "Not linked";
          const n = st.gates.filter((g) => g.shown).length;
          return n === 1 ? "1 gate" : n + " gates";
        },
      });
      ctrl.index.push(
        ["gates.link", "gates", "Link PalGate", "palgate gate barrier link qr code account phone app pal"],
        ["gates.refresh", "gates", "Gate list", "gates list barrier palgate refresh read", () => Boolean(G() && G().state && G().state.linked)],
        ["gates.unlink", "gates", "Unlink PalGate", "unlink remove palgate account forget", () => Boolean(G() && G().state && G().state.linked)],
        ["gates.auto", "gates", "Open gates automatically", "auto open gate arrive near approach gps automatic barrier"],
      );
      if (G()) G().onChange(() => ctrl._renderSidebar && ctrl.sidebar && ctrl._renderSidebar());
    },
  };
})(window);
