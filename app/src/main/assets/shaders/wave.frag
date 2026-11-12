// WAVE: the theme gradient with broad, glossy light sheets and a few fine strands. The built-in
// fallback: always compiled, drawn whenever another program isn't ready or fails.
uniform float uBright;   // daylight brightness, 0.8-1.0
uniform int uLines;      // strands riding the main sheet, 0-12
uniform float uTurb;     // amplitude multiplier, 0-2
uniform float uThick;    // crest/halo/strand width multiplier, 0.5-3

// Low-frequency curve: two long swells plus a slow ripple. All speeds are small so motion
// reads as drifting light rather than oscillation.
float curve(float x, float t, float base, float amp, float phase) {
    amp *= uTurb;
    return base
        + amp * 0.62 * sin(x * 0.85 + t * 0.21 + phase)
        + amp * 0.28 * sin(x * 1.70 - t * 0.33 + phase * 1.9)
        + amp * 0.10 * sin(x * 3.10 + t * 0.47 + phase * 0.7);
}

// One glossy sheet: a bright anti-aliased crest, a soft halo, and a translucent body below the
// crest that fades downward.
vec3 sheet(vec2 uv, float x, float t, float base, float amp, float phase, float strength) {
    float c = curve(x, t, base, amp, phase);
    float d = uv.y - c;                         // > 0 above the crest
    float aa = max(fwidth(d), 1e-4);
    float crest = 1.0 - smoothstep(0.0, (aa * 2.5 + 0.0025) * uThick, abs(d));
    float halo = exp(-(d * d) / (0.0022 * uThick * uThick));
    float body = smoothstep(aa, -0.03, d) * exp(d / 0.16);
    return uAccent.rgb * strength * (crest * 0.32 + halo * 0.10 + body * 0.075);
}

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

void main() {
    vec2 uv = gl_FragCoord.xy / uResolution;    // y = 0 at the bottom
    float x = uv.x * (uResolution.x / uResolution.y);
    float t = uTime;

    // Base: dark at the top, brightest toward the lower middle, as in the reference themes.
    float g = smoothstep(0.0, 1.0, uv.y);
    vec3 col = mix(uColorBottom.rgb, uColorTop.rgb, g);
    col += uColorBottom.rgb * 0.18 * exp(-pow((uv.y - 0.32) * 2.6, 2.0));

    // Broad glossy sheets.
    col += sheet(uv, x, t,        0.50, 0.10, 0.0, 1.00);
    col += sheet(uv, x, t * 0.8,  0.44, 0.08, 2.1, 0.70);
    col += sheet(uv, x, t * 1.15, 0.36, 0.12, 4.3, 0.55);
    col += sheet(uv, x, t * 0.6,  0.58, 0.06, 5.7, 0.35);

    // Fine strands riding the main sheet, centred on it whatever their number.
    float mid = (float(uLines) - 1.0) * 0.5;
    for (int i = 0; i < 12; i++) {
        if (i >= uLines) break;
        float fi = float(i);
        float c = curve(x, t * (0.95 + fi * 0.03), 0.50 + (fi - mid) * 0.012, 0.10, fi * 0.18);
        float d = abs(uv.y - c);
        float aa = max(fwidth(uv.y - c), 1e-4);
        col += uAccent.rgb * 0.10 * (1.0 - smoothstep(0.0, aa * 1.5 * uThick, d));
    }

    col *= uBright;
    // Gentle vignette.
    vec2 v = uv - vec2(0.5, 0.45);
    col *= 1.0 - dot(v, v) * 0.45;
    // Dither to hide 8-bit banding in the long gradients.
    col += (hash(gl_FragCoord.xy + fract(t)) - 0.5) / 255.0;
    fragColor = vec4(col, 1.0);
}
