// BLACK_HOLE: a Gargantua-style black hole (Interstellar), ray traced through curved space and
// drawn only with letters and symbols. Style after JIN2's ASCII "Black Hole" (@jin_22ee); the
// physics after James, von Tunzelmann, Franklin & Thorne, "Gravitational lensing by spinning
// black holes in astrophysics, and in the movie Interstellar" (Class. Quantum Grav. 32, 2015).
//
// Units G = c = M = 1: horizon at r = 2, photon sphere at r = 3, critical impact parameter
// b_c = 3*sqrt(3) (the edge of the shadow).
//
//  1. Four light rays per character cell, followed backwards from the camera. Null geodesics of
//     the Schwarzschild metric obey the Binet equation  u'' + u = 3 M u^2  (u = 1/r). Written in
//     Cartesian form that is the exact "force"  a = -3 M h^2 x / r^5,  h = |x × v| conserved, so a
//     few dozen symplectic steps per ray bend it exactly as GR does.
//  2. A thin disk in the y = 0 plane, from Gargantua's innermost stable orbit (Kerr a = 0.6 M,
//     Bardeen-Press-Teukolsky: r_isco = 3.83 M, the spin used for the film's disk) out to 27 M.
//     Gas on Keplerian orbits, Omega = sqrt(M / r^3); emitted flux of a thin disk
//     F(r) ∝ r^-3 (1 - sqrt(r_in / r)), temperature T ∝ F^(1/4).
//  3. Every hit is shifted by  g = 1/(1+z) = sqrt(1 - 3M/r) / (1 - Omega L)  (gravitational
//     redshift and Doppler shift; L is the photon's angular momentum about the disk axis):
//     observed T' = g T, observed intensity g^4 I. The film toned beaming down so the disk
//     stays symmetric; BEAMING keeps a little of it.
//  4. Rays that escape read a starfield and a dust lane in their final direction, so the sky is
//     lensed too (Einstein ring around the shadow). Rays near b_c add a soft halo of glyphs
//     around the shadow: the photon ring.
//  5. Brightness picks a denser glyph (. : - + * x o % 8 # @) and a hotter colour.
//  6. The camera rolls ~17 degrees so the disk runs diagonally. Sparks ride their own Keplerian
//     orbits through the disk (so they pass in front, slip behind the shadow and reappear lensed
//     over it), and a glint chases round the photon ring.
//
// A category switch (uTransition) sends a pulse of light outward through the disk; the music's
// bass brightens it. The first seconds after launch replay the ring drawing itself and the disk
// lighting up. With feedback (uPrev) it adds JIN2's fading trails and a soft bloom.
//
// Two passes (uField): uPass 0 traces the rays once per cell into a small texture (level, heat,
// flow, star/dust); uPass 1 draws every pixel's glyph dot from it. Tracing per pixel would trace
// each cell's rays ~48 times over.

const float RIN = 3.83;          // Kerr ISCO, a = 0.6 M (prograde)
const float ROUT = 27.0;
const float BC = 5.19615;        // 3 sqrt(3): critical impact parameter, the shadow's edge
const float CAM_R = 60.0;
const float FOCAL = 1.75;
const float BEAMING = 0.45;      // 1.0 = full relativistic beaming, 0.0 = as in the film
const float SPIN = 7.5;          // M of orbital time per second of animation
const float FLOW_PERIOD = 18.0;  // texture re-seeding period, so the shear never winds it up
const float ROWS = 72.0;         // 8 px cells at the ~1280 px render size: one pixel per glyph dot
const float ROLL = 0.30;         // the disk leans down to the right (~17 degrees)
const int STEPS = 220;
const float STEP = 0.06;          // step length per unit radius (smaller: more exact bending)
const float TRAIL = 0.55;        // share of last frame kept after 1/30 s (JIN2's trail pass)
const float GLOW = 0.24;         // tight bloom: a blur of last frame, re-blurred each frame so it spreads
const float BLOOM = 0.5;         // wide bloom: a ~2-cell Gaussian of the cells' light (JIN2's 22 px pass)
const float BLOOM_SPREAD = 0.9;  // its tap offset, in cells (each tap blends 2x2 more)

