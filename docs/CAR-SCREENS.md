# Car screens

What the Tesla browser reports about its screen, measured in real cars with `web/probe.html`
(open `https://car.alexahub.dev/probe.html` in the car; it shows every value and sends them to the
server as `GET /probe-report?...`, which nginx logs in its access log with a 404). Use these numbers
to size the display stream, the app's density and the car page's layout.

## Model 3 (measured Nov 19, 2026)

Browser window at its normal (not fullscreen) size, the car page's settings open before the probe.

| Value | Reported | Notes |
|---|---|---|
| `innerWidth` x `innerHeight` | 773 x 601 | CSS px, the page's viewport |
| `visualViewport` width x height | 773.20263671875 x 601.3071899414062 | fractional: the viewport is not a whole number of CSS px |
| `visualViewport.scale` | 1 | no pinch zoom |
| `outerWidth` x `outerHeight` | 773 x 667 | 66 CSS px of browser chrome above the page (address bar) |
| `documentElement.clientWidth` x `clientHeight` | 773 x 601 | no scrollbars |
| `devicePixelRatio` | 1.5299999713897705 | 1.53 |
| `screen.width` x `screen.height` | 1254 x 784 | CSS px: 1254 x 1.53 = 1918.6, 784 x 1.53 = 1199.5, the 1920 x 1200 panel |
| `screen.availWidth` x `availHeight` | 1254 x 784 | |
| `screen.colorDepth` | 24 | |
| `screen.orientation.type` | landscape-primary | |
| `document.fullscreenElement` | none | |
| `(display-mode: fullscreen)` | false | |
| `(pointer: coarse)` | false | the touchscreen reports as a fine pointer |
| `(hover: hover)` | true | |
| `navigator.maxTouchPoints` | 16 | |
| `navigator.hardwareConcurrency` | 8 | |
| `navigator.deviceMemory` | 2 | the API's capped value, not the car's RAM |
| WebCodecs `VideoDecoder` | present | the h264 (WebCodecs) renderer works |
| User agent | `Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36` | Chromium 148 on x86_64 Linux; 8 threads means the AMD Ryzen computer (MCU 3), the Intel Atom one has 4 |
| Physical viewport (`innerWidth`/`innerHeight` x DPR) | 1183 x 920 device px | what the stream should fill pixel for pixel |

Raw report (nginx access log, the VM behind the Mac's hotspot, so the client address is QEMU's):

```
10.0.2.2 - - [19/Nov/2026:21:42:39 +0200] "GET /probe-report?reason=load&innerWidth=773&innerHeight=601&outerWidth=773&outerHeight=667&clientWidth=773&clientHeight=601&vvWidth=773.20263671875&vvHeight=601.3071899414062&vvScale=1&devicePixelRatio=1.5299999713897705&screenWidth=1254&screenHeight=784&availWidth=1254&availHeight=784&colorDepth=24&orientation=landscape-primary&fullscreen=false&displayModeFullscreen=false&pointerCoarse=false&hover=true&maxTouchPoints=16&hardwareConcurrency=8&deviceMemory=2&webcodecs=true&ua=Mozilla%2F5.0%20(X11%3B%20Linux%20x86_64)%20AppleWebKit%2F537.36%20(KHTML%2C%20like%20Gecko)%20Chrome%2F148.0.0.0%20Safari%2F537.36&physicalViewport=1183x920
```

### What this means for the stream

- The responsive display size is computed from CSS px (`visualViewport`), not device px: 773 x 601
  became 832 x 608 (width aligned up to 64, height to 32, `_calculateOptimalSize` in
  `web/js/display/display-controller.js`). The browser then stretches 832 x 608 onto 1183 x 920
  device px (1.42 x wider, 1.51 x taller), so every frame is upscaled: soft text and
  magnified stair steps on the map's edges.
- A stream that matches the panel needs about 1183 x 920 (aligned: 1216 x 928, or 1184 x 928 with
  16-px alignment). The upstream 832p preset caps the shortest side at 832, which would give
  1088 x 832 for this aspect ratio.
- The app's density follows the same scale: at DPR 1.53 a CSS px is 1.53 device px, so a stream at
  device-px size wants a density 1.53 times the one used for the CSS-px size.
- 66 CSS px of browser chrome sit above the page in the normal window; fullscreen (the upstream
  youtu.be trick) would give the whole 1254 x 784 CSS px (1920 x 1200 device px).

### What was done about it

- The H.264 renderers' canvas backing store is in device pixels for every preset (it was in CSS px:
  the frame was shrunk into a 773x564 canvas, then enlarged 1.53 times by the browser).
- Settings > Display > Resolution > **Native (screen pixels)**: on this car the stream is 1184 x 928 at
  density 284, shown pixel for pixel (773.86 x 606.54 CSS px; 0.9 and 5.5 CSS px fall outside the
  window and are clipped). Checked on the car on Nov 19, 2026: sharper; the map's edges still show
  stair steps (Waze's car-mode map asks for no multisampling). Not the default yet: about 2.2 times
  the pixels of 832p, so the Pi's frame rate and its encoder at 60 Hz must be measured first.
- `tools/debian/car-browser.py --size 773x601 --dpr 1.53` emulates this browser in a headless Chrome.
  Every test browser posts its own display size to the device: restore the car's afterwards.
