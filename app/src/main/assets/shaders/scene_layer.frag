#version 300 es
// One layer of a scene (Live backgrounds § 3b), or the theme gradient under them all (uMode 1).
// Layer textures are premultiplied; the blend mode is set on the GL side, so this only applies the
// layer's opacity and mask. Textures are stored bottom row first, hence the flipped v.
precision highp float;
in vec2 vUv;
in vec2 vCorner;
uniform sampler2D uTex;    // unit 0: the layer's image (premultiplied alpha)
uniform sampler2D uMask;   // unit 1: its mask (alpha), when uHasMask is 1
uniform float uHasMask;
uniform float uOpacity;
uniform int uMode;         // 0: a textured layer, 1: the theme gradient
uniform vec4 uColorTop;
uniform vec4 uColorBottom;
out vec4 fragColor;

void main() {
    if (uMode == 1) {
        vec3 col = mix(uColorBottom.rgb, uColorTop.rgb, smoothstep(0.0, 1.0, 1.0 - vCorner.y));
        fragColor = vec4(col, 1.0);
        return;
    }
    vec4 c = texture(uTex, vec2(vUv.x, 1.0 - vUv.y));
    float m = uHasMask > 0.5 ? texture(uMask, vec2(vCorner.x, 1.0 - vCorner.y)).a : 1.0;
    fragColor = c * (uOpacity * m);
}