// 5x7 glyphs, one 5-bit row each, top row first, leftmost dot in bit 4.
const int GLYPH_COUNT = 18;
const int G[126] = int[126](
    0, 0, 0, 0, 0, 12, 12,         //  0 .
    0, 0, 0, 0, 12, 4, 8,          //  1 ,
    0, 12, 12, 0, 12, 12, 0,       //  2 :
    0, 0, 0, 31, 0, 0, 0,          //  3 -
    0, 4, 4, 31, 4, 4, 0,          //  4 +
    0, 0, 31, 0, 31, 0, 0,         //  5 =
    0, 4, 21, 14, 21, 4, 0,        //  6 *
    0, 0, 17, 10, 4, 10, 17,       //  7 x
    0, 0, 14, 17, 17, 17, 14,      //  8 o
    24, 25, 2, 4, 8, 19, 3,        //  9 %
    4, 15, 20, 14, 5, 30, 4,       // 10 $
    14, 17, 17, 14, 17, 17, 14,    // 11 8
    10, 10, 31, 10, 31, 10, 10,    // 12 #
    14, 17, 23, 21, 23, 16, 14,    // 13 @
    30, 17, 17, 30, 17, 17, 30,    // 14 B
    1, 1, 2, 4, 8, 16, 16,         // 15 /
    16, 16, 8, 4, 2, 1, 1,         // 16 backslash
    4, 4, 4, 4, 4, 4, 4            // 17 |
);

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

float hash3(vec3 p) {
    return fract(sin(dot(p, vec3(127.1, 311.7, 74.7))) * 43758.5453);
}

float glyph(vec2 cellUv, int id) {
    vec2 g = cellUv * vec2(6.0, 8.0);
    ivec2 d = ivec2(floor(g));
    if (id < 0 || d.x >= 5 || d.y >= 7) return 0.0;
    int row = G[id * 7 + (6 - d.y)];
    float lit = float((row >> (4 - d.x)) & 1);
    vec2 f = fract(g) - 0.5;
    return lit * (1.0 - smoothstep(0.34, 0.5, max(abs(f.x), abs(f.y))));
}

// Dark to hot: deep red, red, orange, amber, white-gold, then a blue-white for the beamed side.
vec3 heatColor(float x) {
    x = clamp(x, 0.0, 1.25) * 4.0;
    vec3 c0 = vec3(0.70, 0.13, 0.04);
    vec3 c1 = vec3(0.95, 0.30, 0.07);
    vec3 c2 = vec3(1.00, 0.50, 0.14);
    vec3 c3 = vec3(1.00, 0.70, 0.33);
    vec3 c4 = vec3(1.00, 0.88, 0.68);
    vec3 c5 = vec3(0.86, 0.90, 1.00);
    if (x < 1.0) return mix(c0, c1, x);
    if (x < 2.0) return mix(c1, c2, x - 1.0);
    if (x < 3.0) return mix(c2, c3, x - 2.0);
    if (x < 4.0) return mix(c3, c4, x - 3.0);
    return mix(c4, c5, x - 4.0);
}

// Streaky rings of gas in log r, with clumps that orbit. phase = phi - Omega t.
float gasTexture(float phase, float lr) {
    float a = 34.0 * lr + 1.1 * sin(phase + 2.6 * lr);
    return 0.52 + 0.26 * sin(a)
         + 0.14 * sin(2.4 * a + 2.0 * phase)
         + 0.12 * sin(3.0 * phase - 6.0 * lr + 1.7)
         + 0.08 * sin(71.0 * lr - phase);
}

