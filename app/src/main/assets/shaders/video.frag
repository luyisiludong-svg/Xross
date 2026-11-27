// VIDEO: the look's background video (effects.videoPath) placed by effects.videoFit. The theme
// gradient shows where it doesn't reach (FIT, CENTER) and before its first frame. The background
// filter, if any, is drawn over it like over any shader.

void main() {
    vec2 uv = gl_FragCoord.xy / uResolution;
    vec3 col = mix(uColorBottom.rgb, uColorTop.rgb, smoothstep(0.0, 1.0, uv.y));
    if (uVideoSize.x > 0.0 && uVideoRect.z > 0.0 && uVideoRect.w > 0.0) {
        vec2 t = (gl_FragCoord.xy - uVideoRect.xy) / uVideoRect.zw;
        // A one-pixel soft edge where the frame ends inside the screen.
        vec2 px = 1.0 / uVideoRect.zw;
        vec2 inside = smoothstep(vec2(0.0), px, t) * smoothstep(vec2(0.0), px, 1.0 - t);
        vec3 frame = texture(uVideo, clamp(t, 0.0, 1.0)).rgb;
        col = mix(col, frame, inside.x * inside.y);
    }
    fragColor = vec4(col, 1.0);
}
