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
out vec4 fragColor;
