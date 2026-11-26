#version 300 es
// One layer of a scene (Live backgrounds § 3b): a quad from gl_VertexID (a 4-vertex strip), placed
// by an origin and two edges in clip space, so a layer can move, scale and rotate without buffers.
uniform vec2 uOrigin;   // clip-space corner where the layer's top-left texel lands
uniform vec2 uAxisX;    // clip-space edge along the layer's width
uniform vec2 uAxisY;    // clip-space edge along the layer's height
uniform vec4 uUv;       // u0, v0, du, dv: the texture window, v down from the image's top
out vec2 vUv;           // into the texture window, v down
out vec2 vCorner;       // 0..1 across the layer, y down (its mask, the background gradient)
void main() {
    vec2 c = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
    vCorner = c;
    vUv = uUv.xy + c * uUv.zw;
    gl_Position = vec4(uOrigin + c.x * uAxisX + c.y * uAxisY, 0.0, 1.0);
}
