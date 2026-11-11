(function (global) {
  "use strict";

  const TAHtml = global.TAHtml;

  class ApiClient {
    constructor(apiBaseUrl) {
      this.apiBaseUrl = String(apiBaseUrl || "").replace(/\/$/, "");
    }

    fetchSystemConfiguration() {
      return this._fetchJson(this.apiBaseUrl + "/configuration");
    }

    fetchDisplayState() {
      return this._fetchJson(this.apiBaseUrl + "/displayState");
    }

    postDisplayState(payload) {
      return this._postJson(this.apiBaseUrl + "/displayState", payload);
    }

    fetchDeviceInfo() {
      return this._fetchJson(this.apiBaseUrl + "/deviceInfo");
    }

    // teslalinux: the app's service state and its restart (services/api/api.py; not upstream routes)
    fetchAppState() {
      return this._fetchJson(this.apiBaseUrl + "/appState");
    }

    restartApp() {
      return this._postPlain(this.apiBaseUrl + "/appRestart", "1");
    }

    // teslalinux: language/region and time zone (services/api/api.py)
    fetchLocale() {
      return this._fetchJson(this.apiBaseUrl + "/locale");
    }

    setLocale(locale, timezone) {
      return this._postJson(this.apiBaseUrl + "/locale", { locale, timezone });
    }

    // teslalinux: which keyboard types into the app ("builtin" | "device"; services/api/api.py)
    fetchKeyboard() {
      return this._fetchJson(this.apiBaseUrl + "/keyboard");
    }

    setKeyboard(mode) {
      return this._postJson(this.apiBaseUrl + "/keyboard", { mode });
    }

    // teslalinux: the app's interface ("phone" | "car"; services/api/api.py)
    fetchAppInterface() {
      return this._fetchJson(this.apiBaseUrl + "/appInterface");
    }

    setAppInterface(value) {
      return this._postJson(this.apiBaseUrl + "/appInterface", { interface: value });
    }

    // teslalinux: what the device is connected to right now (services/api/api.py)
    fetchStatus() {
      return this._fetchJson(this.apiBaseUrl + "/status");
    }

    // teslalinux: hotspot name/password/country and LTE settings; resolves with {ok, message}
    fetchNetwork() {
      return this._fetchJson(this.apiBaseUrl + "/network");
    }

    setNetwork(request) {
      return this._postJsonResult(this.apiBaseUrl + "/network", request);
    }

    // teslalinux: the networks the backup Wi-Fi card sees (a scan takes a few seconds)
    scanWifi() {
      return this._fetchJson(this.apiBaseUrl + "/wifiScan");
    }

    // teslalinux: the operating system's own updates (apt / apk); resolves with {ok, message}
    // with logFrom: also the run's output from that byte offset (whole lines), for the live terminal
    fetchSystemUpdate(logFrom) {
      const query = typeof logFrom === "number" ? "?log=" + logFrom : "";
      return this._fetchJson(this.apiBaseUrl + "/systemUpdate" + query);
    }

    systemUpdateAction(action) {
      return this._postJsonResult(this.apiBaseUrl + "/systemUpdate", { action });
    }

    // teslalinux: automatic app updates from Google Play (tools/appupdate; services/api/api.py)
    fetchAppUpdate() {
      return this._fetchJson(this.apiBaseUrl + "/appUpdate");
    }

    setAppUpdateSettings(changes) {
      return this._postJson(this.apiBaseUrl + "/appUpdateSettings", changes);
    }

    checkAppUpdateNow() {
      return this._postPlain(this.apiBaseUrl + "/appUpdateCheck", "1");
    }

    // the bundle goes up as the request body (streamed by the browser); XMLHttpRequest because fetch()
    // reports no upload progress. Resolves with the verification result {ok, state, message}.
    uploadAppBundle(file, onProgress) {
      const url = this.apiBaseUrl + "/appUpdateUpload?name=" + encodeURIComponent(file.name);
      return new Promise((resolve, reject) => {
        const xhr = new XMLHttpRequest();
        xhr.open("POST", url);
        xhr.setRequestHeader("Content-Type", "application/octet-stream");
        xhr.upload.onprogress = (event) => {
          if (onProgress) {
            onProgress(event.loaded, event.lengthComputable ? event.total : file.size);
          }
        };
        xhr.upload.onload = () => {
          if (onProgress) {
            onProgress(file.size, file.size);
          }
        };
        xhr.onload = () => {
          let body = null;
          try {
            body = JSON.parse(xhr.responseText);
          } catch (_error) {
            // not JSON: nginx/API error page
          }
          if (xhr.status === 200 && body) {
            resolve(body);
          } else if (xhr.status === 413) {
            reject(new Error("the file is too large"));
          } else {
            reject(new Error((body && body.message) || "HTTP " + xhr.status));
          }
        };
        xhr.onerror = () => reject(new Error("connection lost"));
        xhr.send(file);
      });
    }

    openUpdater() {
      return this._fetch(this.apiBaseUrl + "/openUpdater");
    }

    fetchHealthCheck() {
      return this._fetch(this.apiBaseUrl + "/health");
    }

    setSoftApBand(value) {
      return this._postPlain(this.apiBaseUrl + "/softApBand", value);
    }

    setSoftApChannel(value) {
      return this._postPlain(this.apiBaseUrl + "/softApChannel", value);
    }

    setSoftApChannelWidth(value) {
      return this._postPlain(this.apiBaseUrl + "/softApChannelWidth", value);
    }

    setSoftApState(value) {
      return this._postPlain(this.apiBaseUrl + "/softApState", value);
    }

    setOfflineModeState(value) {
      return this._postPlain(this.apiBaseUrl + "/offlineModeState", value);
    }

    setOfflineModeTelemetryState(value) {
      return this._postPlain(this.apiBaseUrl + "/offlineModeTelemetryState", value);
    }

    setOfflineModeTeslaFirmwareDownloads(value) {
      return this._postPlain(this.apiBaseUrl + "/offlineModeTeslaFirmwareDownloads", value);
    }

    setBrowserAudioState(value) {
      return this._postPlain(this.apiBaseUrl + "/browserAudioState", value);
    }

    setBrowserAudioVolume(value) {
      return this._postPlain(this.apiBaseUrl + "/browserAudioVolume", value);
    }

    setGpsState(value) {
      return this._postPlain(this.apiBaseUrl + "/gpsState", value);
    }

    async _fetch(url, options) {
      const response = await fetch(url, {
        cache: "no-store",
        credentials: "same-origin",
        ...(options || {}),
      });
      if (!response.ok) {
        throw new Error("HTTP " + response.status + " for " + url);
      }
      return response;
    }

    async _fetchJson(url, options) {
      const response = await this._fetch(url, options);
      return response.json();
    }

    async _postJson(url, payload) {
      await this._fetch(url, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify(payload),
      });
    }

    // a POST whose JSON answer {ok, message} matters also on failure (the reason is shown to the user)
    async _postJsonResult(url, payload) {
      let response;
      try {
        response = await fetch(url, {
          method: "POST",
          cache: "no-store",
          credentials: "same-origin",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(payload),
        });
      } catch (_error) {
        return { ok: false, message: "The device did not answer" };
      }
      let body = null;
      try {
        body = await response.json();
      } catch (_error) {
        body = null;
      }
      if (body && typeof body === "object") {
        return { ok: response.ok && body.ok !== false, message: String(body.message || "") };
      }
      return { ok: response.ok, message: response.ok ? "" : "HTTP " + response.status };
    }

    async _postPlain(url, value) {
      await this._fetch(url, {
        method: "POST",
        headers: {
          "Content-Type": "text/plain",
        },
        body: String(value),
      });
    }
  }

  TAHtml.ApiClient = ApiClient;
})(window);
