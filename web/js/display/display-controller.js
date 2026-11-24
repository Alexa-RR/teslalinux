(function (global) {
  "use strict";

  const TAHtml = global.TAHtml;
  const utils = TAHtml.utils;
  const constants = TAHtml.constants;

  class DisplayController {
    constructor(options) {
      this.apiClient = options.apiClient;
      this.rendererManager = options.rendererManager;
      this.flavor = options.flavor;
      this.onResizeSessionRefresh = options.onResizeSessionRefresh || null;
      this.onResizeModeChange =
        typeof options.onResizeModeChange === "function" ? options.onResizeModeChange : null;

      this.remoteDisplayState = null;

      this.display = {
        viewSize: { width: 1, height: 1 },
        adjustedSize: { width: 1, height: 1 },
      };

      this.resize = {
        mode: "initial",
        token: 0,
        timerId: null,
        cooldownState: null,
      };

      this._viewportWatcherStarted = false;
      this.isPaused = false;

      this._notifyResizeMode(this.resize.mode);
    }

    async initialize() {
      const remoteDisplayState = this._normalizeRemoteDisplayState(
        await this.apiClient.fetchDisplayState(),
      );
      this.remoteDisplayState = remoteDisplayState;

      this._setNormalState({
        viewSize: utils.getViewportSize(),
        adjustedSize: {
          width: remoteDisplayState.width,
          height: remoteDisplayState.height,
        },
        remoteDisplayState,
      });

      await this.rendererManager.ensureDisplaySocket({
        displayWebSocket: this.flavor.displayWebSocket,
        remoteDisplayState,
      });

      this._setupViewportWatcher();
      this.triggerResizeFromViewport();
    }

    getTouchscreenSize() {
      return {
        width: this.display.adjustedSize.width,
        height: this.display.adjustedSize.height,
      };
    }

    setResizeSessionRefreshHandler(handler) {
      this.onResizeSessionRefresh = typeof handler === "function" ? handler : null;
    }

    setResizeModeChangeHandler(handler) {
      this.onResizeModeChange = typeof handler === "function" ? handler : null;
      this._notifyResizeMode(this.resize.mode);
    }

    triggerResizeFromViewport() {
      this.triggerResize(utils.getViewportSize());
    }

    triggerResize(viewSize) {
      if (this.isPaused) {
        return;
      }
      // teslalinux: the device keyboard (js/keyboard/device-keyboard.js) shrinks the viewport while it is
      // up; resizing the display for that would restart it and close the keyboard again
      if (global.TLDeviceKeyboard && global.TLDeviceKeyboard.isOpen()) {
        return;
      }
      const normalizedViewSize = utils.normalizeSize(viewSize, {
        fallback: { width: 1, height: 1 },
      });
      void this._startResize(normalizedViewSize);
    }

    async _startResize(viewSize) {
      if (this.isPaused) {
        return;
      }
      if (!this.flavor) {
        return;
      }

      if (this.resize.mode === "resize_cooldown") {
        const scheduled = this.resize.cooldownState;
        if (scheduled && utils.sameSize(scheduled.viewSize, viewSize)) {
          return;
        }
        this._clearResizeTimer();
      }

      const token = ++this.resize.token;

      let remoteDisplayState;
      try {
        remoteDisplayState = this._normalizeRemoteDisplayState(
          await this.apiClient.fetchDisplayState(),
        );
      } catch (error) {
        TAHtml.log("Unable to fetch display state for resize: " + String(error));
        return;
      }

      if (token !== this.resize.token) {
        return;
      }

      this.remoteDisplayState = remoteDisplayState;

      // teslalinux: the page is always the car's main display. Upstream asked once whether it runs
      // on a rear display (the Display Type dialog) and then kept the device's size there; this
      // device drives the main screen only (/api/displayState reports the rear display off)
      const sizeForCalculation = remoteDisplayState.isResponsive === 1
        ? viewSize
        : constants.DEFAULT_NON_RESPONSIVE_SIZE;

      const desiredSize = this._calculateOptimalSize(sizeForCalculation, {
        resolutionPreset: remoteDisplayState.resolutionPreset,
        isH264: remoteDisplayState.isH264 === 1,
        isHeadless: (remoteDisplayState.isHeadless ?? 1) === 1,
      });

      this._setResizeMode("resize_cooldown");
      this.resize.cooldownState = {
        token,
        viewSize,
        adjustedSize: desiredSize,
        resolutionPreset: remoteDisplayState.resolutionPreset,
        renderer: remoteDisplayState.renderer,
        isResponsive: remoteDisplayState.isResponsive === 1,
        quality: remoteDisplayState.quality,
        refreshRate: remoteDisplayState.refreshRate,
      };

      this.resize.timerId = global.setTimeout(() => {
        void this._sendResizeRequest(token);
      }, constants.RESIZE_COOLDOWN_MS);
    }

    async _sendResizeRequest(token) {
      const cooldown = this.resize.cooldownState;
      if (!cooldown || this.resize.mode !== "resize_cooldown" || cooldown.token !== token) {
        return;
      }

      this._setResizeMode("resize_in_progress");
      this.resize.timerId = null;

      const isH264 = cooldown.renderer !== 0;
      const density = cooldown.adjustedSize && cooldown.adjustedSize.density > 0
        ? cooldown.adjustedSize.density // teslalinux: Native's density goes with its size
        : this._densityForResolutionPreset(cooldown.resolutionPreset, isH264);

      const payload = {
        width: cooldown.adjustedSize.width,
        height: cooldown.adjustedSize.height,
        density,
        resolutionPreset: cooldown.resolutionPreset,
        renderer: cooldown.renderer,
        isResponsive: cooldown.isResponsive ? 1 : 0,
        isH264: isH264 ? 1 : 0,
        quality: cooldown.quality,
        refreshRate: cooldown.refreshRate,
      };

      try {
        await this.apiClient.postDisplayState(payload);
      } catch (error) {
        TAHtml.log("Resize request failed: " + String(error));
        this._setResizeMode("normal");
        this.resize.cooldownState = null;
        return;
      }

      if (token !== this.resize.token) {
        return;
      }

      this.remoteDisplayState = {
        ...this.remoteDisplayState,
        ...payload,
      };

      await utils.delay(constants.RESIZE_COOLDOWN_MS);

      if (token !== this.resize.token) {
        return;
      }

      if (!this.isPaused) {
        this.rendererManager.closeDisplaySocket();
        if (typeof this.onResizeSessionRefresh === "function") {
          try {
            this.onResizeSessionRefresh({
              width: cooldown.adjustedSize.width,
              height: cooldown.adjustedSize.height,
            });
          } catch (_error) {
            // no-op
          }
        }
      }

      this._setNormalState({
        viewSize: cooldown.viewSize,
        adjustedSize: cooldown.adjustedSize,
        remoteDisplayState: this.remoteDisplayState,
      });
    }

    _setNormalState(options) {
      const remoteDisplayState = options.remoteDisplayState || this.remoteDisplayState;
      if (!remoteDisplayState) {
        return;
      }

      this._clearResizeTimer();
      this._setResizeMode("normal");
      this.resize.cooldownState = null;

      this.display.viewSize = utils.normalizeSize(options.viewSize, {
        fallback: utils.getViewportSize(),
      });

      this.display.adjustedSize = utils.normalizeSize(options.adjustedSize, {
        fallback: {
          width: remoteDisplayState.width,
          height: remoteDisplayState.height,
        },
      });

      global.displayWidth = this.display.adjustedSize.width;
      global.displayHeight = this.display.adjustedSize.height;

      this.rendererManager.applyDisplaySize(
        this.display.adjustedSize.width,
        this.display.adjustedSize.height,
        this._nativeCssSize(this.display.adjustedSize, remoteDisplayState),
      );

      if (!this.isPaused) {
        void this.rendererManager.ensureDisplaySocket({
          displayWebSocket: this.flavor.displayWebSocket,
          remoteDisplayState,
        });
      }
    }

    setPaused(paused) {
      const nextPaused = Boolean(paused);
      if (nextPaused === this.isPaused) {
        return;
      }

      this.isPaused = nextPaused;

      if (nextPaused) {
        this._clearResizeTimer();
        this.rendererManager.closeDisplaySocket();
      } else {
        if (this.remoteDisplayState) {
          void this.rendererManager.ensureDisplaySocket({
            displayWebSocket: this.flavor.displayWebSocket,
            remoteDisplayState: this.remoteDisplayState,
          });
        }
        this.triggerResizeFromViewport();
      }
    }

    _setupViewportWatcher() {
      if (this._viewportWatcherStarted) {
        return;
      }
      this._viewportWatcherStarted = true;

      let lastViewport = utils.getViewportSize();
      // teslalinux: while a keyboard is up (the device keyboard for the app's field, or one of the
      // page's own fields), nothing about the picture changes: not its size, not the stream's, not its
      // place. The car's browser shrinks the window for its keyboard; the stage keeps the height it had
      // before (pinned in px, so the centred picture does not move up) and the keyboard simply covers
      // the bottom of the picture. lastViewport keeps the size from before, so closing the keyboard
      // changes nothing either, and a real window change made meanwhile is applied once it is down.
      const stage = this.rendererManager.displayRoot ? this.rendererManager.displayRoot.parentElement : null;
      let stagePinned = false;
      const keyboardUp = () => !!(global.TLDeviceKeyboard && global.TLDeviceKeyboard.isOpen());
      const pinStage = () => {
        if (stagePinned || !stage) {
          return;
        }
        stagePinned = true;
        stage.style.bottom = "auto";
        stage.style.height = String(lastViewport.height) + "px";
      };
      const unpinStage = () => {
        if (!stagePinned || !stage) {
          return;
        }
        stagePinned = false;
        stage.style.bottom = "";
        stage.style.height = "";
      };

      const onPotentialResize = () => {
        if (this.isPaused) {
          return;
        }

        if (keyboardUp()) {
          if (!utils.sameSize(utils.getViewportSize(), lastViewport)) {
            pinStage();
          }
          return;
        }
        unpinStage();

        const current = utils.getViewportSize();
        if (!utils.sameSize(current, lastViewport)) {
          lastViewport = current;

          if (this.display.adjustedSize.width > 0 && this.display.adjustedSize.height > 0) {
            this.rendererManager.applyDisplaySize(
              this.display.adjustedSize.width,
              this.display.adjustedSize.height,
              this._nativeCssSize(this.display.adjustedSize, this.remoteDisplayState),
            );
          }

          this.triggerResize(current);
        }
      };

      const rafCheck = () => {
        onPotentialResize();
        global.requestAnimationFrame(rafCheck);
      };

      global.addEventListener("resize", onPotentialResize, { passive: true });
      global.addEventListener("orientationchange", onPotentialResize, { passive: true });

      if (global.visualViewport) {
        global.visualViewport.addEventListener("resize", onPotentialResize, {
          passive: true,
        });
        global.visualViewport.addEventListener("scroll", onPotentialResize, {
          passive: true,
        });
      }

      global.requestAnimationFrame(rafCheck);
    }

    _normalizeRemoteDisplayState(raw) {
      return {
        width: Math.max(1, utils.toInt(raw.width, 1024)),
        height: Math.max(1, utils.toInt(raw.height, 768)),
        density: utils.toInt(raw.density, 200),
        resolutionPreset: utils.toInt(raw.resolutionPreset, 0),
        renderer: utils.toInt(raw.renderer, 0),
        isHeadless:
          raw.isHeadless === undefined || raw.isHeadless === null
            ? 1
            : utils.toInt(raw.isHeadless, 1),
        isResponsive: utils.toInt(raw.isResponsive, 1),
        isH264: utils.toInt(raw.isH264, 0),
        refreshRate: utils.toInt(raw.refreshRate, 30),
        quality: utils.toInt(raw.quality, 90),
      };
    }

    // teslalinux: with Native, a stream the compositor runs at the screen's own size is shown pixel
    // for pixel (its CSS size); anything else (another preset, a size not applied yet, a window that
    // changed since) is fitted into the window as upstream does (null)
    _nativeCssSize(size, remoteDisplayState) {
      if (!size || !remoteDisplayState
        || utils.toInt(remoteDisplayState.resolutionPreset, 0) !== constants.NATIVE_RESOLUTION_PRESET) {
        return null;
      }
      const native = utils.nativeDisplaySize(null);
      if (native.cssWidth === null || native.width !== size.width || native.height !== size.height) {
        return null;
      }
      return { width: native.cssWidth, height: native.cssHeight };
    }

    _calculateOptimalSize(viewSize, options) {
      const isHeadless = Boolean(options.isHeadless);
      if (!isHeadless) {
        return { width: 1024, height: 768 };
      }

      const resolutionPreset = utils.toInt(options.resolutionPreset, 0);
      const isH264 = Boolean(options.isH264);

      // teslalinux: Native follows the screen (utils.nativeDisplaySize), whatever the view size
      if (resolutionPreset === constants.NATIVE_RESOLUTION_PRESET) {
        const native = utils.nativeDisplaySize(null);
        return { width: native.width, height: native.height, density: native.density };
      }

      let adjustedResolutionPreset;
      if (isH264) {
        adjustedResolutionPreset = resolutionPreset === 0 ? 0 : 1;
      } else {
        adjustedResolutionPreset = resolutionPreset;
      }

      const maxWidth = 1920;
      const maxHeight = 1088;
      const minSide = 320;

      const safeView = utils.normalizeSize(viewSize, {
        fallback: { width: 1024, height: 768 },
      });
      const aspectRatio = safeView.width / safeView.height;

      let width = utils.clamp(safeView.width, minSide, maxWidth);
      let height = width / aspectRatio;

      if (height > maxHeight) {
        height = maxHeight;
        width = height * aspectRatio;
      }

      const maxShortest = this._maxHeightForResolutionPreset(adjustedResolutionPreset);
      const shortest = Math.min(width, height);
      if (shortest > maxShortest) {
        const scale = maxShortest / shortest;
        width *= scale;
        height *= scale;
      }

      width = utils.alignUp(width, 64);
      height = utils.alignUp(height, 32);

      if (height <= 480) {
        height = 512;
        width = utils.alignUp(height * aspectRatio, 64);
      }

      if (width < minSide) {
        width = utils.alignUp(minSide, 64);
      }
      if (height < minSide) {
        height = utils.alignUp(minSide, 32);
      }

      width = Math.min(width, maxWidth);
      height = Math.min(height, maxHeight);

      return {
        width: Math.round(width),
        height: Math.round(height),
      };
    }

    _maxHeightForResolutionPreset(preset) {
      switch (utils.toInt(preset, 0)) {
        case 0:
          return 832;
        case 1:
          return 720;
        case 2:
          return 640;
        case 3:
          return 544;
        case 4:
          return 480;
        default:
          return 832;
      }
    }

    _densityForResolutionPreset(preset, isH264) {
      const normalizedPreset = utils.toInt(preset, 0);

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

    _clearResizeTimer() {
      if (this.resize.timerId !== null) {
        global.clearTimeout(this.resize.timerId);
        this.resize.timerId = null;
      }
    }

    _setResizeMode(mode) {
      if (this.resize.mode === mode) {
        return;
      }
      this.resize.mode = mode;
      this._notifyResizeMode(mode);
    }

    _notifyResizeMode(mode) {
      if (typeof this.onResizeModeChange === "function") {
        try {
          this.onResizeModeChange(String(mode || "initial"));
        } catch (_error) {
          // no-op
        }
      }
    }
  }

  TAHtml.DisplayController = DisplayController;
})(window);
