// CRT: the theme on a curved tube: a gradient and glowing ribbons with phosphor bloom, seen
// through scanlines and an RGB aperture grille.

const float PI = 3.14159265;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

// The picture on the tube, in tube coordinates (0..1). Each ribbon has a sharp core and a wide
// halo: the halo is the phosphor bloom.
vec3 picture(vec2 uv, float aspect) {
    vec3 col = mix(uColorBottom.rgb, uColorTop.rgb, smoothstep(0.0, 1.0, uv.y));
    float x = uv.x * aspect;
    for (int k = 0; k < 3; k++) {
        float fk = float(k);
        float c = 0.48 + (fk - 1.0) * 0.07
            + 0.07 * sin(x * 1.2 + uTime * 0.35 + fk * 2.1)
            + 0.025 * sin(x * 3.3 - uTime * 0.6 + fk);
        float d = uv.y - c;
        float core = exp(-(d * d) / 0.00003);
        float bloom = exp(-(d * d) / 0.0025);
        col += uAccent.rgb * (core * 0.55 + bloom * 0.16) * (1.0 - fk * 0.2);
    }
    return col;
}

void main() {
    vec2 res = uResolution;
    vec2 uv = gl_FragCoord.xy / res;
    float aspect = res.x / res.y;

    // Barrel curvature; outside the tube is the bezel's black.
    vec2 cc = uv - 0.5;
    vec2 tube = 0.5 + cc * (1.0 + 0.06 * dot(cc, cc) * vec2(1.0, 1.4));
    vec2 edge = smoothstep(vec2(0.0), vec2(0.004), tube) * smoothstep(vec2(0.0), vec2(0.004), 1.0 - tube);
    float inside = edge.x * edge.y;

    vec3 col = picture(tube, aspect);
    float luma = dot(col, vec3(0.299, 0.587, 0.114));

    // Scanlines: ~270 lines whatever the render size; bright lines bloom over the gaps.
    float lines = 270.0;
    float s = sin(tube.y * lines * PI);
    float scan = mix(0.55, 1.0, s * s);
    col *= mix(scan, 1.0, clamp(luma * 0.6, 0.0, 0.5));

    // Aperture grille: vertical R, G, B phosphor stripes, one pixel each.
    float stripe = mod(floor(gl_FragCoord.x), 3.0);
    vec3 mask = vec3(0.78);
    if (stripe < 0.5) mask.r = 1.12; else if (stripe < 1.5) mask.g = 1.12; else mask.b = 1.12;
    col *= mask;

    // Faint flicker and the tube's vignette.
    col *= 0.985 + 0.015 * sin(uTime * 47.0);
    col *= 1.0 - dot(cc, cc) * 0.9;
    col *= inside;
    col += (hash(gl_FragCoord.xy + fract(uTime)) - 0.5) / 255.0;
    fragColor = vec4(col, 1.0);
}
