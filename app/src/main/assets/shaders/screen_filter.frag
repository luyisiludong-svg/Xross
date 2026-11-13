#version 300 es
// Xross background filter (2b.4f, effects.backgroundFilter): drawn after the background program
// as a second full-screen pass, multiplied into it (glBlendFunc(GL_DST_COLOR, GL_ZERO)), so it
// works over every preset and a pack's own shader alike. 1.0 leaves a pixel, 0.0 makes it black.
// It only touches the GL surface; the Compose menu above it stays crisp.
//
// Sizes are given in dp and turned into whole render pixels with uPxPerDp (display density x the
// surface's render scale), so a line or stripe always covers the same pixels: no shimmer, no
// moire when the compositor scales the surface up to the screen.
precision highp float;
uniform vec2 uResolution; // render target in pixels
uniform float uPxPerDp;   // render pixels per dp
uniform int uMode;        // 1 SCANLINES, 2 CRT, 3 LCD_GRID
uniform float uStrength;  // 0..1
out vec4 fragColor;

const float PI = 3.14159265;

// A pitch of [dp], snapped to whole render pixels and at least [minPx].
float pitch(float dp, float minPx) {
    return max(minPx, floor(dp * uPxPerDp + 0.5));
}

// 0 in the gap between two lines, 1 on a line. Sampled at pixel centers from the line's start,
// so a 2-pixel pitch gives one bright row and one dark row.
float lineShape(float coord, float p) {
    float f = fract((coord - 0.5) / p);
    float s = sin(f * PI);
    return s * s;
}

void main() {
    vec2 px = gl_FragCoord.xy;
    vec3 m = vec3(1.0);
    if (uMode == 1 || uMode == 2) {
        float lp = pitch(1.5, 2.0);
        m *= mix(1.0 - 0.55 * uStrength, 1.0, lineShape(px.y, lp));
    }
    if (uMode == 2) {
        // Aperture grille: R, G, B phosphor stripes, each a whole number of pixels wide.
        float sw = pitch(0.5, 1.0);
        float stripe = mod(floor(px.x / sw), 3.0);
        vec3 mask = vec3(1.0 - 0.32 * uStrength);
        if (stripe < 0.5) mask.r = 1.0; else if (stripe < 1.5) mask.g = 1.0; else mask.b = 1.0;
        m *= mask;
        // The tube's vignette.
        vec2 cc = px / uResolution - 0.5;
        m *= 1.0 - dot(cc, cc) * 0.8 * uStrength;
    }
    if (uMode == 3) {
        // LCD cells with a thin dark gap on their right and bottom edges.
        float cell = pitch(3.0, 3.0);
        float gap = max(1.0, floor(cell / 4.0 + 0.5));
        vec2 inCell = mod(floor(px), cell);
        float edge = max(step(cell - gap, inCell.x), step(cell - gap, inCell.y));
        m *= 1.0 - 0.45 * uStrength * edge;
    }
    fragColor = vec4(m, 1.0);
}
