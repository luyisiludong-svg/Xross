// A scene's shader layer (Live backgrounds § 3c): rain on the glass in front of everything below
// it. uBelow is the scene so far; uImage0 (drops.png) holds each drop's refraction in RG and its
// mask in A, tiled and sliding down slowly. The bass makes the drops bend the light a little more.

void main() {
    vec2 uv = gl_FragCoord.xy / uResolution;
    // drops.png tiles at 512 px per tile on the long side, whatever the screen size.
    float tile = max(uResolution.x, uResolution.y) / 512.0;
    vec2 aspect = uResolution / max(uResolution.x, uResolution.y);
    vec2 t = uv * aspect * tile + vec2(0.0, uTime * 0.02);
    vec4 d = texture(uImage0, t);
    vec2 bend = (d.rg - 0.5) * d.a * (0.012 + 0.01 * uAudioBass);
    vec3 col = texture(uBelow, clamp(uv + bend, 0.0, 1.0)).rgb;
    // A drop is a little brighter at its rim, where it catches the light.
    col += d.a * 0.05 * smoothstep(0.2, 0.5, length(d.rg - 0.5));
    fragColor = vec4(col, 1.0);
}
