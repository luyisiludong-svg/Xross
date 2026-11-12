// ASCII_MATRIX: a character grid driven by luminance and time, tinted by the palette. Procedural
// 5x7 glyphs rain down each column (own speed and trail, re-rolling a few times a second), and
// light up where a soft, slowly moving wave scene underneath is bright.

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

// A glyph on a 6x8 cell grid (5x7 lit area, a one-cell gutter right and top), each dot on or off
// from a hash of the glyph id. Dots are slightly rounded so the result reads as type, not noise.
float glyph(vec2 cellUv, float id) {
    vec2 g = cellUv * vec2(6.0, 8.0);
    vec2 dotId = floor(g);
    if (dotId.x >= 5.0 || dotId.y >= 7.0) return 0.0;
    float lit = step(0.52, hash(dotId + vec2(id * 7.13, id * 3.71)));
    vec2 f = fract(g) - 0.5;
    return lit * (1.0 - smoothstep(0.30, 0.48, max(abs(f.x), abs(f.y))));
}

void main() {
    vec2 frag = gl_FragCoord.xy;
    float rowsOnScreen = 46.0;
    float cellH = max(8.0, floor(uResolution.y / rowsOnScreen));
    vec2 cell = vec2(floor(cellH * 0.72), cellH);
    vec2 c = floor(frag / cell);
    vec2 cuv = fract(frag / cell);

    float seed = hash(vec2(c.x, 17.0));
    float speed = 5.0 + seed * 9.0;               // rows per second
    float len = 10.0 + seed * 22.0;               // trail length in rows
    float rows = uResolution.y / cell.y;
    float period = rows + len + 8.0 + seed * 40.0;
    float head = mod(uTime * speed + seed * 211.0, period);
    float fromTop = rows - c.y;
    float d = head - fromTop;                      // rows behind the head (> 0 is the trail)
    float trail = (d >= 0.0 && d < len) ? pow(1.0 - d / len, 1.6) : 0.0;
    float headLit = (d >= 0.0 && d < 1.0) ? 1.0 : 0.0;

    float rate = 1.0 + 4.0 * hash(c + 3.7);
    float id = floor(hash(c) * 97.0 + uTime * rate);
    float on = glyph(cuv, id);

    float y = frag.y / uResolution.y;
    // The scene the grid samples, per cell: a broad swell of light across the lower middle.
    vec2 cc = (c + 0.5) * cell / uResolution;
    float x = cc.x * uResolution.x / uResolution.y;
    float swell = 0.46 + 0.10 * sin(x * 0.9 + uTime * 0.22) + 0.04 * sin(x * 2.3 - uTime * 0.37);
    float lum = exp(-pow((cc.y - swell) / 0.16, 2.0));
    vec3 bg = mix(uColorBottom.rgb, uColorTop.rgb, smoothstep(0.0, 1.0, y)) * 0.6;
    float level = max(trail, lum * 0.45);
    vec3 ink = uAccent.rgb * (0.06 + 0.94 * level) * (1.0 + 0.3 * uAudioBass);
    vec3 col = bg + on * ink;
    col += on * headLit * mix(uAccent.rgb, vec3(1.0), 0.65) * 0.8;
    // A faint scan of the cells so empty columns still read as a grid.
    col += uAccent.rgb * 0.015 * on;

    vec2 v = frag / uResolution - 0.5;
    col *= 1.0 - dot(v, v) * 0.6;
    col += (hash(frag + fract(uTime)) - 0.5) / 255.0;
    fragColor = vec4(col, 1.0);
}
