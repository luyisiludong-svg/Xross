#version 300 es
// A scene's particle layer (Live backgrounds § 3d): one instanced draw, a 4-vertex strip per
// particle from gl_VertexID. Each particle is a pure function of its index and uPhase, with no
// CPU state; SceneParticles.kt (theme engine) holds the same arithmetic and its tests.
//
// Positions are in render pixels, y down. The emitter's box (the layer's rect, else the screen) is
// posed like an image layer: an origin and two edges (parallax, sway, pulse and the drivers).
// Particles are born across the box and move in its own, unscaled pixels.
uniform vec2 uScreen;    // the target, render pixels
uniform vec2 uOrigin;    // the box's top-left corner after motion
uniform vec2 uAxisX;     // its two edges after motion
uniform vec2 uAxisY;
uniform vec2 uBox;       // its size before motion (pulse) scaled it
uniform float uRef;      // render pixels per reference pixel (1280 on the long side)
uniform float uScale;    // the motion's scale, for the particles' size
uniform float uPhase;    // seconds, wrapped to CYCLES periods on the CPU
uniform float uRate;     // births per second
uniform float uPeriod;   // seconds between one slot's births: count / rate
uniform vec2 uLife;      // seconds, low and high
uniform vec2 uVelX;      // reference pixels per second, low and high
uniform vec2 uVelY;
uniform vec2 uSize;      // reference pixels across, low and high
uniform vec4 uColor0;    // straight RGBA at birth
uniform vec4 uColor1;    // and at death
uniform int uSeed;       // the layer's index: two layers differ
out vec2 vUv;            // 0..1 across the particle, y down
out vec2 vBox;           // the particle's place in the box, 0..1 (the mask)
out vec4 vColor;         // premultiplied

const float CYCLES = 256.0;

uint hash(uint x) {
    x ^= x >> 16u;
    x *= 0x7feb352du;
    x ^= x >> 15u;
    x *= 0x846ca68bu;
    x ^= x >> 16u;
    return x;
}

float random(uint base, uint n) {
    return float(hash(base + n) >> 8u) * (1.0 / 16777216.0);
}

void main() {
    vec2 c = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
    vUv = c;
    float rel = uPhase - float(gl_InstanceID) / uRate;
    float cycleF = floor(rel / uPeriod);
    float age = rel - cycleF * uPeriod;
    uint cycle = uint(mod(cycleF, CYCLES));
    uint base = hash(uint(gl_InstanceID) ^ hash(cycle ^ (uint(uSeed) * 0x9E3779B9u)));
    float life = mix(uLife.x, uLife.y, random(base, 0u));
    if (age < 0.0 || age >= life) {
        // Not alive: every corner on one point, so nothing is rasterized.
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        vBox = vec2(0.0);
        vColor = vec4(0.0);
        return;
    }
    vec2 spawn = vec2(random(base, 1u), random(base, 2u));
    vec2 vel = vec2(mix(uVelX.x, uVelX.y, random(base, 3u)), mix(uVelY.x, uVelY.y, random(base, 4u)));
    float size = mix(uSize.x, uSize.y, random(base, 5u));

    vec2 local = spawn * uBox + vel * uRef * age;
    vec2 f = local / uBox;
    vec2 centre = uOrigin + f.x * uAxisX + f.y * uAxisY;
    vec2 pos = centre + (c - 0.5) * size * uRef * uScale;
    gl_Position = vec4(pos.x / uScreen.x * 2.0 - 1.0, 1.0 - pos.y / uScreen.y * 2.0, 0.0, 1.0);
    vBox = clamp(f, 0.0, 1.0);

    float t = clamp(age / life, 0.0, 1.0);
    vec4 col = mix(uColor0, uColor1, t);
    float span = min(0.25, 0.1 * life);
    col.a *= span > 0.0 ? clamp(age / span, 0.0, 1.0) : 1.0;
    vColor = vec4(col.rgb * col.a, col.a);
}
