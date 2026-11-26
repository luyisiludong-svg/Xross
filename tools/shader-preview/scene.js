// Layered scenes in the shader preview (Live backgrounds § 3f): a port of ScenePoser.kt and
// GlScene.kt's draw, using the app's own scene_layer / scene_particles programs. index.html loads
// it and calls sceneLoad / sceneDraw while a scene.json is open.
//
// The same rules as the device: the theme gradient first; image, sprite and video layers posed and
// blended (premultiplied, destination alpha kept); particle layers as one instanced draw each;
// shader layers over everything below them as uBelow (unit 7), inside their rect at their opacity.
// What isn't ported: the budget (the app reports and drops layers over it), the poster fallback
// and the watchdog. Timings here say nothing about a phone.
'use strict';

const SCENE_SHADERS = '../../app/src/main/assets/shaders/';
const PARALLAX_SHIFT = 0.03, REFERENCE_W = 1280, CYCLES = 256, BELOW_UNIT = 7;
const ANCHORS = {
  CENTER: [0.5, 0.5], TOP: [0.5, 0], BOTTOM: [0.5, 1], LEFT: [0, 0.5], RIGHT: [1, 0.5],
  TOP_LEFT: [0, 0], TOP_RIGHT: [1, 0], BOTTOM_LEFT: [0, 1], BOTTOM_RIGHT: [1, 1],
};

let scene = null; // { layers, sharp, quad, particles, targets: [a, b], textures, video }

function sceneColor(s, fallback) {
  const m = /^#?([0-9a-f]{6}|[0-9a-f]{8})$/i.exec(String(s || '').trim());
  if (!m) return fallback;
  const hex = m[1].length === 6 ? 'ff' + m[1] : m[1];
  const n = parseInt(hex, 16) >>> 0;
  return [(n >>> 16 & 255) / 255, (n >>> 8 & 255) / 255, (n & 255) / 255, (n >>> 24 & 255) / 255];
}
const pair = (v, d) => Array.isArray(v) && v.length === 2 ? v.map(Number) : d;
const upper = (v, d) => (v === undefined || v === null) ? d : String(v).trim().toUpperCase();

// The layer as SceneLayer / LayerLook hold it (defaults included; no problem reporting here).
function sceneLayer(o, i) {
  const fit = upper(o.fit, 'FILL').replace('COVER', 'FILL');
  const sway = typeof o.sway === 'number' ? { angle: o.sway } : o.sway;
  const pulse = typeof o.pulse === 'number' ? { scale: o.pulse } : o.pulse;
  const drive = d => d && { scale: d.scale || 0, opacity: d.opacity || 0, offset: pair(d.offset, [0, 0]), angle: d.angle || 0 };
  const L = {
    index: i, type: String(o.type || '').toLowerCase(), path: o.path, wrap: upper(o.wrap, 'CLAMP'),
    opacity: o.opacity ?? 1, blend: upper(o.blend, 'NORMAL'), rect: Array.isArray(o.rect) && o.rect.length === 4 ? o.rect.map(Number) : null,
    fit, anchor: ANCHORS[upper(o.anchor, 'CENTER')] || ANCHORS.CENTER, parallax: o.parallax || 0, mask: o.mask || null,
    scroll: pair(o.scroll, [0, 0]),
    sway: sway && { angle: sway.angle || 0, offset: pair(sway.offset, [0, 0]), period: sway.period || 6 },
    pulse: pulse && { scale: pulse.scale || 0, opacity: pulse.opacity || 0, period: pulse.period || 4 },
    audio: drive(o.audio), transition: drive(o.transition), daylight: drive(o.daylight),
  };
  if (L.type === 'sprite') {
    [L.cols, L.rows] = pair(o.frames, [1, 1]).map(n => Math.max(1, Math.floor(n)));
    L.fps = o.fps || 8;
    L.count = o.count || L.cols * L.rows;
  }
  if (L.type === 'shader') L.images = (o.images || []).slice(0, 4);
  if (L.type === 'particles') {
    L.rate = o.rate || 1;
    L.life = pair(o.life, [2, 4]);
    const v = Array.isArray(o.velocity) && o.velocity.length === 2 ? o.velocity : [[0, 0], [-40, -20]];
    L.velX = pair(v[0], [0, 0]);
    L.velY = pair(v[1], [0, 0]);
    L.size = pair(o.size, [4, 8]);
    const c = [].concat(o.color ?? ['#ffffffff', '#00ffffff']);
    L.color0 = sceneColor(c[0], [1, 1, 1, 1]);
    L.color1 = sceneColor(c[c.length - 1], [1, 1, 1, 0]);
    L.count = Math.max(1, Math.ceil(L.rate * L.life[1]));
    L.period = L.count / L.rate;
  }
  return L;
}