// One disk crossing at p. lphi: the photon's angular momentum about the disk axis, per unit energy.
// Returns the observed colour (linear, unbounded); heat: observed temperature, flow: texture phase.
vec3 diskEmission(vec3 p, float lphi, float t, out float heat, out float flow, out float edge) {
    float r = length(p.xz);
    float phi = atan(p.z, p.x);
    float omega = pow(r, -1.5);

    float g = sqrt(max(1.0 - 3.0 / r, 0.02)) / max(1.0 - omega * lphi, 0.05);
    float gv = pow(g, BEAMING);

    const float RPEAK = RIN * 49.0 / 36.0;   // where r^-3 (1 - sqrt(rin/r)) peaks
    const float FMAX = (1.0 / 7.0) / (RPEAK * RPEAK * RPEAK);
    float flux = max(1.0 - sqrt(RIN / r), 0.0) / (r * r * r) / FMAX;

    // Two copies of the texture, re-seeded half a period apart and cross-faded.
    float lr = log(r);
    float k = fract(t / FLOW_PERIOD);
    float t1 = k * FLOW_PERIOD;
    float t2 = fract(k + 0.5) * FLOW_PERIOD;
    float w = abs(2.0 * k - 1.0);
    float ph1 = phi - omega * SPIN * t1;
    float ph2 = phi - omega * SPIN * t2 + 2.1;
    float tex = mix(gasTexture(ph1, lr), gasTexture(ph2, lr), w);
    flow = mix(ph1, ph2, w) * 1.3 + lr * 9.0;

    // A pulse of light runs outward through the disk on a category switch.
    float front = RIN + (1.0 - uTransition) * (ROUT - RIN) * 0.9;
    float pulse = uTransition * exp(-pow((r - front) / 1.4, 2.0)) * 1.6;

    // Sparks riding their own Keplerian orbits, a bright head with a long tail behind. They run
    // along the near side, slip behind the shadow and come back over it on the lensed image.
    float sparks = 0.0;
    for (int i = 0; i < 3; i++) {
        // Out in the broad part of the disk; still Keplerian relative to each other (inner laps outer).
        float rc = i == 0 ? 9.0 : i == 1 ? 14.0 : 20.0;
        float ws = pow(rc, -1.5) * SPIN * 2.4;
        float d = mod(phi - 2.1 * float(i) - ws * t + 3.14159265, 6.2831853) - 3.14159265;
        float comet = d > 0.0 ? exp(-pow(d / 0.12, 2.0)) : exp(d / 1.0);
        sparks += comet * exp(-pow((lr - log(rc)) / 0.05, 2.0));
    }

    edge = smoothstep(RIN, RIN + 0.5, r) * (1.0 - smoothstep(ROUT - 9.0, ROUT, r));
    heat = pow(flux, 0.25) * gv * (0.85 + 0.25 * tex) + 0.45 * sparks;
    float intensity = flux * gv * gv * gv * gv * tex * edge + (pulse + 1.4 * sparks) * edge;
    return heatColor(heat) * intensity;
}

// Lensed sky: sparse stars and a faint dust lane, read in the ray's final direction.
vec3 sky(vec3 d, out float isStar, out float dust) {
    vec2 s = vec2(atan(d.z, d.x), asin(clamp(d.y, -1.0, 1.0)));
    float star = 0.0;
    for (int i = 0; i < 2; i++) {
        float scale = i == 0 ? 9.0 : 16.0;
        vec2 q = s * scale;
        vec2 id = floor(q);
        vec2 off = vec2(hash(id + float(i) * 7.1), hash(id + 3.3 + float(i))) * 0.8 + 0.1;
        vec2 dd = (fract(q) - off) / scale;
        dd.x *= cos(s.y);
        float on = step(0.72 + 0.12 * float(i), hash(id + 11.0 * float(i) + 0.5));
        star = max(star, on * exp(-dot(dd, dd) / 0.00012) * (i == 0 ? 1.0 : 0.6));
    }
    isStar = star;
    float lane = exp(-pow(dot(d, normalize(vec3(0.25, 1.0, 0.35))) - 0.05, 2.0) / 0.03);
    float grain = step(1.0 - 0.10 * lane, hash3(floor(d * 90.0)));
    dust = grain;
    return vec3(0.80, 0.86, 1.00) * star + vec3(0.45, 0.25, 0.17) * grain * 0.22;
}

