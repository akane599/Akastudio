#version 300 es
// Flat colour written unchanged. Used in display space (already sRGB-encoded values) for the A/B divider.
precision highp float;

uniform vec4 u_color;

layout(location = 0) out vec4 o_color;

void main() {
    o_color = u_color;
}
