// STARTER: drifting glow blobs over the theme gradient. A template for a pack's own background
// shader (docs/SHADER_TUTORIAL.md). Copy it into a pack as shader.frag and set
// "effects": { "shaderPreset": "CUSTOM" } in theme.json.
//
// prelude.glsl is put in front of this file, so uTime, uResolution, the palette colours, the
// audio levels, uTransition and fragColor are already declared.

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

const int BLOBS = 4;

void main() {
    // Aspect-correct coordinates centred on the screen: y spans -0.5 .. 0.5.
    vec2 uv = (gl_FragCoord.xy - 0.5 * uResolution) / uResolution.y;
    float y = gl_FragCoord.y / uResolution.y;

    // 1. Base: the theme gradient, kept dark so the menu above stays readable.
    vec3 col = mix(uColorBottom.rgb, uColorTop.rgb, smoothstep(0.0, 1.0, y)) * 0.6;

    // 2. Content: a few soft blobs in the theme's colours, kept to the right of the categories.
    for (int i = 0; i < BLOBS; i++) {
        float fi = float(i);
        vec2 c = vec2(0.35 + 0.25 * sin(uTime * 0.13 + fi * 1.7),
                      0.15 * cos(uTime * 0.11 + fi * 2.3));
        float d = length(uv - c);
        vec3 tint = mix(uAccent.rgb, uGlow.rgb, fi / float(BLOBS - 1));
        col += tint * 0.12 * exp(-d * d * 18.0) * (1.0 + 0.4 * uAudioBass);
    }

    // 3. Reactions: a flash of glow on a category switch (and during a SHADER boot intro).
    col += uGlow.rgb * 0.15 * uTransition;

    // 4. Finish: a gentle vignette, then dither so the long gradients don't band in 8 bits.
    vec2 v = gl_FragCoord.xy / uResolution - vec2(0.5, 0.45);
    col *= 1.0 - dot(v, v) * 0.5;
    col += (hash(gl_FragCoord.xy + fract(uTime)) - 0.5) / 255.0;
    fragColor = vec4(col, 1.0);
}
