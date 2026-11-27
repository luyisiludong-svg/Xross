// SCENE: a layered scene (effects.scenePath) drawn as its poster, until the scene renderer is in
// place: scene.poster, else its first image layer, bound as uImage0 and covering the screen
// (cropped, centered). The theme gradient shows while it loads and when the scene has no poster.
// The background filter, if any, is drawn over it like over any shader.

void main() {
    vec2 uv = gl_FragCoord.xy / uResolution;
    vec3 col = mix(uColorBottom.rgb, uColorTop.rgb, smoothstep(0.0, 1.0, uv.y));
    if (uImageSize0.x > 0.0 && uImageSize0.y > 0.0) {
        // Cover: scale the shorter fit up so the image fills the screen, crop the rest evenly.
        float screen = uResolution.x / uResolution.y;
        float image = uImageSize0.x / uImageSize0.y;
        vec2 scale = screen > image ? vec2(1.0, image / screen) : vec2(screen / image, 1.0);
        vec4 art = texture(uImage0, (uv - 0.5) * scale + 0.5);
        col = mix(col, art.rgb, art.a);
    }
    fragColor = vec4(col, 1.0);
}
