/* teslalinux: Settings > This device > Debug > "Emulate the Model 3 screen", for testing the car page on
 * a computer. While it is on (per browser, localStorage), this page runs inside emulate.html, in a
 * frame with a Model 3 browser's viewport (773.2 x 601.3 CSS px, docs/CAR-SCREENS.md), scaled to
 * fit the real window; in that frame devicePixelRatio is the car's 1.53. So everything the page
 * computes from its window, the layout and media queries, the display size it asks the device for
 * (each resolution preset, Native's device pixels and density), the stream canvas's backing store,
 * comes out as in the car. Loaded first and not deferred: the frame's devicePixelRatio must be the
 * car's before any other script reads it, and the top page must go to the emulator before the app
 * starts. */
(function (global) {
  "use strict";

  const KEY = "teslalinux.debug.emulateScreen";
  // the measured Model 3 (docs/CAR-SCREENS.md, Nov 19 2026)
  const PROFILES = {
    model3: {
      id: "model3",
      name: "Model 3",
      cssWidth: 773.20263671875,
      cssHeight: 601.3071899414062,
      devicePixelRatio: 1.53,
    },
  };

  function read() {
    try {
      const v = global.localStorage.getItem(KEY);
      return v && PROFILES[v] ? v : null;
    } catch (e) {
      return null; // storage off: never on
    }
  }

  function framed() {
    try {
      const f = global.frameElement;
      return f && f.dataset && PROFILES[f.dataset.tlEmulation] ? PROFILES[f.dataset.tlEmulation] : null;
    } catch (e) {
      return null; // a frame of another origin: not ours
    }
  }

  const frameProfile = framed();
  const api = {
    KEY,
    PROFILES,
    /** the profile this page emulates (it runs in the emulator's frame), or null */
    active: frameProfile,
    /** the profile switched on in this browser, or null */
    chosen: read(),
    /** the top page is on its way to the emulator: the app does not start in it */
    leaving: false,
    /** switches the emulation on (a profile id) or off (null) for this browser and reloads into it or out of it */
    set(id) {
      try {
        if (id && PROFILES[id]) global.localStorage.setItem(KEY, id);
        else global.localStorage.removeItem(KEY);
      } catch (e) {
        return false;
      }
      const top = global.top || global;
      top.location.replace(id ? new URL("./emulate.html", top.location.href).href : new URL("./", top.location.href).href);
      return true;
    },
  };
  global.TLScreenEmulation = api;

  if (frameProfile) {
    // the car's pixel ratio for everything that reads it (the browser scales the frame itself)
    Object.defineProperty(global, "devicePixelRatio", {
      configurable: true,
      get: () => frameProfile.devicePixelRatio,
    });
    document.documentElement.dataset.tlEmulation = frameProfile.id;
  } else if (api.chosen && global.top === global) {
    // the top page shows the emulator instead, with this page in its frame
    api.leaving = true;
    global.location.replace(new URL("./emulate.html", global.location.href).href + global.location.hash);
  }
})(window);
