// BOKEH: soft out-of-focus orbs drifting up through the theme gradient. Near orbs are large,
// faint and blurred; far ones small and crisp, so the field has depth of field.

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

const int ORBS = 20;

void main() {
    vec2 uv = gl_FragCoord.xy / uResolution;
    float aspect = uResolution.x / uResolution.y;
    vec2 p = vec2(uv.x * aspect, uv.y);
    float aa = 1.5 / uResolution.y;

    vec3 col = mix(uColorBottom.rgb, uColorTop.rgb, smoothstep(0.0, 1.0, uv.y));
    col += uColorBottom.rgb * 0.15 * exp(-pow((uv.y - 0.3) * 2.4, 2.0));

    for (int i = 0; i < ORBS; i++) {
        float fi = float(i);
        // Per-orb constants from low-discrepancy sequences: well spread and far cheaper than a
        // sin hash per orb per pixel.
        float h1 = fract(fi * 0.618034 + 0.13);
        float h2 = fract(fi * 0.754878 + 0.41);
        float depth = fract(fi * 0.569840 + 0.27);  // 0 near .. 1 far
        float r = mix(0.15, 0.025, depth);
        float rise = mix(0.030, 0.010, depth);
        vec2 c;
        c.y = fract(h2 + uTime * rise) * (1.0 + 4.0 * r) - 2.0 * r;
        c.x = h1 * (aspect + 2.0 * r) - r + 0.05 * sin(uTime * (0.15 + 0.2 * h2) + fi * 1.7);
        float d = length(p - c);
        // Circle of confusion: near orbs blur over a third of their radius, far ones stay sharp.
        float blur = mix(0.45, 0.04, depth) * r + aa;
        // Most orbs are nowhere near this pixel: disc and rim are both ~0 past 1.4 radii.
        if (d > r * 1.4 + blur) continue;
        float disc = 1.0 - smoothstep(r - blur, r + blur, d);
        // Brighter rim, as real lens bokeh has.
        float k = clamp(1.0 - abs(d - r * 0.9) / (r * 0.12 + aa), 0.0, 1.0);
        float rim = k * k * (1.0 - depth);
        float a = mix(0.10, 0.20, depth) * (0.85 + 0.3 * uAudioBass);
        vec3 tint = mix(uAccent.rgb, uGlow.rgb, h1);
        col += tint * a * (disc + rim * 0.35);
    }

    vec2 v = uv - vec2(0.5, 0.45);
    col *= 1.0 - dot(v, v) * 0.5;
    col += (hash(gl_FragCoord.xy + fract(uTime)) - 0.5) / 255.0;
    fragColor = vec4(col, 1.0);
}