// ScenePoser.pose: a parallelogram in render pixels (y down), the texture window and the opacity.
function scenePose(L, texW, texH, sw, sh, inp) {
  if (texW <= 0 || texH <= 0 || sw <= 0 || sh <= 0) return null;
  const ref = Math.max(sw, sh) / REFERENCE_W;
  const cellW = texW / (L.cols || 1), cellH = texH / (L.rows || 1);
  const shiftX = PARALLAX_SHIFT * L.parallax * sw, shiftY = PARALLAX_SHIFT * L.parallax * sh;
  let bx, by, bw, bh;
  if (L.rect) {
    [bx, by, bw, bh] = [L.rect[0] * sw, L.rect[1] * sh, L.rect[2] * sw, L.rect[3] * sh];
  } else {
    if (L.fit === 'STRETCH') {
      bw = sw + 2 * shiftX; bh = sh + 2 * shiftY;
    } else {
      const cover = Math.max((sw + 2 * shiftX) / cellW, (sh + 2 * shiftY) / cellH);
      const contain = Math.min(sw / cellW, sh / cellH);
      const k = L.fit === 'FIT' ? contain : L.fit === 'CENTER' ? Math.min(ref, contain) : cover;
      bw = cellW * k; bh = cellH * k;
    }
    bx = L.anchor[0] * (sw - bw); by = L.anchor[1] * (sh - bh);
  }
  const t = inp.time;
  let angle = 0, scale = 1, opacity = L.opacity, dx = 0, dy = 0;
  const wave = period => Math.sin(2 * Math.PI * ((t / period) % 1));
  if (L.sway) {
    const w = wave(L.sway.period);
    angle += L.sway.angle * w; dx += L.sway.offset[0] * ref * w; dy += L.sway.offset[1] * ref * w;
  }
  if (L.pulse) {
    const w = wave(L.pulse.period);
    scale += L.pulse.scale * w; opacity += L.pulse.opacity * w;
  }
  const drive = (d, level) => {
    if (!d || !level) return;
    scale += d.scale * level; opacity += d.opacity * level; angle += d.angle * level;
    dx += d.offset[0] * ref * level; dy += d.offset[1] * ref * level;
  };
  drive(L.audio, Math.min(1, Math.max(0, inp.bass + 0.5 * inp.beat)));
  drive(L.transition, inp.transition);
  drive(L.daylight, inp.daylight);
  const pull = (f, tilt) => Math.min(1, Math.max(-1, (f - 0.5) * 2 + tilt));
  dx -= pull(inp.focusX, inp.tiltX) * shiftX;
  dy -= pull(inp.focusY, inp.tiltY) * shiftY;
  opacity = Math.min(1, Math.max(0, opacity));
  scale = Math.max(0, scale);
  if (opacity <= 1 / 512 || scale === 0 || bw <= 0 || bh <= 0) return null;
  const rad = angle * Math.PI / 180, c = Math.cos(rad), s = Math.sin(rad);
  const w = bw * scale, h = bh * scale, cx = bx + bw / 2 + dx, cy = by + bh / 2 + dy;
  const p = { axXx: c * w, axXy: s * w, axYx: -s * h, axYy: c * h, u0: 0, v0: 0, du: 1, dv: 1, opacity, scale };
  p.ox = cx - (p.axXx + p.axYx) / 2;
  p.oy = cy - (p.axXy + p.axYy) / 2;
  if (L.type === 'sprite') {
    const n = L.count <= 1 ? 0 : ((Math.floor(t * L.fps) % L.count) + L.count) % L.count;
    p.du = 1 / L.cols; p.dv = 1 / L.rows;
    p.u0 = (n % L.cols) * p.du; p.v0 = Math.floor(n / L.cols) * p.dv;
  }
  const scrolled = (speed, size) => { if (!speed || !size) return 0; const k = t * speed / size; return k - Math.floor(k); };
  p.u0 -= scrolled(L.scroll[0], texW);
  p.v0 -= scrolled(L.scroll[1], texH);
  return p;
}

