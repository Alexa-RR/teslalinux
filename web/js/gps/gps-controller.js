(function (global) {
  "use strict";

  const TAHtml = global.TAHtml;

  class GpsController {
    constructor(options) {
      this.socketUrl = options.socketUrl;
      this.isEnabled = Boolean(options.isEnabled);

      this.gpsSocket = null;
      this.gpsRunning = false;
      this.gpsUpdateIntervalId = null;
      this._hasRequestedPermission = false;
    }

    initialize() {
      if (!this.isEnabled) {
        return;
      }
      this._connectSocket();
    }

    setEnabled(enabled) {
      const nextEnabled = Boolean(enabled);
      if (nextEnabled === this.isEnabled) {
        return;
      }
      this.isEnabled = nextEnabled;
      if (nextEnabled) {
        this._connectSocket();
      } else {
        this._stop();
      }
    }

    _connectSocket() {
      if (this.gpsSocket || !this.isEnabled) {
        return;
      }

      const socket = new ReconnectingWebSocket(this.socketUrl);

      socket.onopen = () => {
        this.gpsRunning = true;
      };
      socket.onclose = () => {
        this.gpsRunning = false;
      };
      socket.onerror = (error) => {
        TAHtml.log("GPS websocket error: " + String(error));
      };
      socket.onmessage = (event) => {
        TAHtml.log("GPS websocket message: " + String(event.data || ""));
      };

      this.gpsSocket = socket;
      void this._checkPermissionAndStartUpdates();
    }

    async _checkPermissionAndStartUpdates() {
      if (!this.isEnabled) {
        return;
      }

      try {
        const permissionStatus = await navigator.permissions.query({ name: "geolocation" });

        // teslalinux: a permission given (or taken back) later, from the browser's site settings,
        // takes effect without reloading the page
        if (!this._watchingPermission) {
          this._watchingPermission = true;
          permissionStatus.addEventListener("change", () => {
            if (permissionStatus.state === "granted") {
              this._startLocationUpdates();
            } else if (permissionStatus.state === "denied") {
              this._stopLocationUpdates();
            }
          });
        }

        if (permissionStatus.state === "granted") {
          this._startLocationUpdates();
          return;
        }

        if (permissionStatus.state === "prompt") {
          if (this._hasRequestedPermission) {
            return;
          }
          this._hasRequestedPermission = true;
          this._requestPermission();
          return;
        }

        TAHtml.log("Location access denied.");
      } catch (_error) {
        if (this._hasRequestedPermission) {
          return;
        }
        this._hasRequestedPermission = true;
        this._requestPermission();
      }
    }

    // teslalinux: upstream treated any failure of the first request as a refusal and never asked
    // again. Only PERMISSION_DENIED is one: POSITION_UNAVAILABLE and TIMEOUT mean there is no fix yet
    // (the car in a garage or a tunnel when the page opens), and the updates start anyway so the
    // position arrives as soon as the car has one.
    _requestPermission() {
      navigator.geolocation.getCurrentPosition(
        () => this._startLocationUpdates(),
        (error) => {
          if (error && error.code === 1) {
            TAHtml.log("Location access denied.");
            return;
          }
          TAHtml.log("No position yet (" + String(error && error.message) + "); updates start anyway.");
          this._startLocationUpdates();
        },
      );
    }

    _stopLocationUpdates() {
      if (this.gpsUpdateIntervalId !== null) {
        global.clearInterval(this.gpsUpdateIntervalId);
        this.gpsUpdateIntervalId = null;
      }
    }

    _startLocationUpdates() {
      if (this.gpsUpdateIntervalId !== null || !this.isEnabled) {
        return;
      }

      this.gpsUpdateIntervalId = global.setInterval(() => {
        navigator.geolocation.getCurrentPosition(
          (position) => this._updateLocation(position),
          (error) => TAHtml.log("GPS update error: " + String(error && error.message)),
        );
      }, 1000);
    }

    _updateLocation(position) {
      if (!this.gpsRunning || !this.gpsSocket || !position || !position.coords) {
        return;
      }
      this.gpsSocket.send(this._toLocationData(position));
    }

    _stop() {
      this.gpsRunning = false;

      if (this.gpsUpdateIntervalId !== null) {
        global.clearInterval(this.gpsUpdateIntervalId);
        this.gpsUpdateIntervalId = null;
      }

      if (this.gpsSocket) {
        try {
          this.gpsSocket.close();
        } catch (_error) {
          // no-op
        }
        this.gpsSocket = null;
      }
    }

    _toLocationData(position) {
      return JSON.stringify({
        latitude: String(position.coords.latitude),
        longitude: String(position.coords.longitude),
        speed: String(position.coords.speed),
        bearing: String(position.coords.heading),
        vertical_accuracy: String(position.coords.accuracy),
        timestamp: String(Date.now()),
      });
    }
  }

  TAHtml.GpsController = GpsController;
})(window);
