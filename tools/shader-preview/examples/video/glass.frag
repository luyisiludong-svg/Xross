// A CUSTOM shader over the look's video (Live backgrounds § 2): the frame through rippled glass,
// shifted a little toward the focused item, with a soft vignette. Without a video (or before its
// first frame) it shows the theme gradient.

void main() {
    vec2 uv = gl_FragCoord.xy / uResolution;
    vec3 col = mix(uColorBottom.rgb, uColorTop.rgb, uv.y);
    if (uVideoSize.x > 0.0) {
        // Where videoFit put the frame, in 0..1 of the frame.
        vec2 t = (gl_FragCoord.xy - uVideoRect.xy) / uVideoRect.zw;
        // Parallax: up to 2% of the frame toward the focus.
        t += (uFocus - 0.5) * 0.02;
        // Ripples on the glass, stronger on the beat.
        float r = sin(uv.y * 40.0 + uTime * 1.5) * sin(uv.x * 23.0 - uTime * 0.9);
        t += r * (0.0025 + 0.004 * uAudioBass);
        col = texture(uVideo, clamp(t, 0.0, 1.0)).rgb;
    }
    float v = smoothstep(1.2, 0.35, length(uv - 0.5));
    fragColor = vec4(col * mix(0.65, 1.0, v), 1.0);
}