// Character cell size in render pixels: ~72 rows, 6x8 at least (one pixel per glyph dot).
vec2 cellSize() {
    float cellH = max(8.0, floor(uResolution.y / ROWS));
    return vec2(max(6.0, floor(cellH * 0.75)), cellH);
}

// Screen position (render pixels) to camera-plane coordinates. In portrait, step back so the disk fits.
vec2 toUv(vec2 px) {
    vec2 uv = (px - 0.5 * uResolution) / uResolution.y;
    return uv * max(1.0, 2.0 * uResolution.y / uResolution.x);
}

struct Camera { vec3 ro; vec3 fw; vec3 rt; vec3 up; };

// Just above the disk plane (Interstellar's view), swaying slowly and drifting round.
Camera camera(float t) {
    Camera cam;
    float el = radians(7.5 + 3.5 * sin(t * 0.027));
    float az = t * 0.011;
    cam.ro = CAM_R * vec3(cos(el) * sin(az), sin(el), cos(el) * cos(az));
    cam.fw = normalize(-cam.ro);
    cam.rt = normalize(cross(cam.fw, vec3(0.0, 1.0, 0.0)));
    cam.up = cross(cam.rt, cam.fw);
    return cam;
}

// Rolled so the disk runs diagonally across the screen.
vec3 rayDir(Camera cam, vec2 uv) {
    vec2 uvr = vec2(cos(ROLL) * uv.x - sin(ROLL) * uv.y, sin(ROLL) * uv.x + cos(ROLL) * uv.y);
    return normalize(cam.fw * FOCAL + uvr.x * cam.rt + uvr.y * cam.up);
}

// One geodesic: the disk light it gathers, and the sky it ends on.
void trace(Camera cam, vec2 uv, float t, float diskOn,
           out vec3 emit, out float heat, out float flow, out float isStar, out float dust) {
    vec3 rd = rayDir(cam, uv);
    vec3 hv = cross(cam.ro, rd);
    float h2 = dot(hv, hv);
    float lphi = -(cam.ro.x * rd.z - cam.ro.z * rd.x);   // photon's L about y, along its travel
    vec3 x = cam.ro;
    vec3 v = rd;
    emit = vec3(0.0);
    heat = 0.0;
    flow = 0.0;
    isStar = 0.0;
    dust = 0.0;
    float cover = 0.0;
    bool captured = false;
    for (int i = 0; i < STEPS; i++) {
        float r2 = dot(x, x);
        float r = sqrt(r2);
        float dt = STEP * r;
        v += -3.0 * h2 * x / (r2 * r2 * r) * dt;
        vec3 xn = x + v * dt;
        if (x.y * xn.y < 0.0) {
            vec3 p = mix(x, xn, x.y / (x.y - xn.y));
            float rp = length(p.xz);
            if (rp > RIN && rp < ROUT) {
                float hHeat, hFlow, edge;
                vec3 e = diskEmission(p, lphi, t, hHeat, hFlow, edge) * diskOn;
                emit += (1.0 - cover) * e;
                if (cover < 0.5) { heat = hHeat; flow = hFlow; }
                cover += (1.0 - cover) * 0.95 * edge * diskOn;
                if (cover > 0.94) break;
            }
        }
        x = xn;
        float rn = length(x);
        if (rn < 2.0) { captured = true; break; }
        if (rn > ROUT + 4.0 && dot(x, v) > 0.0) break;
    }
    if (!captured && cover < 0.94) {
        sky(normalize(v), isStar, dust);
        isStar *= 1.0 - cover;
        dust *= 1.0 - cover;
    }
}