function sceneLink(vs, fs, what) {
  const p = gl.createProgram();
  for (const [type, src] of [[gl.VERTEX_SHADER, vs], [gl.FRAGMENT_SHADER, fs]]) {
    const s = gl.createShader(type);
    gl.shaderSource(s, src);
    gl.compileShader(s);
    if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(`${what}: ${gl.getShaderInfoLog(s)}`);
    gl.attachShader(p, s);
  }
  gl.linkProgram(p);
  if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(`${what}: ${gl.getProgramInfoLog(p)}`);
  const loc = new Proxy({}, { get: (c, n) => (n in c ? c[n] : (c[n] = gl.getUniformLocation(p, n))) });
  return { p, loc };
}

// A layer texture as GlScene uploads it: premultiplied, bottom row first, mipmapped.
async function sceneTexture(path, wrap) {
  const blob = await (await fetch(path)).blob();
  const bmp = await createImageBitmap(blob, { imageOrientation: 'flipY', premultiplyAlpha: 'premultiply', colorSpaceConversion: 'none' });
  const tex = gl.createTexture();
  gl.bindTexture(gl.TEXTURE_2D, tex);
  gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, gl.RGBA, gl.UNSIGNED_BYTE, bmp);
  gl.generateMipmap(gl.TEXTURE_2D);
  const w = { REPEAT: gl.REPEAT, MIRROR: gl.MIRRORED_REPEAT }[wrap] || gl.CLAMP_TO_EDGE;
  for (const [k, v] of [[gl.TEXTURE_MIN_FILTER, gl.LINEAR_MIPMAP_LINEAR], [gl.TEXTURE_MAG_FILTER, gl.LINEAR],
                        [gl.TEXTURE_WRAP_S, w], [gl.TEXTURE_WRAP_T, w]])
    gl.texParameteri(gl.TEXTURE_2D, k, v);
  return { tex, w: bmp.width, h: bmp.height };
}

// A shader layer's own images: straight alpha, as § 1 binds them.
async function sceneShaderImages(list, base) {
  const out = [];
  for (const item of list) {
    const spec = typeof item === 'string' ? { path: item } : item;
    try {
      const blob = await (await fetch(base + spec.path)).blob();
      const bmp = await createImageBitmap(blob, { imageOrientation: 'flipY', premultiplyAlpha: 'none', colorSpaceConversion: 'none' });
      const tex = gl.createTexture();
      gl.bindTexture(gl.TEXTURE_2D, tex);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, gl.RGBA, gl.UNSIGNED_BYTE, bmp);
      const wrap = { REPEAT: gl.REPEAT, MIRROR: gl.MIRRORED_REPEAT }[upper(spec.wrap, '')] || gl.CLAMP_TO_EDGE;
      if (spec.mipmap) gl.generateMipmap(gl.TEXTURE_2D);
      for (const [k, v] of [[gl.TEXTURE_MIN_FILTER, spec.mipmap ? gl.LINEAR_MIPMAP_LINEAR : gl.LINEAR], [gl.TEXTURE_MAG_FILTER, gl.LINEAR],
                            [gl.TEXTURE_WRAP_S, wrap], [gl.TEXTURE_WRAP_T, wrap]])
        gl.texParameteri(gl.TEXTURE_2D, k, v);
      out.push({ tex, w: bmp.width, h: bmp.height });
    } catch (e) {
      out.push(null);
      $('log').textContent += `image ${spec.path}: ${e.message}\n`;
    }
  }
  return out;
}

