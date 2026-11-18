// teslalinux: voice input through this browser's own microphone and speech recognition (the Web
// Speech API). Two users: the car interface's search field (Car mode: the page is the car's host,
// whose microphone button searches by voice), and the app's speech recognizer (Phone mode: ATL's
// SpeechRecognizer asks this page over /sockets/speech, docs/UPSTREAM-NOTES.md "Speech
// recognition"). The language is this browser's choice (Settings: Voice language), Hebrew or
// English; a request from the app may name its own.
(function (global) {
  "use strict";

  const Recognition = global.SpeechRecognition || global.webkitSpeechRecognition || null;
  const LANGUAGE_KEY = "teslalinux.voice.language";
  const LANGUAGES = [
    { value: "he-IL", label: "עברית (Hebrew)" },
    { value: "en-US", label: "English" },
  ];

  function readLanguage() {
    try {
      const v = global.localStorage.getItem(LANGUAGE_KEY);
      if (v && LANGUAGES.some((l) => l.value === v)) return v;
    } catch (_e) {
      // storage blocked: the default
    }
    const nav = String(global.navigator.language || "").toLowerCase();
    return nav.startsWith("he") || nav.startsWith("iw") ? "he-IL" : "en-US";
  }

  const TLSpeech = {
    LANGUAGES,

    /** whether this browser has speech recognition at all */
    supported() {
      return Recognition !== null;
    },

    language() {
      return readLanguage();
    },

    setLanguage(value) {
      try {
        global.localStorage.setItem(LANGUAGE_KEY, value);
      } catch (_e) {
        // storage blocked: the choice lasts for this page only
      }
      TLSpeech._session = null;
      for (const l of TLSpeech._languageListeners) l(value);
    },

    _languageListeners: [],
    onLanguageChange(fn) {
      TLSpeech._languageListeners.push(fn);
    },

    /**
     * One recognition: the browser asks for the microphone the first time. handlers: onReady
     * (listening), onSpeechStart, onSpeechEnd, onPartial(texts), onResult(texts, confidences),
     * onError(code: the Web Speech API's names, plus "no-match" when it heard nothing it could
     * turn into text, and "unsupported"). Exactly one of onResult and onError ends it. Returns
     * {stop() (end the audio, deliver what was heard), abort() (no result)}.
     */
    listen(options) {
      const o = options || {};
      let done = false;
      const finish = (fn) => {
        if (done) return;
        done = true;
        if (fn) fn();
      };
      if (!Recognition) {
        global.setTimeout(() => finish(() => o.onError && o.onError("unsupported")), 0);
        return { stop() {}, abort() {} };
      }
      const rec = new Recognition();
      rec.lang = o.lang || readLanguage();
      rec.interimResults = Boolean(o.partial);
      rec.maxAlternatives = Math.max(1, Math.min(10, o.maxResults || 5));
      rec.continuous = false;
      let finalTexts = null;
      let finalConfidences = null;
      rec.onaudiostart = () => o.onReady && !done && o.onReady();
      rec.onspeechstart = () => o.onSpeechStart && !done && o.onSpeechStart();
      rec.onspeechend = () => o.onSpeechEnd && !done && o.onSpeechEnd();
      rec.onresult = (event) => {
        if (done) return;
        const result = event.results[event.results.length - 1];
        const texts = [];
        const confidences = [];
        for (let i = 0; i < result.length; i += 1) {
          texts.push(result[i].transcript.trim());
          confidences.push(typeof result[i].confidence === "number" ? result[i].confidence : -1);
        }
        if (result.isFinal) {
          finalTexts = texts;
          finalConfidences = confidences;
        } else if (o.onPartial) {
          o.onPartial(texts);
        }
      };
      rec.onerror = (event) => {
        finish(() => o.onError && o.onError(String(event.error || "aborted")));
      };
      rec.onend = () => {
        if (finalTexts && finalTexts.length && finalTexts[0]) {
          finish(() => o.onResult && o.onResult(finalTexts, finalConfidences));
        } else {
          finish(() => o.onError && o.onError("no-match"));
        }
      };
      try {
        rec.start();
      } catch (_e) {
        global.setTimeout(() => finish(() => o.onError && o.onError("busy")), 0);
      }
      return {
        stop() {
          try {
            rec.stop();
          } catch (_e) {
            // already ended
          }
        },
        abort() {
          done = true;
          try {
            rec.abort();
          } catch (_e) {
            // already ended
          }
        },
      };
    },
  };

  /**
   * Phone mode: the app's speech recognizer. Connected to ATL's channel, it says whether this
   * browser can recognise speech and runs the app's sessions ({"type":"listen"|"stop"|"cancel"}),
   * sending back their events.
   */
  class SpeechController {
    constructor(options) {
      this.socketUrl = options.socketUrl;
      this.socket = null;
      this.sessions = new Map();
    }

    initialize() {
      const socket = new ReconnectingWebSocket(this.socketUrl);
      socket.onopen = () => {
        this._send({ type: "hello", recognition: TLSpeech.supported() });
      };
      socket.onclose = () => {
        for (const s of this.sessions.values()) s.abort();
        this.sessions.clear();
      };
      socket.onmessage = (event) => {
        let m;
        try {
          m = JSON.parse(String(event.data));
        } catch (_e) {
          return;
        }
        if (m.type === "listen") this._listen(m);
        else if (m.type === "stop" && this.sessions.has(m.id)) this.sessions.get(m.id).stop();
        else if (m.type === "cancel" && this.sessions.has(m.id)) {
          this.sessions.get(m.id).abort();
          this.sessions.delete(m.id);
        }
      };
      this.socket = socket;
    }

    _send(message) {
      if (this.socket && this.socket.readyState === 1) this.socket.send(JSON.stringify(message));
    }

    _listen(m) {
      const id = m.id;
      // the app's language when it names one this page offers, else this browser's choice
      const asked = String(m.lang || "");
      const lang = TLSpeech.LANGUAGES.some((l) => l.value.split("-")[0] === asked.split("-")[0])
        ? asked
        : TLSpeech.language();
      const session = TLSpeech.listen({
        lang,
        partial: Boolean(m.partial),
        maxResults: m.maxResults,
        onReady: () => this._send({ type: "ready", id }),
        onSpeechStart: () => this._send({ type: "speechStart", id }),
        onSpeechEnd: () => this._send({ type: "speechEnd", id }),
        onPartial: (texts) => this._send({ type: "partial", id, texts }),
        onResult: (texts, confidences) => {
          this.sessions.delete(id);
          this._send({ type: "results", id, texts, confidences });
        },
        onError: (error) => {
          this.sessions.delete(id);
          this._send({ type: "error", id, error });
        },
      });
      this.sessions.set(id, session);
    }
  }

  global.TLSpeech = TLSpeech;
  global.TAHtml = global.TAHtml || {};
  global.TAHtml.SpeechController = SpeechController;
})(window);
