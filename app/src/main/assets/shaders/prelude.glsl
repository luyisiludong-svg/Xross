#version 300 es
// Xross background shader prelude: put in front of every preset and of a pack's shader.frag
// unless that file starts with its own #version line. Error lines are reported relative to the
// file that follows, not to this prelude.
precision highp float;
precision highp int;
uniform float uTime;         // seconds of animation (scaled by the wave speed), wraps at 10 000
uniform vec2 uResolution;    // render target size in pixels
uniform vec4 uColorTop;      // canvas top color, rgb (a = 1)
uniform vec4 uColorBottom;   // canvas bottom color
uniform vec4 uAccent;        // wave / ribbon color (pack accentSecondary, else accent)
uniform vec4 uGlow;          // glow color (pack accent)
uniform float uBatteryLevel; // 0.0 empty .. 1.0 full (1.0 where unknown)
uniform float uAudioBass;    // 0.0 .. 1.0 bass energy of Xross's music, as heard (0.0 with none or paused)
uniform float uAudioBeat;    // 1.0 for ~100 ms from a beat, else 0.0
uniform float uTransition;   // 1.0 right after a category switch, easing back to 0.0 in ~1 s
uniform vec2 uFocus;         // the focused item's centre, 0..1 in the space of gl_FragCoord.xy / uResolution (y up); eased
// Images (optional): effects.shaderImages, in order, on texture units 2-5. Upright when sampled
// at gl_FragCoord.xy / uResolution; RGBA8 with straight (not premultiplied) alpha, so masks and
// data packed in alpha survive. A missing or dropped slot samples black and its size is 0.
uniform sampler2D uImage0, uImage1, uImage2, uImage3;
uniform vec4 uImageSize0, uImageSize1, uImageSize2, uImageSize3; // w, h, 1/w, 1/h in texels; 0 for a missing slot
// Video (optional): effects.videoPath, the current frame on texture unit 6, already RGB (alpha 1),
// upright at the same coordinates as the images, at most 1280 on its long side. Before the first
// frame, and with no video, it samples black and uVideoSize is 0.
uniform sampler2D uVideo;
uniform vec4 uVideoSize;     // w, h, 1/w, 1/h of the frame in texels; 0 with no video
uniform vec4 uVideoRect;     // where effects.videoFit places it: left, bottom, width, height in render pixels
// Feedback (optional): a shader that reads uPrev gets last frame's picture (RGBA8, the render
// size, linear filtering, clamped). Fade it per second with uFrameDt, and ignore it where
// uFresh is 1.0 (first frame, after a pause or resize, and in still/E-Ink mode).
uniform sampler2D uPrev;     // last frame's output; sample at gl_FragCoord.xy / uResolution
uniform float uFrameDt;      // seconds since the last frame, 0.1 at most
uniform float uFresh;        // 1.0 when uPrev holds no usable history, else 0.0
// Field pass (optional): a shader that reads uField is drawn twice per frame. First with
// uPass = 0 into a texture of ceil(uResolution / 4) texels (gl_FragCoord is then a texel, write
// whatever the second pass needs), then with uPass = 1 at full size, where uField holds that
// result (RGBA8; texelFetch reads one texel exactly, texture() blends neighbours bilinearly).
// For work shared by a block of pixels.
uniform sampler2D uField;
uniform int uPass;           // 0: the field pass, 1: the full-size pass (always 1 without uField)
out vec4 fragColor;
