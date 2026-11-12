// STARFIELD: warp dust flowing out from the centre. A category switch (uTransition) stretches the
// dust into streaks and pushes it forward (uTravel, integrated by the renderer so it never jumps).
uniform float uTravel;       // extra distance flown on category switches, renderer-integrated

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

const int LAYERS = 5;

void main() {
    vec2 uv = (gl_FragCoord.xy - 0.5 * uResolution) / uResolution.y;
    float y = gl_FragCoord.y / uResolution.y;
    vec3 col = mix(uColorBottom.rgb, uColorTop.rgb, smoothstep(0.0, 1.0, y)) * 0.85;
    float warp = clamp(uTransition, 0.0, 1.0);
    vec2 dir = normalize(uv + vec2(1e-4, 0.0));
    vec2 side = vec2(-dir.y, dir.x);
    vec3 dust = mix(uAccent.rgb, vec3(1.0), 0.55);

    for (int i = 0; i < LAYERS; i++) {
        float fi = float(i);
        float z = fract(fi / float(LAYERS) + uTime * 0.035 + uTravel);   // 0 far .. 1 near
        float scale = mix(22.0, 1.2, z);
        vec2 q = uv * scale + vec2(fi * 13.1, fi * 7.7);
        vec2 id = floor(q);
        vec2 f = fract(q) - 0.5;
        float h = hash(id + fi * 3.1);
        vec2 off = (vec2(hash(id + 1.3 + fi), hash(id + 2.7 + fi)) - 0.5) * 0.6;
        vec2 d = f - off;
        float size = 0.035 * z + 0.006;
        float stretch = 1.0 + warp * 9.0 * z;
        float along = dot(d, dir) / stretch;
        float across = dot(d, side);
        float star = exp(-(along * along + across * across) / (size * size));
        float fade = smoothstep(0.0, 0.3, z) * smoothstep(1.0, 0.8, z);
        col += dust * star * fade * step(0.6, h) * (0.7 + 0.8 * warp) * (1.0 + 0.35 * uAudioBass);
    }

    // A faint glow at the vanishing point, brighter mid-warp and on a beat of the music.
    col += uGlow.rgb * (0.04 + 0.10 * warp + 0.05 * uAudioBeat) * exp(-dot(uv, uv) * 6.0);
    col += (hash(gl_FragCoord.xy + fract(uTime)) - 0.5) / 255.0;
    fragColor = vec4(col, 1.0);
}
