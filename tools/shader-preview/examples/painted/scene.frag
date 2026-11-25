// A painted scene (Live backgrounds § 1): a still painting brought to life with depth parallax
// toward the focus, a ripple on the water and a drifting haze. Images, from theme.json:
//   uImage0  painting.png  rgb: the painting; alpha < 1 marks water (straight alpha, kept as is)
//   uImage1  depth.png     r: 0 far .. 1 near
// The painting is drawn to COVER the screen whatever its aspect, like a wallpaper.

float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }

float noise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1, 0)), u.x), mix(hash(i + vec2(0, 1)), hash(i + vec2(1, 1)), u.x), u.y);
}

// Screen uv (0..1) to image uv for a COVER fit of an image of size `size`.
vec2 cover(vec2 uv, vec4 size) {
    if (size.x <= 0.0) return uv;
    float screen = uResolution.x / uResolution.y;
    float image = size.x / size.y;
    vec2 scale = screen > image ? vec2(1.0, image / screen) : vec2(screen / image, 1.0);
    return (uv - 0.5) * scale + 0.5;
}

void main() {
    vec2 uv = gl_FragCoord.xy / uResolution;
    // Shrink a little so parallax never shows the painting's edge.
    vec2 base = (cover(uv, uImageSize0) - 0.5) * 0.94 + 0.5;

    // Depth parallax: near things move more, away from the focus, plus a slow sway.
    float d = texture(uImage1, base).r;
    vec2 look = (uFocus - 0.5) * vec2(0.035, 0.02) + vec2(sin(uTime * 0.11), cos(uTime * 0.07)) * 0.004;
    vec2 p = base - look * d;

    // Ripple where the painting marks water; the beat and a category switch add a swell.
    vec4 paint = texture(uImage0, p);
    float water = 1.0 - paint.a;
    if (water > 0.01) {
        float swell = 1.0 + 1.5 * uAudioBass + 2.0 * uTransition;
        vec2 w = vec2(noise(p * vec2(18.0, 60.0) + vec2(uTime * 0.6, 0.0)), noise(p * vec2(22.0, 70.0) - vec2(0.0, uTime * 0.5))) - 0.5;
        p += w * vec2(0.004, 0.010) * water * swell;
        paint = texture(uImage0, p);
        paint.rgb += vec3(0.05, 0.04, 0.06) * water * smoothstep(0.55, 0.9, noise(p * vec2(40.0, 200.0) + uTime));
    }
    vec3 col = paint.rgb;

    // Haze in the distance, tinted by the theme's glow.
    float haze = noise(vec2(p.x * 3.0 + uTime * 0.03, p.y * 6.0)) * (1.0 - d);
    col = mix(col, uGlow.rgb * 0.8, haze * 0.18);

    // Keep it dark behind the menu: dim, a vignette, the left third a little darker.
    col *= 0.62 * mix(0.8, 1.0, smoothstep(0.0, 0.45, uv.x));
    col *= 1.0 - 0.35 * dot(uv - 0.5, uv - 0.5) * 2.0;
    col += (hash(gl_FragCoord.xy + fract(uTime)) - 0.5) / 255.0;
    fragColor = vec4(col, 1.0);
}