// Loads scene.json at `path`; files are pack-relative, so `base` is the pack's folder (theme.json's, else the scene's).
async function sceneLoad(path, base, prelude) {
  const [json, lv, lf, pv, pf] = await Promise.all([
    fetch(path).then(r => { if (!r.ok) throw new Error(path + ': ' + r.status); return r.json(); }),
    ...['scene_layer.vert', 'scene_layer.frag', 'scene_particles.vert', 'scene_particles.frag'].map(f => fetch(SCENE_SHADERS + f).then(r => r.text())),
  ]);
  const layers = (json.layers || []).map(sceneLayer);
  const s = {
    layers, sharp: upper(json.resolution, 'SOFT') === 'SHARP',
    quad: sceneLink(lv, lf, 'scene_layer'), particles: sceneLink(pv, pf, 'scene_particles'),
    textures: {}, videoPath: null, targets: null,
  };
  const want = (p, wrap) => p && (s.textures[p + '|' + wrap] ??= sceneTexture(base + p, wrap).catch(e => {
    $('log').textContent += `image ${p}: ${e.message}\n`;
    return null;
  }));
  for (const L of layers) {
    if (L.type === 'image' || L.type === 'sprite') want(L.path, L.type === 'image' ? L.wrap : 'CLAMP');
    if (L.type === 'particles') want(L.path, 'CLAMP');
    want(L.mask, 'CLAMP');
    if (L.type === 'video' && !s.videoPath) s.videoPath = base + L.path;
    if (L.type === 'shader') {
      try {
        const src = await fetch(base + L.path).then(r => { if (!r.ok) throw new Error(r.status); return r.text(); });
        const own = /^\s*#version/.test(src);
        const lines = own ? 0 : prelude.split('\n').length - (prelude.endsWith('\n') ? 1 : 0);
        L.program = link(own ? src : prelude + (prelude.endsWith('\n') ? '' : '\n') + src, lines);
        L.loc = new Proxy({}, { get: (c, n) => (n in c ? c[n] : (c[n] = gl.getUniformLocation(L.program, n))) });
        L.shaderImages = await sceneShaderImages(L.images, base);
      } catch (e) {
        // As on the device: a layer that doesn't compile is left out, the rest draws.
        $('log').textContent += `layers[${L.index}] ${L.path}: ${e.message} · left out\n`;
      }
    }
  }
  for (const k of Object.keys(s.textures)) s.textures[k] = await s.textures[k];
  if (s.videoPath) loadVideo(s.videoPath);
  scene = s;
  return s;
}

function sceneTargets() {
  if (scene.targets && scene.targets[0].w === W && scene.targets[0].h === H) return scene.targets;
  scene.targets = [target(W, H), target(W, H)];
  return scene.targets;
}

function sceneBlend(mode) {
  const f = {
    ADD: [gl.ONE, gl.ONE], SCREEN: [gl.ONE, gl.ONE_MINUS_SRC_COLOR], MULTIPLY: [gl.DST_COLOR, gl.ONE_MINUS_SRC_ALPHA],
  }[mode] || [gl.ONE, gl.ONE_MINUS_SRC_ALPHA];
  gl.blendFuncSeparate(f[0], f[1], gl.ZERO, gl.ONE);
}

