// teslalinux: Settings > Tesla (docs/TESLA-LINK.md; GET/POST /api/carLink, services/carkey and
// services/carsync). The car's VIN, pairing this device as a read-only key (the car's own add-key
// prompt, confirmed with the key card), the link's state, the route on the car's own screen, and the
// two things the link does: Waze follows the car's destination, and the car's GPS as Waze's position.
// SettingsController installs it (install(controller)): one section after Waze, and its search entries.
(function (global) {
  "use strict";

  const TAHtml = global.TAHtml;
  const UI = TAHtml.SettingsUI;
  const { h } = UI;
  const VIN_RE = /^[A-HJ-NPR-Z0-9]{17}$/;

  const LINK_TEXT = {
    off: "Not set up",
    searching: "Looking for the car",
    connected: "Connected over Bluetooth",
    notPaired: "Not paired yet",
    asleep: "The car is not answering",
    error: "Not connected",
  };

  function routeText(d) {
    if (!d || (!d.destination && d.destLat == null)) return "No route on the car's screen";
    const parts = [d.destination || d.destLat.toFixed(5) + ", " + d.destLon.toFixed(5)];
    if (d.minutesToArrival > 0) parts.push(Math.round(d.minutesToArrival) + " min");
    if (d.kmToArrival > 0) parts.push((d.kmToArrival < 10 ? d.kmToArrival.toFixed(1) : Math.round(d.kmToArrival)) + " km");
    return parts.join(", ");
  }

  // what the rows show: re-rendered when it changes (polled every 3 s while the section is open)
  function signature(st) {
    const c = st.car || {};
    const d = c.drive || {};
    return JSON.stringify([st.running, c.vin, c.link, c.keyReady, d.destination, d.destLat != null, st.settings,
      st.sync && st.sync.lastAction && st.sync.lastAction.at, st.sync && st.sync.gpsFeeding]);
  }

  async function render(token) {
    const ctrl = this;
    const api = ctrl.apiClient;
    let st;
    try {
      st = await api.fetchCarLink();
    } catch (_e) {
      return [UI.warnbox("The device did not answer.", "error_outline")];
    }
    ctrl.cache.carLink = st;
    if (!st.running || !st.car) {
      return [UI.group("Tesla", [UI.warnbox("The car link service is not running on the device. Restart the device, or update it if this keeps happening.", "error_outline")])];
    }
    const car = st.car;
    const groups = [];
    const rerenderSoon = (ms) => global.setTimeout(() => token === ctrl._renderToken && ctrl._rerender(), ms || 400);
    const send = async (r, req, pendingText) => {
      const s = UI.status(r);
      s.pending(pendingText);
      const res = await api.setCarLink(req);
      if (!res.ok) s.err(res.message || "Not saved");
      else s.ok(res.message || "Saved", true);
      return res.ok;
    };

    // ---- the car
    const vinRow = UI.row({
      id: "tesla.vin",
      title: "Car's VIN",
      more: "On the car's screen under Controls > Software (Additional Vehicle Information), or at the bottom of the windshield on the driver's side. The car's Bluetooth name comes from it.",
      control: UI.tapValue(car.vin || "Not set", async () => {
        const v = await UI.editText({
          title: "Car's VIN",
          value: car.vin || "",
          maxLength: 17,
          placeholder: "17 letters and digits",
          help: "Pairing asks the car to confirm with your key card afterwards.",
          validate: (x) => (VIN_RE.test(x.trim().toUpperCase()) ? "" : "A VIN has 17 letters and digits (no I, O or Q)."),
        });
        if (v == null) return;
        if (await send(vinRow, { vin: v.trim().toUpperCase() }, "Saving…")) rerenderSoon(800);
      }),
    });
    const linkRow = UI.row({
      id: "tesla.link",
      title: "Link",
      detail: car.link === "connected" ? null : car.message,
      control: UI.value(LINK_TEXT[car.link] || car.link),
    });
    const rows = [vinRow, linkRow];
    if (car.vin && car.link !== "connected") {
      const pairRow = UI.row({
        id: "tesla.pair",
        title: "Pair with the car",
        detail: car.pairRequestedAt ? "Request sent: tap your key card on the reader behind the cupholders, then confirm on the car's screen." : "Once: the car asks you to confirm with your key card",
        more: [
          "This device becomes a read-only key of the car (role: vehicle monitor). It reads the car's route and position over Bluetooth; it cannot unlock, open or drive the car.",
          "Sit in the car with it on. The car's prompt waits about 30 seconds for the key card.",
          "The key shows on the car's Controls > Locks screen, where you can remove it at any time.",
        ],
      });
      const pb = UI.button("Pair", null, "btn--primary", "key");
      pb.addEventListener("click", async () => {
        pb.disabled = true;
        try {
          if (await send(pairRow, { action: "pair" }, "Asking the car…")) rerenderSoon(3000);
        } finally {
          pb.disabled = false;
        }
      });
      UI.setControl(pairRow, pb);
      rows.push(pairRow);
    }
    if (car.vin || car.keyReady) {
      const fr = UI.row({
        id: "tesla.forget",
        title: "Forget the car",
        detail: "Also remove the key on the car: Controls > Locks",
      });
      UI.setControl(fr, UI.armed("Forget", "Tap again to forget", async () => {
        if (await send(fr, { action: "forget" }, "Forgetting…")) rerenderSoon(600);
      }));
      rows.push(fr);
    }
    groups.push(UI.group("Car", rows));

    // ---- what the link does
    const followRow = UI.row({
      id: "tesla.follow",
      title: "Waze follows the car's route",
      detail: car.link === "connected" ? "On the car's screen now: " + routeText(car.drive) : null,
      more: [
        "Start a route on the car's own map and Waze drives to the same place. Waze's route preview is confirmed for you in the car interface.",
        "When the car's route ends (arrived, or cancelled on its screen), Waze's drive to it ends too (car interface). A drive you start in Waze itself is left alone.",
      ],
      control: UI.toggle(st.settings.follow, (on) => UI.save(followRow, async () => {
        const res = await api.setCarLink({ follow: on });
        if (!res.ok) throw UI.userError(res.message || "Not saved");
      }, on ? "On" : "Off"), "Waze follows the car's route"),
    });
    const syncRows = [followRow];
    const last = st.sync && st.sync.lastAction;
    if (last) {
      const lr = UI.row({ id: "tesla.last", title: "Last", detail: UI.fmt.ago(last.at) + ": " + last.text });
      if (!last.ok) UI.status(lr).err(last.text);
      syncRows.push(lr);
    }
    const gpsRow = UI.row({
      id: "tesla.gps",
      title: "Use the car's GPS",
      detail: st.settings.carGps ? (st.sync.gpsFeeding ? "Waze gets the car's position now" : "Waiting for the car's position") : "Off: Waze gets the position from this browser",
      more: "The car's own position, heading and speed, read over Bluetooth every second. It does not depend on the browser's location, which is often vague in the car's browser. The browser's position is used again when the car's stops coming.",
      control: UI.toggle(st.settings.carGps, (on) => UI.save(gpsRow, async () => {
        const res = await api.setCarLink({ carGps: on });
        if (!res.ok) throw UI.userError(res.message || "Not saved");
      }, on ? "On" : "Off").then((ok) => {
        if (ok) rerenderSoon(2500);
        return ok;
      }), "Use the car's GPS"),
    });
    syncRows.push(gpsRow);
    groups.push(UI.group("With the car", syncRows, car.link === "connected" ? null : "These work while the link is connected."));

    // the link's state and the car's route change by themselves: looked at again every 3 s
    const sig = signature(st);
    const poll = async () => {
      if (token !== ctrl._renderToken) return;
      if (ctrl._visible()) {
        try {
          const next = await api.fetchCarLink();
          if (token !== ctrl._renderToken) return;
          if (signature(next) !== sig) return ctrl._rerender();
        } catch (_e) {
          // the next round tries again
        }
      }
      ctrl._pollTimer = global.setTimeout(poll, 3000);
    };
    ctrl._pollTimer = global.setTimeout(poll, 3000);
    return groups;
  }

  TAHtml.SettingsTesla = {
    install(ctrl) {
      const at = ctrl.sections.findIndex((s) => s.id === "waze");
      ctrl.sections.splice(at + 1, 0, {
        id: "tesla",
        title: "Tesla",
        icon: "directions_car",
        render,
        railValue() {
          const st = this.cache.carLink;
          if (!st || !st.car) return "";
          if (st.car.link === "connected") return st.settings.follow ? "Following the car" : "Connected";
          return LINK_TEXT[st.car.link] || "";
        },
      });
      ctrl.index.push(
        ["tesla.vin", "tesla", "Car's VIN", "vin tesla car vehicle identification number"],
        ["tesla.pair", "tesla", "Pair with the car", "pair key card tesla bluetooth phone key link"],
        ["tesla.follow", "tesla", "Waze follows the car's route", "follow car route destination navigation tesla sync map"],
        ["tesla.gps", "tesla", "Use the car's GPS", "gps position location car tesla accuracy"],
        ["tesla.forget", "tesla", "Forget the car", "forget remove unpair tesla key"],
      );
    },
  };
})(window);
