#version 300 es
// A scene's particle layer (Live backgrounds § 3d): the particle's image (premultiplied) tinted by
// its colour, else a soft dot; times the layer's opacity and mask. The blend mode is set on the GL
// side, as for the quad layers. Textures are stored bottom row first, hence the flipped v.
precision highp float;
in vec2 vUv;
in vec2 vBox;
in vec4 vColor;
uniform sampler2D uTex;    // unit 0: the particle's image, when uHasTex is 1
uniform sampler2D uMask;   // unit 1: the layer's mask over the emitter's box, when uHasMask is 1
uniform float uHasTex;
uniform float uHasMask;
uniform float uOpacity;
out vec4 fragColor;

void main() {
    vec4 c;
    if (uHasTex > 0.5) {
        // Premultiplied texel × premultiplied tint: (rgb·t·a, a·a_t).
        c = texture(uTex, vec2(vUv.x, 1.0 - vUv.y)) * vColor;
    } else {
        vec2 d = vUv * 2.0 - 1.0;
        float r = clamp(1.0 - dot(d, d), 0.0, 1.0);
        c = vColor * (r * r);
    }
    float m = uHasMask > 0.5 ? texture(uMask, vec2(vBox.x, 1.0 - vBox.y)).a : 1.0;
    fragColor = c * (uOpacity * m);
}