function sceneCopy(src, dst) {
  gl.disable(gl.BLEND);
  gl.useProgram(blit);
  gl.bindFramebuffer(gl.FRAMEBUFFER, dst ? dst.fb : null);
  gl.viewport(0, 0, W, H);
  gl.activeTexture(gl.TEXTURE0);
  gl.bindTexture(gl.TEXTURE_2D, src.tex);
  gl.uniform1i(gl.getUniformLocation(blit, 'uSrc'), 0);
  gl.uniform2f(gl.getUniformLocation(blit, 'uSize'), W, H);
  gl.drawArrays(gl.TRIANGLES, 0, 3);
}

// One frame: `inp` is SceneInputs (time, focus y down, tilt, bass, beat, transition, daylight); `videoFrame` { tex, w, h } or null.
function sceneDraw(inp, videoFrame, dt) {
  const s = scene;
  const t = sceneTargets();
  let cur = 0;
  gl.bindFramebuffer(gl.FRAMEBUFFER, t[cur].fb);
  gl.viewport(0, 0, W, H);
  const q = s.quad;
  const useQuad = () => {
    gl.useProgram(q.p);
    gl.uniform1i(q.loc.uTex, 0);
    gl.uniform1i(q.loc.uMask, 1);
  };
  useQuad();
  gl.disable(gl.BLEND);
  gl.uniform4fv(q.loc.uColorTop, palette.top);
  gl.uniform4fv(q.loc.uColorBottom, palette.bottom);
  gl.uniform1i(q.loc.uMode, 1);
  gl.uniform2f(q.loc.uOrigin, -1, 1); gl.uniform2f(q.loc.uAxisX, 2, 0); gl.uniform2f(q.loc.uAxisY, 0, -2);
  gl.uniform4f(q.loc.uUv, 0, 0, 1, 1);
  gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
  gl.uniform1i(q.loc.uMode, 0);
  gl.enable(gl.BLEND);
  const bindMask = (L) => {
    const m = L.mask && s.textures[L.mask + '|CLAMP'];
    gl.activeTexture(gl.TEXTURE1);
    gl.bindTexture(gl.TEXTURE_2D, m ? m.tex : null);
    gl.activeTexture(gl.TEXTURE0);
    return m ? 1 : 0;
  };
  for (const L of s.layers) {
    if (L.type === 'shader') {
      if (!L.program || L.opacity <= 1 / 512) continue;
      const partial = L.rect || L.opacity < 1;
      if (partial) sceneCopy(t[cur], t[1 - cur]);
      const below = t[cur];
      cur = 1 - cur;
      gl.bindFramebuffer(gl.FRAMEBUFFER, t[cur].fb);
      gl.viewport(0, 0, W, H);
      if (L.rect) {
        const r = L.rect, cl = v => Math.min(Math.max(v, 0), 1);
        const x0 = Math.round(cl(r[0]) * W), x1 = Math.round(cl(r[0] + r[2]) * W);
        const top = Math.round(cl(r[1]) * H), bottom = Math.round(cl(r[1] + r[3]) * H);
        gl.enable(gl.SCISSOR_TEST);
        gl.scissor(x0, H - bottom, x1 - x0, bottom - top);
      }
      if (L.opacity < 1) {
        gl.enable(gl.BLEND);
        gl.blendColor(0, 0, 0, L.opacity);
        gl.blendFuncSeparate(gl.CONSTANT_ALPHA, gl.ONE_MINUS_CONSTANT_ALPHA, gl.ZERO, gl.ONE);
      } else {
        gl.disable(gl.BLEND);
      }
      gl.useProgram(L.program);
      setUniforms(dt, L.loc, L.shaderImages);
      gl.uniform1f(L.loc.uFresh, 1);
      gl.uniform1i(L.loc.uPass, 1);
      gl.activeTexture(gl.TEXTURE0 + BELOW_UNIT);
      gl.bindTexture(gl.TEXTURE_2D, below.tex);
      gl.uniform1i(L.loc.uBelow, BELOW_UNIT);
      gl.drawArrays(gl.TRIANGLES, 0, 3);
      gl.bindTexture(gl.TEXTURE_2D, null);
      gl.activeTexture(gl.TEXTURE0);
      gl.disable(gl.SCISSOR_TEST);
      gl.enable(gl.BLEND);
      useQuad();
      continue;
    }
    if (L.type === 'particles') {
      const p = scenePose(L, W, H, W, H, inp);
      if (!p || p.scale <= 0) continue;
      const boxW = Math.hypot(p.axXx, p.axXy) / p.scale, boxH = Math.hypot(p.axYx, p.axYy) / p.scale;
      const P = s.particles, img = L.path && s.textures[L.path + '|CLAMP'];
      gl.useProgram(P.p);
      gl.uniform1i(P.loc.uTex, 0);
      gl.uniform1i(P.loc.uMask, 1);
      sceneBlend(L.blend);
      gl.activeTexture(gl.TEXTURE0);
      gl.bindTexture(gl.TEXTURE_2D, img ? img.tex : null);
      gl.uniform1f(P.loc.uHasMask, bindMask(L));
      gl.uniform1f(P.loc.uHasTex, img ? 1 : 0);
      gl.uniform1f(P.loc.uOpacity, p.opacity);
      gl.uniform2f(P.loc.uScreen, W, H);
      gl.uniform2f(P.loc.uOrigin, p.ox, p.oy);
      gl.uniform2f(P.loc.uAxisX, p.axXx, p.axXy);
      gl.uniform2f(P.loc.uAxisY, p.axYx, p.axYy);
      gl.uniform2f(P.loc.uBox, boxW, boxH);
      gl.uniform1f(P.loc.uRef, Math.max(W, H) / REFERENCE_W);
      gl.uniform1f(P.loc.uScale, p.scale);
      const span = L.period * CYCLES, ph = inp.time % span;
      gl.uniform1f(P.loc.uPhase, ph < 0 ? ph + span : ph);
      gl.uniform1f(P.loc.uRate, L.rate);
      gl.uniform1f(P.loc.uPeriod, L.period);
      gl.uniform2fv(P.loc.uLife, L.life);
      gl.uniform2fv(P.loc.uVelX, L.velX);
      gl.uniform2fv(P.loc.uVelY, L.velY);
      gl.uniform2fv(P.loc.uSize, L.size);
      gl.uniform4fv(P.loc.uColor0, L.color0);
      gl.uniform4fv(P.loc.uColor1, L.color1);
      gl.uniform1i(P.loc.uSeed, L.index);
      gl.drawArraysInstanced(gl.TRIANGLE_STRIP, 0, 4, L.count);
      useQuad();
      continue;
    }
    let tex;
    if (L.type === 'video') tex = videoFrame;
    else tex = s.textures[L.path + '|' + (L.type === 'image' ? L.wrap : 'CLAMP')];
    if (!tex) continue;
    const p = scenePose(L, tex.w, tex.h, W, H, inp);
    if (!p) continue;
    sceneBlend(L.blend);
    gl.activeTexture(gl.TEXTURE0);
    gl.bindTexture(gl.TEXTURE_2D, tex.tex);
    gl.uniform1f(q.loc.uHasMask, bindMask(L));
    gl.uniform1f(q.loc.uOpacity, p.opacity);
    gl.uniform4f(q.loc.uUv, p.u0, p.v0, p.du, p.dv);
    gl.uniform2f(q.loc.uOrigin, p.ox / W * 2 - 1, 1 - p.oy / H * 2);
    gl.uniform2f(q.loc.uAxisX, p.axXx / W * 2, -p.axXy / H * 2);
    gl.uniform2f(q.loc.uAxisY, p.axYx / W * 2, -p.axYy / H * 2);
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
  }
  gl.disable(gl.BLEND);
  for (const u of [1, 0]) { gl.activeTexture(gl.TEXTURE0 + u); gl.bindTexture(gl.TEXTURE_2D, null); }
  sceneCopy(t[cur], null);
}
