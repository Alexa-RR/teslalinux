(function (global) {
  "use strict";

  const TAHtml = (global.TAHtml = global.TAHtml || {});

  TAHtml.constants = {
    APP_VERSION: "2026.3.1",
    DISPLAY_PREF_KEYS: [
      "DisplayRepository_isPrimaryDisplaySharedPreferencesKey",
      "flutter.DisplayRepository_isPrimaryDisplaySharedPreferencesKey",
    ],
    DEFAULT_NON_RESPONSIVE_SIZE: { width: 1088, height: 832 },
    RESIZE_COOLDOWN_MS: 1000,
    NATIVE_RESOLUTION_PRESET: 5, // teslalinux: utils.nativeDisplaySize
    TOUCH_SLOT_COUNT: 10,
    DISPLAY_RENDERERS: [
      { value: 0, label: "Motion JPEG" },
      { value: 1, label: "h264 (WebCodecs)" },
      { value: 2, label: "h264 (legacy)" },
    ],
    DISPLAY_RESOLUTION_PRESETS: [
      { value: 5, label: "Native (screen pixels)" },
      { value: 0, label: "832p" },
      { value: 1, label: "720p" },
      { value: 2, label: "640p" },
      { value: 3, label: "544p" },
      { value: 4, label: "480p" },
    ],
    DISPLAY_QUALITY_PRESETS: [
      { value: 40, label: "40" },
      { value: 50, label: "50" },
      { value: 60, label: "60" },
      { value: 70, label: "70" },
      { value: 80, label: "80" },
      { value: 90, label: "90" },
    ],
    DISPLAY_REFRESH_RATE_PRESETS: [
      { value: 30, label: "30 Hz" },
      { value: 45, label: "45 Hz" },
      { value: 60, label: "60 Hz" },
    ],
    SOFT_AP_BANDS: [
      { key: "band2_4GHz", name: "2.4 GHz", band: 1, channel: 6, channelWidth: 2 },
      { key: "band5GHz36", name: "5 GHZ - Channel 36", band: 2, channel: 36, channelWidth: 3 },
      { key: "band5GHz44", name: "5 GHZ - Channel 44", band: 2, channel: 44, channelWidth: 3 },
      { key: "band5GHz149", name: "5 GHZ - Channel 149", band: 2, channel: 149, channelWidth: 3 },
      { key: "band5GHz157", name: "5 GHZ - Channel 157", band: 2, channel: 157, channelWidth: 3 },
    ],
  };

  TAHtml.log = function log(message) {
    // Uncomment for debugging:
    // console.log("[tesla-android-web]", message);
  };

  // teslalinux: derive every endpoint from the page origin so the hostname is configured once,
  // in host/phase1-pios/env, instead of being hardcoded here.
  TAHtml.createFlavor = function createFlavor() {
    const host = global.location.host;
    const wsScheme = global.location.protocol === "https:" ? "wss" : "ws";
    return {
      domain: host,
      apiBaseUrl: global.location.protocol + "//" + host + "/api",
      audioWebSocket: wsScheme + "://" + host + "/sockets/audio",
      displayWebSocket: wsScheme + "://" + host + "/sockets/display",
      gpsWebSocket: wsScheme + "://" + host + "/sockets/gps",
      speechWebSocket: wsScheme + "://" + host + "/sockets/speech",
      touchscreenWebSocket: wsScheme + "://" + host + "/sockets/touchscreen",
    };
  };

  TAHtml.utils = {
    toInt,
    clamp,
    alignUp,
    delay,
    compareVersions,
    parseBoolean,
    getViewportSize,
    normalizeSize,
    sameSize,
    createElement,
    mapSoftApBandFromConfig,
    densityForResolutionPreset,
    nativeDisplaySize,
    rendererName,
    resolutionPresetName,
    qualityPresetName,
    refreshRateName,
    mapDeviceModelName,
  };

  function toInt(value, fallback) {
    const parsed = Number(value);
    if (Number.isFinite(parsed)) {
      return Math.trunc(parsed);
    }
    return fallback;
  }

  function clamp(value, min, max) {
    return Math.max(min, Math.min(max, value));
  }

  function alignUp(value, alignment) {
    return Math.ceil(value / alignment) * alignment;
  }

  function delay(ms) {
    return new Promise((resolve) => {
      global.setTimeout(resolve, ms);
    });
  }

  function compareVersions(currentVersion, latestVersion) {
    const currentParts = String(currentVersion || "")
      .split(".")
      .map((part) => toInt(part, 0));
    const latestParts = String(latestVersion || "")
      .split(".")
      .map((part) => toInt(part, 0));

    const maxLength = Math.max(currentParts.length, latestParts.length);
    for (let index = 0; index < maxLength; index += 1) {
      const currentValue = index < currentParts.length ? currentParts[index] : 0;
      const latestValue = index < latestParts.length ? latestParts[index] : 0;
      if (latestValue > currentValue) {
        return 1;
      }
      if (latestValue < currentValue) {
        return -1;
      }
    }
    return 0;
  }

  function parseBoolean(value) {
    if (value === true || value === "true" || value === "1" || value === 1) {
      return true;
    }
    if (value === false || value === "false" || value === "0" || value === 0) {
      return false;
    }
    return null;
  }

  function getViewportSize() {
    if (global.visualViewport) {
      return {
        width: Math.max(1, Math.round(global.visualViewport.width)),
        height: Math.max(1, Math.round(global.visualViewport.height)),
      };
    }

    return {
      width: Math.max(1, Math.round(global.innerWidth)),
      height: Math.max(1, Math.round(global.innerHeight)),
    };
  }

  function normalizeSize(size, options) {
    const fallback = options && options.fallback ? options.fallback : { width: 1, height: 1 };

    const width = toInt(size && size.width, fallback.width);
    const height = toInt(size && size.height, fallback.height);

    return {
      width: Math.max(1, width),
      height: Math.max(1, height),
    };
  }

  function sameSize(left, right) {
    if (!left || !right) {
      return false;
    }
    return left.width === right.width && left.height === right.height;
  }

  function createElement(tag, className, textContent) {
    const element = document.createElement(tag);
    if (className) {
      element.className = className;
    }
    if (textContent !== undefined && textContent !== null) {
      element.textContent = textContent;
    }
    return element;
  }

  function mapSoftApBandFromConfig(config) {
    const band = toInt(config && config["persist.tesla-android.softap.band_type"], 1);
    const channel = toInt(config && config["persist.tesla-android.softap.channel"], 6);

    if (band === 1) {
      return TAHtml.constants.SOFT_AP_BANDS[0];
    }

    for (let index = 0; index < TAHtml.constants.SOFT_AP_BANDS.length; index += 1) {
      const item = TAHtml.constants.SOFT_AP_BANDS[index];
      if (item.channel === channel) {
        return item;
      }
    }
    return TAHtml.constants.SOFT_AP_BANDS[1];
  }

  // teslalinux: the "Native" resolution preset. The upstream presets size the stream in CSS px, which
  // the car's browser then stretches onto its panel (a Model 3: a 773x601 CSS px window at
  // devicePixelRatio 1.53, so an 832x608 stream is enlarged about 1.45 times; docs/CAR-SCREENS.md).
  // Native sizes it in device pixels instead: the window's size times devicePixelRatio, rounded up to
  // the 16-pixel blocks H.264 codes, so every pixel of the stream lands on one pixel of the screen
  // (the few rounding pixels fall outside the window). Larger than the encoder's 1920x1088 it is
  // scaled down to fit and then shown like the other presets.
  var NATIVE_RESOLUTION_PRESET = 5; // also TAHtml.constants.NATIVE_RESOLUTION_PRESET
  var NATIVE_MAX_WIDTH = 1920;
  var NATIVE_MAX_HEIGHT = 1088;
  // the app's density per CSS px: one dp is 1.16 CSS px, the size 832p gives the app on a Model 3's
  // browser window (density 200 on an 832 px stream shown 773 CSS px wide), so switching to Native
  // keeps everything the same size on the screen, only sharper
  var NATIVE_DP_CSS_PX = 1.16;

  function nativeDisplaySize(viewport, devicePixelRatio) {
    const vv = global.visualViewport;
    const cssWidth = viewport && viewport.width > 0 ? viewport.width : vv ? vv.width : global.innerWidth;
    const cssHeight = viewport && viewport.height > 0 ? viewport.height : vv ? vv.height : global.innerHeight;
    const dpr = devicePixelRatio > 0 ? devicePixelRatio : global.devicePixelRatio > 0 ? global.devicePixelRatio : 1;
    let deviceWidth = Math.max(320, cssWidth * dpr);
    let deviceHeight = Math.max(320, cssHeight * dpr);
    // what one stream pixel is in device pixels: 1, unless the window is larger than the encoder takes
    const scale = Math.min(1, NATIVE_MAX_WIDTH / alignUp(deviceWidth, 16), NATIVE_MAX_HEIGHT / alignUp(deviceHeight, 16));
    const width = Math.min(NATIVE_MAX_WIDTH, alignUp(deviceWidth * scale, 16));
    const height = Math.min(NATIVE_MAX_HEIGHT, alignUp(deviceHeight * scale, 16));
    const density = clamp(Math.round(160 * NATIVE_DP_CSS_PX * dpr * scale), 72, 640);
    return {
      width,
      height,
      density,
      // the stream's size in CSS px when shown pixel for pixel (scale 1); null: fit it like the others
      cssWidth: scale === 1 ? width / dpr : null,
      cssHeight: scale === 1 ? height / dpr : null,
    };
  }

  function densityForResolutionPreset(preset, isH264) {
    const normalizedPreset = toInt(preset, 0);
    if (normalizedPreset === NATIVE_RESOLUTION_PRESET) {
      return nativeDisplaySize(null).density;
    }
    if (isH264) {
      switch (normalizedPreset) {
        case 0:
          return 200;
        case 1:
          return 175;
        default:
          return 175;
      }
    }
    switch (normalizedPreset) {
      case 0:
        return 200;
      case 1:
        return 175;
      case 2:
        return 155;
      case 3:
        return 130;
      case 4:
        return 115;
      default:
        return 200;
    }
  }

  function rendererName(renderer) {
    return _lookupLabel(TAHtml.constants.DISPLAY_RENDERERS, renderer, "Motion JPEG");
  }

  function resolutionPresetName(preset) {
    return _lookupLabel(TAHtml.constants.DISPLAY_RESOLUTION_PRESETS, preset, "832p");
  }

  function qualityPresetName(value) {
    return _lookupLabel(TAHtml.constants.DISPLAY_QUALITY_PRESETS, value, "90");
  }

  function refreshRateName(value) {
    return _lookupLabel(TAHtml.constants.DISPLAY_REFRESH_RATE_PRESETS, value, "30 Hz");
  }

  function mapDeviceModelName(rawModel) {
    if (rawModel === "rpi4") {
      return "Raspberry Pi 4";
    }
    if (rawModel === "cm4") {
      return "Compute Module 4";
    }
    return "UNOFFICIAL " + String(rawModel || "undefined");
  }

  function _lookupLabel(options, value, fallback) {
    const target = toInt(value, toInt(options[0].value, 0));
    for (let index = 0; index < options.length; index += 1) {
      if (toInt(options[index].value, 0) === target) {
        return options[index].label;
      }
    }
    return fallback;
  }
})(window);
