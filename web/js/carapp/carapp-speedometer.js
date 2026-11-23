// teslalinux: the app's speedometer, drawn by the car page over the app's map (Car mode). Where the
// device supports it (Settings > Waze interface > Speedometer; tools/apps/waze-speedometer.py, the
// verified Waze versions), the app's own speedometer is off and its readings arrive on
// /sockets/carapp as {"type":"speedometer","speed":S,"units":U,"speedLimit":L,"speedKph":K,
// "miles":B} (docs/UPSTREAM-NOTES.md, "Car app templates"). Drawn here as the app drew it: the
// current speed in a round bubble, the road's speed limit in a sign beside it (the round, red-ringed
// sign; the US rectangle where the app uses that style), at the bottom right of the map area the
// panels leave free, the speed turning red above the limit by the app's own rule.
(function (global) {
  "use strict";

  const MODES = ["both", "limit", "off"];
  // where it stands: "bottomRight" (where the app drew its own, the default) or "topLeft" (under
  // the directions: below the turn card, else at the top left of the free map area)
  const POSITIONS = ["bottomRight", "topLeft"];

  /**
   * Above the limit, as the app decides it (its speedometer state): with an offset above 0, above
   * limit + limit * offset / 100 (whole numbers); else above limit - offset.
   */
  function overLimit(speed, limit, offset) {
    if (!(speed >= 0) || !(limit > 0)) return false;
    const off = Number.isFinite(offset) ? Math.trunc(offset) : 0;
    const threshold = off > 0 ? limit + Math.trunc((limit * off) / 100) : limit - off;
    return speed > threshold;
  }

  class Speedometer {
    /** @param {HTMLElement} surface  a layer with the map surface's size in display pixels */
    constructor(surface) {
      this.surface = surface;
      this.el = document.createElement("div");
      this.el.className = "carapp-speedo hidden";
      this.el.setAttribute("aria-live", "off");
      this.sign = document.createElement("div");
      this.sign.className = "carapp-speed-limit";
      this.signCaption = document.createElement("span");
      this.signCaption.className = "caption";
      this.signCaption.textContent = "SPEED LIMIT";
      this.signNumber = document.createElement("span");
      this.signNumber.className = "number";
      this.sign.append(this.signCaption, this.signNumber);
      this.bubble = document.createElement("div");
      this.bubble.className = "carapp-speed";
      this.speedNumber = document.createElement("span");
      this.speedNumber.className = "number";
      this.speedUnit = document.createElement("span");
      this.speedUnit.className = "unit";
      this.bubble.append(this.speedNumber, this.speedUnit);
      this.el.append(this.sign, this.bubble);
      surface.appendChild(this.el);
    }

    /**
     * Draws a reading (null: none yet) with the choices: mode "both" (speed and limit, the
     * default), "limit" (the limit sign only) or "off"; signStyle "us" or "world"; alertOffset (the
     * app's over-the-limit offset); area: the map area the panels leave free, in surface pixels
     * ({left, top, right, bottom}; null: a full-screen panel covers the map); width and height of
     * the surface; position "bottomRight" (at the area's bottom right) or "topLeft" (at under, a
     * {left, top} point in surface pixels: below the turn card, else the area's top left). Hidden
     * while there is nothing to show.
     */
    update(reading, opts) {
      const mode = MODES.indexOf(opts.mode) >= 0 ? opts.mode : "both";
      const area = opts.area;
      const r = reading || {};
      const speed = Number.isFinite(r.speed) ? r.speed : -1;
      const limit = Number.isFinite(r.speedLimit) ? r.speedLimit : 0;
      const showSpeed = mode === "both" && speed >= 0;
      const showLimit = mode !== "off" && limit > 0;
      const visible = !!reading && !!area && (showSpeed || showLimit);
      this.el.classList.toggle("hidden", !visible);
      if (!visible) return;
      const top = opts.position === "topLeft";
      this.el.classList.toggle("top-left", top);
      if (top) {
        const at = opts.under || { left: area.left, top: area.top };
        this.el.style.left = Math.max(0, at.left) + "px";
        this.el.style.top = Math.max(0, at.top) + "px";
        this.el.style.right = this.el.style.bottom = "";
      } else {
        this.el.style.right = Math.max(0, opts.width - area.right) + "px";
        this.el.style.bottom = Math.max(0, opts.height - area.bottom) + "px";
        this.el.style.left = this.el.style.top = "";
      }
      this.sign.hidden = !showLimit;
      this.bubble.hidden = !showSpeed;
      if (showLimit) {
        const us = opts.signStyle === "us";
        this.sign.classList.toggle("us", us);
        this.signCaption.hidden = !us;
        const text = String(limit);
        if (this.signNumber.textContent !== text) this.signNumber.textContent = text;
        this.sign.classList.toggle("wide", text.length > 2);
        this.sign.setAttribute("aria-label", "Speed limit " + text + (r.miles ? " mph" : ""));
      }
      if (showSpeed) {
        const text = String(speed);
        if (this.speedNumber.textContent !== text) this.speedNumber.textContent = text;
        const unit = typeof r.units === "string" ? r.units : "";
        if (this.speedUnit.textContent !== unit) this.speedUnit.textContent = unit;
        this.bubble.classList.toggle("wide", text.length > 2);
        this.bubble.classList.toggle("over", limit > 0 && overLimit(speed, limit, opts.alertOffset));
      }
    }
  }

  /**
   * The point under the directions, in surface pixels of ROOT's surface (WIDTH wide): below the
   * turn card (or, over side panels, the next-step pill), a panel margin apart; null when neither
   * is on screen (then the free map area's top left).
   */
  Speedometer.underDirections = function (root, width) {
    const box = root.getBoundingClientRect();
    const scale = box.width / width || 1;
    const el = root.querySelector(".carapp-card.steps") || root.querySelector(".carapp-pill");
    if (!el) return null;
    const b = el.getBoundingClientRect();
    if (!b.height) return null;
    const gap = parseFloat(getComputedStyle(el).getPropertyValue("--card-top")) || 16;
    return { left: Math.round((b.left - box.left) / scale), top: Math.round((b.bottom - box.top) / scale + gap) };
  };

  Speedometer.MODES = MODES;
  Speedometer.POSITIONS = POSITIONS;
  Speedometer.overLimit = overLimit;
  global.TLCarSpeedometer = Speedometer;
})(window);
