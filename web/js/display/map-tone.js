/*
 * teslalinux: a palette remap for the streamed map (Settings > Waze interface > Map colours), one
 * function added to the H.264 renderers' fragment shader, so it costs no extra pass and no extra
 * texture read (about 60 arithmetic operations per pixel). The app's map is left as the app draws
 * it; only the page shows it in the muted colours of the car interface's Graphite look: the map
 * surfaces (land, roads, labels, parks, water) take a neutral grey tone, the route the look's blue,
 * and everything strongly coloured (traffic, pins, the car, reports) stays as it is.
 *
 * Works in a page or in a worker (no DOM). In a renderer's WebGL program:
 *   fragment source = "precision mediump float;\n" + MapTone.GLSL + "...main() {\n" +
 *                     "  gl_FragColor = vec4(mapTone(rgb), 1.0);\n}"
 *   const tone = MapTone.locate(gl, program);   // after linkProgram
 *   MapTone.apply(gl, program, tone, "day");     // "day", "night" or null (off); leaves program in use
 *
 * Each preset is written as colours: the app's own (what its day and night map skins draw) and the
 * target, and uniforms() derives the shader's numbers from them. The preset must match what the
 * app draws (the car's night mode): a day preset on a night map leaves its route alone.
 */
(function (global) {
  "use strict";

  // How it works: each pixel as BT.601 luma and chroma (Cb, Cr). Weak chroma is map surface: its
  // luma goes through a piecewise-linear curve (computed without branches), its chroma is cut down
  // and tinted. Chroma pointing at the app's route colour is rotated to the target hue with an
  // affine luma, so the route, its antialiased edges and its street-name chips follow. Strong or
  // warm chroma is kept.
  const GLSL = [
    "uniform float uStrength;",
    "uniform float uCurveY0;",
    "uniform vec3  uCurve[5];",
    "uniform float uSurfChroma;",
    "uniform vec2  uTint;",
    "uniform vec2  uKeepC;",
    "uniform vec2  uRouteDir;",
    "uniform vec2  uRouteCos;",
    "uniform vec2  uRouteC;",
    "uniform vec2  uRouteRot;",
    "uniform vec2  uRouteY;",
    "uniform float uRouteGain;",
    "",
    "float mapToneCurve(float y) {",
    "  float r = uCurveY0;",
    "  for (int i = 0; i < 5; i++) r += uCurve[i].z * clamp(y - uCurve[i].x, 0.0, uCurve[i].y);",
    "  return r;",
    "}",
    "",
    "vec3 mapTone(vec3 rgb) {",
    "  if (uStrength <= 0.0) return rgb;",
    "  float y = dot(rgb, vec3(0.299, 0.587, 0.114));",
    "  vec2 ch = vec2((rgb.b - y) * 0.564, (rgb.r - y) * 0.713);",
    "  float c = length(ch);",
    "  vec2 dir = ch / max(c, 1e-4);",
    "  float warm = smoothstep(0.35, 0.6, dot(dir, vec2(-0.5, 0.866))) * smoothstep(0.05, 0.10, c);",
    "  float keep = max(smoothstep(uKeepC.x, uKeepC.y, c), warm);",
    "  float yOut = mix(mapToneCurve(y), y, keep);",
    "  vec2 chOut = mix(ch * uSurfChroma + uTint, ch, keep);",
    "  float route = smoothstep(uRouteCos.x, uRouteCos.y, dot(dir, uRouteDir)) * smoothstep(uRouteC.x, uRouteC.y, c);",
    "  vec2 rot = vec2(ch.x * uRouteRot.x - ch.y * uRouteRot.y, ch.x * uRouteRot.y + ch.y * uRouteRot.x) * uRouteGain;",
    "  yOut = mix(yOut, clamp(y * uRouteY.x + uRouteY.y, 0.0, 1.0), route);",
    "  chOut = mix(chOut, rot, route);",
    "  vec3 outc = vec3(yOut + chOut.y * 1.402, 0.0, yOut + chOut.x * 1.772);",
    "  outc.g = (yOut - 0.299 * outc.r - 0.114 * outc.b) / 0.587;",
    "  return mix(rgb, clamp(outc, 0.0, 1.0), uStrength);",
    "}",
    "",
  ].join("\n");

  // BT.601 luma and chroma (Cb, Cr) of a "#rrggbb" colour
  function ycc(hex) {
    const v = parseInt(hex.slice(1), 16);
    const r = ((v >> 16) & 255) / 255, g = ((v >> 8) & 255) / 255, b = (v & 255) / 255;
    const y = 0.299 * r + 0.587 * g + 0.114 * b;
    return { y: y, cb: (b - y) * 0.564, cr: (r - y) * 0.713 };
  }

  /*
   * from*: the app's colours as they arrive through the stream (its day map: purple route on a
   * near-white land; its night map: cyan route on blue slate); to*: the Graphite look's map.
   * curve: luma (in, out) points for the map surfaces; surfChroma: the share of their colour kept;
   * keepC: the chroma ramp above which a colour is left alone; routeBand: [outer, inner] half
   * angle in degrees of the route's hue band; routeC: its chroma ramp.
   */
  const PRESETS = {
    day: {
      fromRoute: "#711ed4", toRoute: "#2f64e8", fromBg: "#f4f9f4", toBg: "#e4e5e7",
      curve: [[0, 0], [0.47, 0.42], [0.70, 0.72], [0.86, 0.82], [0.95, 0.895], [1, 0.905]],
      surfChroma: 0.15, tint: "#e4e5e7", keepC: [0.16, 0.26],
      routeBand: [35, 20], routeC: [0.05, 0.12], routeCentreShift: -5,
    },
    night: {
      fromRoute: "#2dddff", toRoute: "#2f64e8", fromBg: "#232b38", toBg: "#2f3032",
      curve: [[0, 0], [0.17, 0.19], [0.33, 0.28], [0.58, 0.40], [0.84, 0.64], [1, 0.92]],
      surfChroma: 0.12, tint: "#2f3032", keepC: [0.17, 0.27],
      routeBand: [22, 12], routeC: [0.17, 0.26], routeCentreShift: 0,
    },
  };

  /** The shader's uniform values for a preset (an object as in PRESETS). */
  function uniforms(p) {
    const fr = ycc(p.fromRoute), to = ycc(p.toRoute), fb = ycc(p.fromBg), tb = ycc(p.toBg), tint = ycc(p.tint);
    const af = Math.atan2(fr.cr, fr.cb), at = Math.atan2(to.cr, to.cb);
    const centre = af + (p.routeCentreShift || 0) * Math.PI / 180;
    const rot = at - af;
    const cf = Math.hypot(fr.cb, fr.cr), ct = Math.hypot(to.cb, to.cr);
    // route luma: the route colour onto the target, the background onto the target background
    const ya = (to.y - tb.y) / ((fr.y - fb.y) || 1e-4);
    const yb = to.y - fr.y * ya;
    // the curve's points as segments: start, width, slope
    const curve = new Float32Array(15);
    for (let i = 0; i < 5; i++) {
      const a = p.curve[i], b = p.curve[i + 1], w = Math.max(b[0] - a[0], 1e-4);
      curve[3 * i] = a[0];
      curve[3 * i + 1] = w;
      curve[3 * i + 2] = (b[1] - a[1]) / w;
    }
    return {
      uStrength: 1,
      uCurveY0: p.curve[0][1],
      uCurve: curve,
      uSurfChroma: p.surfChroma,
      uTint: [tint.cb, tint.cr],
      uKeepC: p.keepC,
      uRouteDir: [Math.cos(centre), Math.sin(centre)],
      uRouteCos: [Math.cos(p.routeBand[0] * Math.PI / 180), Math.cos(p.routeBand[1] * Math.PI / 180)],
      uRouteC: p.routeC,
      uRouteRot: [Math.cos(rot), Math.sin(rot)],
      uRouteY: [ya, yb],
      uRouteGain: ct / cf,
    };
  }

  const NAMES = ["uStrength", "uCurveY0", "uCurve", "uSurfChroma", "uTint", "uKeepC", "uRouteDir", "uRouteCos",
    "uRouteC", "uRouteRot", "uRouteY", "uRouteGain"];

  /** The uniform locations of a linked program that includes GLSL. */
  function locate(gl, program) {
    const loc = {};
    for (const n of NAMES) loc[n] = gl.getUniformLocation(program, n);
    return loc;
  }

  /** Sets the remap on the linked program: a preset name ("day", "night") or null (off). */
  function apply(gl, program, loc, preset) {
    gl.useProgram(program);
    const p = typeof preset === "string" ? PRESETS[preset] : preset;
    if (!p) {
      gl.uniform1f(loc.uStrength, 0);
      return;
    }
    const u = uniforms(p);
    gl.uniform1f(loc.uStrength, u.uStrength);
    gl.uniform1f(loc.uCurveY0, u.uCurveY0);
    gl.uniform3fv(loc.uCurve, u.uCurve);
    gl.uniform1f(loc.uSurfChroma, u.uSurfChroma);
    gl.uniform2fv(loc.uTint, u.uTint);
    gl.uniform2fv(loc.uKeepC, u.uKeepC);
    gl.uniform2fv(loc.uRouteDir, u.uRouteDir);
    gl.uniform2fv(loc.uRouteCos, u.uRouteCos);
    gl.uniform2fv(loc.uRouteC, u.uRouteC);
    gl.uniform2fv(loc.uRouteRot, u.uRouteRot);
    gl.uniform2fv(loc.uRouteY, u.uRouteY);
    gl.uniform1f(loc.uRouteGain, u.uRouteGain);
  }

  global.MapTone = { GLSL: GLSL, PRESETS: PRESETS, uniforms: uniforms, locate: locate, apply: apply };
})(typeof self !== "undefined" ? self : this);