// Pass 0: one texel per cell. r: glyph level, g: heat / 1.6, b: flow / 3 (wrapped), a: dust 1.0,
// else star brightness * 0.9.
vec4 fieldPass(vec2 c, float t) {
    vec2 cell = cellSize();
    if (any(greaterThanEqual(c * cell, uResolution))) return vec4(0.0);
    Camera cam = camera(t);
    float diskOn = smoothstep(2.5, 6.5, t);   // formation: the ring draws itself, then the disk lights up

    // Four rays on a rotated grid inside the cell: smooth edges where the shadow, ring and rim cross it.
    vec3 emit = vec3(0.0);
    float heatW = 0.0, wSum = 0.0, flow = 0.0, best = -1.0, star = 0.0, dust = 0.0;
    for (int k = 0; k < 4; k++) {
        vec2 o = k == 0 ? vec2(0.375, 0.125) : k == 1 ? vec2(0.875, 0.375) : k == 2 ? vec2(0.625, 0.875) : vec2(0.125, 0.625);
        vec3 e; float hh, ff, ss, dd;
        trace(cam, toUv((c + o) * cell), t, diskOn, e, hh, ff, ss, dd);
        float l = dot(e, vec3(0.3, 0.55, 0.15));
        emit += e;
        heatW += hh * (l + 1e-4);
        wSum += l + 1e-4;
        if (l > best) { best = l; flow = ff; }
        star += ss;
        dust += dd;
    }
    emit *= 0.25;
    float heat = heatW / wSum;
    star *= 0.25;
    dust *= 0.25;

    // Photon ring: the rays that skim the photon sphere, at b = 3 sqrt(3) (the cell's centre ray).
    // A soft halo of ordinary glyphs, tight inside the shadow and fading slowly outward.
    vec2 uv = toUv((c + 0.5) * cell);
    vec3 rd = rayDir(cam, uv);
    float b = length(cross(cam.ro, rd));
    float lphi = -(cam.ro.x * rd.z - cam.ro.z * rd.x);
    float db = b - BC;
    float ring = exp(-pow(db / (db > 0.0 ? 1.1 : 0.35), 2.0));
    float around = fract(atan(uv.x, -uv.y) / 6.2831853 + 0.5);
    float drawn = smoothstep(0.0, 0.06, smoothstep(0.4, 2.8, t) * 1.08 - around);
    float glint = pow(0.5 + 0.5 * cos(atan(uv.y, uv.x) - t * 0.9), 14.0);   // chases round, ~7 s
    float ringLight = ring * drawn * (0.42 + 0.12 * clamp(lphi / BC, -1.0, 1.0) + 0.12 * diskOn + 0.45 * glint * diskOn);

    // Tone map to a glyph level, and screen-blend the ring in so it melts into the disk.
    float exposure = 2.9 * (1.0 + 0.35 * uAudioBass + 0.12 * uAudioBeat + 0.4 * uTransition);
    float lum = dot(emit, vec3(0.3, 0.55, 0.15));
    float level = 1.0 - exp(-exposure * pow(max(lum, 0.0), 0.55));
    if (ringLight > level) {
        heat = max(heat, 0.55 + 0.35 * ringLight);
        if (lum < 0.01) flow = atan(uv.y, uv.x) * 7.0 - t * 1.3;
    }
    level = 1.0 - (1.0 - level) * (1.0 - ringLight);

    float skyTag = star > 0.25 ? 0.9 * min(star, 1.0) : dust > 0.25 ? 1.0 : 0.0;
    return vec4(level, clamp(heat / 1.6, 0.0, 1.0), fract(flow / 3.0), skyTag);
}

// Wide bloom: the cells' light, blurred. Five bilinear taps of the field (each blends 4 cells)
// spread over ~2 cells; level and heat are blurred first and turned into colour once.
vec3 wideBloom(vec2 frag, vec2 cell) {
    vec2 size = vec2(textureSize(uField, 0));
    vec2 st = frag / cell / size;            // texel centre c + 0.5 sits at that cell's centre
    vec2 o = BLOOM_SPREAD / size;
    vec2 f = 0.4 * texture(uField, st).rg
           + 0.15 * (texture(uField, st + o).rg + texture(uField, st - o).rg
                   + texture(uField, st + vec2(o.x, -o.y)).rg + texture(uField, st + vec2(-o.x, o.y)).rg);
    float level = f.r;
    // A deeper orange than the letters (gas glowing, not type), and fuller in the mid-bright
    // orange parts of the disk: light ~ level^1.5 instead of level^2.
    return heatColor(f.g * 1.6 * 0.7 + 0.08 * level) * (0.5 + level) * level * sqrt(level);
}

