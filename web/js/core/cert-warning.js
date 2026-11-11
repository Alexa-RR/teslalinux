// teslalinux addition (not upstream): a small, dismissable notice when the car's TLS certificate needs
// attention. Reads GET /api/certificate (services/api/api.py, the state host/tls/teslalinux-tls
// writes) a few seconds after load and every 30 minutes; shows nothing when the route is missing,
// the request fails, or all is well. Never blocks the display or touch: one fixed element, bottom left.
(function (global) {
  "use strict";

  const WARN_DAYS = 14;
  const FIRST_CHECK_MS = 4000;
  const RECHECK_MS = 30 * 60 * 1000;
  const DISMISS_KEY = "teslalinux.certWarning.dismissed";
  let element = null;

  function message(state) {
    if (!state || typeof state !== "object") {
      return null;
    }
    let days = Number(state.daysLeft);
    if (!Number.isFinite(days) && Number.isFinite(Number(state.notAfter))) {
      days = (Number(state.notAfter) * 1000 - Date.now()) / 86400000;
    }
    if (state.selfSigned === true) {
      return "Temporary certificate: the car has not received its certificate yet.";
    }
    const result = String(state.lastResult || "");
    if (Number.isFinite(days) && days < WARN_DAYS) {
      const left = days <= 0 ? "has expired" : "expires in " + Math.max(1, Math.floor(days)) + " day" + (Math.floor(days) === 1 ? "" : "s");
      return "The car certificate " + left + (result === "failed" || result === "rejected" ? " and the last update failed." : ".");
    }
    if (result === "failed" || result === "rejected") {
      return "Certificate update " + (result === "rejected" ? "rejected" : "failed") +
        (Number.isFinite(days) ? " (" + Math.floor(days) + " days left)." : ".");
    }
    return null;
  }

  function dismissed() {
    try {
      return global.sessionStorage.getItem(DISMISS_KEY) === "1";
    } catch (_error) {
      return false;
    }
  }

  function show(text) {
    if (!element) {
      element = document.createElement("div");
      element.setAttribute("role", "status");
      element.style.cssText = "position:fixed;left:12px;bottom:12px;z-index:110;max-width:min(520px,calc(100vw - 24px));" +
        "display:flex;align-items:center;gap:10px;padding:8px 10px 8px 14px;border-radius:12px;" +
        "background:rgba(40,40,40,0.92);color:#fff;font:14px/1.35 Roboto,Arial,sans-serif;box-shadow:0 2px 10px rgba(0,0,0,.35)";
      const label = document.createElement("span");
      label.className = "teslalinux-cert-warning-text";
      const close = document.createElement("button");
      close.type = "button";
      close.textContent = "×";
      close.setAttribute("aria-label", "Dismiss");
      close.style.cssText = "border:0;background:transparent;color:#fff;font-size:20px;line-height:1;padding:4px 6px;cursor:pointer";
      close.addEventListener("click", function () {
        element.hidden = true;
        try {
          global.sessionStorage.setItem(DISMISS_KEY, "1");
        } catch (_error) {
          // no storage: dismissed for this page only
        }
      });
      element.appendChild(label);
      element.appendChild(close);
      document.body.appendChild(element);
    }
    element.querySelector(".teslalinux-cert-warning-text").textContent = text;
    element.hidden = false;
  }

  async function check() {
    if (dismissed()) {
      return;
    }
    let state = null;
    try {
      const response = await fetch("/api/certificate", { cache: "no-store", credentials: "same-origin" });
      if (!response.ok) {
        return;
      }
      state = await response.json();
    } catch (_error) {
      return;
    }
    const text = message(state);
    if (text) {
      show(text);
    } else if (element) {
      element.hidden = true;
    }
  }

  global.addEventListener("load", function () {
    global.setTimeout(check, FIRST_CHECK_MS);
    global.setInterval(check, RECHECK_MS);
  });
})(window);
