// teslalinux: type into the app with the keyboard of the device showing this page (the car's, a
// phone's) instead of the app's built-in on-screen keyboard. Settings > Keyboard (GET/POST
// /api/keyboard) chooses; in "device" mode this connects to /sockets/keyboard (tacomp, TACOMP_IME_PORT):
//   tacomp -> page  {"type":"ime","active":true,"purpose":N,"hint":N} when a text field in the app
//                   wants input (Wayland text-input-v3, purpose/hint as in that protocol), active:false
//                   when it no longer does
//   page -> tacomp  one command per line: "t TEXT" commits TEXT into the field, "k enter|backspace"
// A hidden input takes the device keyboard; what is typed is sent as the difference between its
// previous and current value, so autocorrect, swipe typing and composed input arrive as text plus
// backspaces. Browsers that open the keyboard only from a tap (Safari on iPhone) get a keyboard
// button while a field wants input. Nothing here runs in "builtin" mode.
(function (global) {
  "use strict";

  const SENTINEL = "​​​​"; // zero-width: Backspace on an "empty" field still changes the value
  const MAX_VALUE = 48;
  const PURPOSE_INPUTMODE = {
    2: "numeric", // digits
    3: "decimal", // number
    4: "tel",     // phone
    5: "url",
    6: "email",
    9: "numeric", // pin
  };
  const HINT_SPELLCHECK = 0x2;
  const HINT_AUTO_CAPITALIZATION = 0x4;
  const HINT_MULTILINE = 0x200;

  let socket = null;
  let reconnectTimer = null;
  let wantSocket = false;
  let active = false;
  let composing = false;
  let previous = SENTINEL;
  let input = null;
  let button = null;
  let hideTimer = null;
  const HIDE_GRACE_MS = 600; // a field that disables and re-enables text input (a tap on it) keeps the keyboard

  function apiBase() {
    return global.location.protocol + "//" + global.location.host + "/api";
  }

  function socketUrl() {
    const scheme = global.location.protocol === "https:" ? "wss" : "ws";
    return scheme + "://" + global.location.host + "/sockets/keyboard";
  }

  function send(line) {
    if (socket && socket.readyState === 1) {
      socket.send(line + "\n");
    }
  }

  function ensureElements() {
    if (input) {
      return;
    }
    input = document.createElement("input");
    input.type = "text";
    input.className = "teslalinux-kbd-input";
    input.setAttribute("aria-hidden", "true");
    input.setAttribute("autocomplete", "off");
    input.setAttribute("enterkeyhint", "enter");
    input.tabIndex = -1;
    input.addEventListener("compositionstart", () => {
      composing = true;
    });
    input.addEventListener("compositionend", () => {
      composing = false;
      sync();
    });
    input.addEventListener("input", () => {
      if (!composing) {
        sync();
      }
    });
    input.addEventListener("keydown", (event) => {
      if (event.key === "Enter" && !event.isComposing) {
        event.preventDefault();
        sync();
        send("k enter");
      }
    });
    input.addEventListener("blur", refreshButton);
    input.addEventListener("focus", () => global.setTimeout(refreshButton, 400));
    document.body.appendChild(input);

    button = document.createElement("button");
    button.type = "button";
    button.className = "teslalinux-kbd-button";
    button.setAttribute("aria-label", "Show keyboard");
    const icon = document.createElement("span");
    icon.className = "material-symbols-rounded";
    icon.textContent = "keyboard";
    button.appendChild(icon);
    button.hidden = true;
    // focus inside the tap itself (touchend/click): the only way Safari on iPhone opens the keyboard;
    // the page's touch layer must not take these taps
    const tap = (event) => {
      event.preventDefault();
      event.stopPropagation();
      focusInput();
    };
    button.addEventListener("touchstart", (event) => event.stopPropagation(), { passive: true });
    button.addEventListener("touchend", tap, { passive: false });
    button.addEventListener("click", tap);
    button.addEventListener("pointerdown", (event) => event.stopPropagation());
    document.body.appendChild(button);
    // while a field wants input, any tap on the page (the field again, say) opens the keyboard: Safari
    // ignores the focus we try when the field becomes active, a moment after the tap
    document.addEventListener("touchend", (event) => {
      if (active && !keyboardOpen() && event.target !== button && !button.contains(event.target) && !isTextField(event.target)) {
        focusInput();
      }
    }, { capture: true, passive: true });
    if (global.visualViewport) {
      global.visualViewport.addEventListener("resize", refreshButton);
    }
  }

  function resetValue() {
    input.value = SENTINEL;
    previous = SENTINEL;
    try {
      input.setSelectionRange(SENTINEL.length, SENTINEL.length);
    } catch (_error) {
      // some input modes (email, number) have no selection API
    }
  }

  function focusInput() {
    ensureElements();
    resetValue();
    try {
      input.focus({ preventScroll: true });
    } catch (_error) {
      input.focus();
    }
    refreshButton();
  }

  // a text field of the page itself (Settings: hotspot name, APN...): it takes the keyboard, not the app
  function isTextField(el) {
    return !!el && el !== input && (el.tagName === "TEXTAREA" || el.isContentEditable ||
      (el.tagName === "INPUT" && !/^(checkbox|radio|range|button|submit|reset|file|color)$/.test(el.type)));
  }

  // the keyboard is up: our input has focus and the visual viewport shrank (Safari can report the
  // input as focused without showing a keyboard, so focus alone proves nothing)
  function keyboardOpen() {
    if (!input || document.activeElement !== input) {
      return false;
    }
    const vv = global.visualViewport;
    if (!vv) {
      return true; // no way to tell: trust focus
    }
    return vv.height < global.innerHeight - 100;
  }

  function refreshButton() {
    if (!button) {
      return;
    }
    button.hidden = !(active && !keyboardOpen());
  }

  // the difference between the previous and the current value: backspaces for what went, text for what came
  function sync() {
    const before = Array.from(previous);
    const after = Array.from(input.value);
    let common = 0;
    while (common < before.length && common < after.length && before[common] === after[common]) {
      common += 1;
    }
    for (let i = common; i < before.length; i += 1) {
      send("k backspace");
    }
    const added = after.slice(common).join("").replace(/​/g, "");
    if (added.length > 0) {
      const lines = added.split(/\r?\n/);
      lines.forEach((part, index) => {
        if (index > 0) {
          send("k enter");
        }
        if (part.length > 0) {
          send("t " + part);
        }
      });
    }
    previous = input.value;
    if (!input.value.startsWith(SENTINEL) || Array.from(input.value).length > MAX_VALUE) {
      resetValue();
    }
  }

  function applyState(state) {
    if (!state || state.type !== "ime") {
      return;
    }
    ensureElements();
    const wasActive = active;
    active = state.active === true;
    if (active && hideTimer) {
      global.clearTimeout(hideTimer);
      hideTimer = null;
    }
    if (active) {
      const purpose = Number(state.purpose) || 0;
      const hint = Number(state.hint) || 0;
      input.setAttribute("inputmode", PURPOSE_INPUTMODE[purpose] || "text");
      input.setAttribute("autocapitalize", hint & HINT_AUTO_CAPITALIZATION ? "sentences" : "off");
      input.setAttribute("autocorrect", hint & HINT_SPELLCHECK ? "on" : "off");
      input.spellcheck = (hint & HINT_SPELLCHECK) !== 0;
      input.setAttribute("enterkeyhint", hint & HINT_MULTILINE ? "enter" : "go");
      if (!wasActive) {
        // Chromium (the car) opens its keyboard for a focus shortly after a tap; Safari on iPhone
        // does not, and gets the button
        focusInput();
      }
    } else if (wasActive && !hideTimer) {
      hideTimer = global.setTimeout(() => {
        hideTimer = null;
        if (!active) {
          input.blur();
          refreshButton();
        }
      }, HIDE_GRACE_MS);
    }
    refreshButton();
  }

  function connect() {
    if (!wantSocket || socket) {
      return;
    }
    socket = new WebSocket(socketUrl());
    socket.addEventListener("message", (event) => {
      try {
        applyState(JSON.parse(event.data));
      } catch (_error) {
        // not ours
      }
    });
    socket.addEventListener("close", () => {
      socket = null;
      applyState({ type: "ime", active: false });
      if (wantSocket && !reconnectTimer) {
        reconnectTimer = global.setTimeout(() => {
          reconnectTimer = null;
          connect();
        }, 1000);
      }
    });
  }

  function setMode(mode) {
    wantSocket = mode === "device";
    if (wantSocket) {
      ensureElements();
      connect();
    } else {
      if (socket) {
        socket.close();
        socket = null;
      }
      applyState({ type: "ime", active: false });
    }
  }

  async function init() {
    try {
      const response = await fetch(apiBase() + "/keyboard", { cache: "no-store" });
      if (!response.ok) {
        return; // an API without the route: the built-in keyboard, as upstream
      }
      const state = await response.json();
      setMode(state && state.mode);
    } catch (_error) {
      // offline or no route: nothing to do
    }
  }

  // the display controller asks this before resizing the display for a viewport change: a keyboard
  // opened for the app's field or for one of the page's own fields must not resize the app's display
  global.TLDeviceKeyboard = {
    isOpen: () => (!!input && document.activeElement === input) || isTextField(document.activeElement),
  };

  // Settings > Keyboard tells us when the choice changes
  global.addEventListener("teslalinux-keyboard-mode", (event) => {
    setMode(event && event.detail);
  });

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})(window);
