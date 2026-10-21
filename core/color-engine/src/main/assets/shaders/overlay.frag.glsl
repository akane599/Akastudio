#version 300 es
// Text overlay: the texture is an Android ARGB_8888 raster (sRGB-encoded, premultiplied in encoded space).
// Unpremultiply, decode sRGB to linear, re-premultiply, so compositing happens in linear light.
precision highp float;

in vec2 v_uv;

uniform sampler2D u_tex;
uniform int u_dstLog;

layout(location = 0) out vec4 o_color;

const float LOG_K = 64.0;
const float LOG_MAX = 16.0;

vec3 logEncode(vec3 x) {
    return log2(1.0 + LOG_K * clamp(x, 0.0, LOG_MAX)) / log2(1.0 + LOG_K * LOG_MAX);
}

vec4 encodeTarget(vec4 c) {
    if (u_dstLog == 0) return c;
    if (c.a <= 0.0) return vec4(0.0);
    return vec4(logEncode(c.rgb / c.a) * c.a, c.a);
}

// IEC 61966-2-1 decode (RenderMath.srgbDecode / ColorMath.srgbToLinear).
vec3 srgbDecode(vec3 c) {
    return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), greaterThan(c, vec3(0.04045)));
}

void main() {
    vec4 t = texture(u_tex, v_uv);
    if (t.a <= 0.0) {
        o_color = vec4(0.0);
        return;
    }
    vec3 straight = clamp(t.rgb / t.a, 0.0, 1.0);
    o_color = encodeTarget(vec4(srgbDecode(straight) * t.a, t.a));
}