// Pass 1: each pixel is one dot of its cell's glyph.
void main() {
    float t = uTime;
    if (uPass == 0) {
        fragColor = fieldPass(floor(gl_FragCoord.xy), t);
        return;
    }
    vec2 frag = gl_FragCoord.xy;
    vec2 cell = cellSize();
    vec2 c = floor(frag / cell);
    vec2 cuv = fract(frag / cell);
    vec4 f = texelFetch(uField, ivec2(c), 0);
    float level = f.r;
    float heat = f.g * 1.6;
    float flow = f.b * 3.0;

    vec3 bg = mix(uColorBottom.rgb, uColorTop.rgb, frag.y / uResolution.y) * 0.12;
    vec3 col = bg;
    float h = hash(c);
    int id = -1;
    vec3 ink = vec3(0.0);

    if (level > 0.05) {
        int tier = int(clamp(level * 5.0, 0.0, 4.0));
        int pick = int(mod(floor(h * 7.0 + flow), 3.0));
        int pools[15] = int[15](0, 2, 1, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14);
        id = pools[tier * 3 + pick];
        vec3 hue = heatColor(heat * 0.95 + 0.22 * level);
        ink = hue * (0.42 + 1.0 * level);
    } else if (f.a > 0.2 && f.a < 0.95) {
        float isStar = f.a / 0.9;
        id = isStar > 0.8 ? (h < 0.3 ? 6 : 4) : 0;
        ink = vec3(0.82, 0.87, 1.0) * (0.45 + 0.5 * isStar) * (0.85 + 0.15 * sin(t * 0.9 + h * 50.0));
    } else if (f.a >= 0.95) {
        id = h < 0.5 ? 1 : 2;
        ink = vec3(0.50, 0.29, 0.19) * 0.55 * smoothstep(3.0, 6.0, t);
    }

    float on = glyph(cuv, id);
    col += on * ink;
    // Wide bloom under and between the letters, so the disk reads as light and not only as type.
    col += wideBloom(frag, cell) * BLOOM * (1.0 - 0.4 * on);

    vec2 vg = frag / uResolution - 0.5;
    col *= 1.0 - dot(vg, vg) * 0.5;

    // Feedback: last frame fades under this one (trails behind the sparks, letters cross-fading
    // as they change), and a soft blur of it is added on top, so bright letters bloom.
    if (uFresh < 0.5) {
        vec2 st = frag / uResolution;
        vec3 prev = texture(uPrev, st).rgb;
        col = max(col, prev * pow(TRAIL, uFrameDt * 30.0) - 1.5 / 255.0);   // RGBA8: never stuck at 1/255
        vec2 o = cell * 1.6 / uResolution;
        vec3 halo = 0.25 * (texture(uPrev, st + o).rgb + texture(uPrev, st - o).rgb
                          + texture(uPrev, st + vec2(o.x, -o.y)).rgb + texture(uPrev, st + vec2(-o.x, o.y)).rgb);
        col += max(halo - 0.025, 0.0) * GLOW;
    }
    // Soft shoulder: highlights roll off instead of clipping, so the hot side stays gold with its
    // letters readable rather than a white blob. Below 0.65 nothing changes.
    vec3 over = max(col - 0.65, 0.0);
    col = min(col, 0.65) + 0.30 * (1.0 - exp(-over / 0.30));   // peaks at 0.95
    col += (hash(frag + fract(t)) - 0.5) / 255.0;
    fragColor = vec4(col, 1.0);
}
