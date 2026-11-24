(function (global) {
  "use strict";

  const TAHtml = global.TAHtml;

  class ShellController {
    constructor(options) {
      this.apiClient = options.apiClient;
      this.displayController = options.displayController;
      this.settingsController = options.settingsController;

      // teslalinux: two pages, the map (#home-shell) and Settings (#page-settings); upstream's About,
      // Release Notes and Donations pages and the bottom bar between them are gone
      this.homeShell = options.homeShell;
      this.settingsPage = options.settingsPage;
      this.connectivityBanner = options.connectivityBanner;
      this.connectivityBannerText = options.connectivityBannerText || null;

      this.connectivityState = "backendAccessible";
      this._timersStarted = false;
      this._connectivityCheckInFlight = false;
      this._requestedConnectivityTimerId = null;
      this._connectivityMessage =
        "Connection with Teslalinux services lost. The app will restart when it comes back.";
    }

    initialize() {
      this.showMap();
      this._startConnectivityObservers();
    }

    requestConnectivityCheck(_reason) {
      if (this._requestedConnectivityTimerId !== null) {
        global.clearTimeout(this._requestedConnectivityTimerId);
      }

      this._requestedConnectivityTimerId = global.setTimeout(() => {
        this._requestedConnectivityTimerId = null;
        void this._checkConnectivity();
      }, 120);
    }

    /** The map: the display streams again, and what the settings page opened in the app (Waze's own settings) closes */
    showMap() {
      this.homeShell.hidden = false;
      this.settingsPage.hidden = true;
      this.displayController.setPaused(false);
      if (this.settingsController.onHidden) this.settingsController.onHidden();
    }

    /** Settings over the whole page, in the section it showed last; the display pauses meanwhile */
    showSettings() {
      this.homeShell.hidden = true;
      this.settingsPage.hidden = false;
      this.displayController.setPaused(true);
      void this.settingsController.setActiveSection(this.settingsController.activeSectionId);
    }

    _startConnectivityObservers() {
      if (this._timersStarted) {
        return;
      }
      this._timersStarted = true;

      this.requestConnectivityCheck("startup");

      global.setInterval(() => {
        if (this.connectivityState === "backendAccessible") {
          this.requestConnectivityCheck("periodic_online");
        }
      }, 30000);

      global.setInterval(() => {
        if (this.connectivityState !== "backendAccessible") {
          this.requestConnectivityCheck("periodic_offline");
        }
      }, 5000);
    }

    async _checkConnectivity() {
      if (this._connectivityCheckInFlight) {
        return;
      }

      this._connectivityCheckInFlight = true;
      try {
        const response = await this.apiClient.fetchHealthCheck();
        this._onConnectivitySuccess();
        // teslalinux: the app interface (Phone/Car) the device runs now, for js/carapp
        global.dispatchEvent(new CustomEvent("teslalinux-app-interface", { detail: response.headers.get("X-Teslalinux-App-Interface") }));
      } catch (_error) {
        this._onConnectivityFailure();
      } finally {
        this._connectivityCheckInFlight = false;
      }
    }

    _onConnectivityFailure() {
      this.connectivityState = "backendUnreachable";
      this.connectivityBanner.hidden = false;
      if (this.connectivityBannerText) {
        this.connectivityBannerText.textContent = this._connectivityMessage;
      } else {
        this.connectivityBanner.textContent = this._connectivityMessage;
      }
    }

    _onConnectivitySuccess() {
      if (this.connectivityState === "backendUnreachable") {
        global.location.reload();
      }
      this.connectivityState = "backendAccessible";
      this.connectivityBanner.hidden = true;
    }
  }

  TAHtml.ShellController = ShellController;
})(window);
