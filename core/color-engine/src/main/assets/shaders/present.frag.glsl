#version 300 es
// Final display/readback pass: composites the premultiplied linear scene over the matte colour, clamps to
// [0, 1] and applies the IEC 61966-2-1 sRGB encode. This is the only place sRGB encoding happens.
precision highp float;

in vec2 v_uv;

uniform sampler2D u_tex;
uniform int u_srcLog;
uniform vec3 u_matte;        // linear-light matte behind transparent scene pixels

layout(location = 0) out vec4 o_color;

const float LOG_K = 64.0;
const float LOG_MAX = 16.0;

vec3 logDecode(vec3 e) {
    return (exp2(e * log2(1.0 + LOG_K * LOG_MAX)) - 1.0) / LOG_K;
}

vec4 decodeSource(vec4 c) {
    if (u_srcLog == 0) return c;
    if (c.a <= 0.0) return vec4(0.0);
    return vec4(logDecode(c.rgb / c.a) * c.a, c.a);
}

// IEC 61966-2-1 encode (RenderMath.srgbEncode / ColorMath.linearToSrgb), on clamped input.
vec3 srgbEncode(vec3 linear) {
    vec3 c = clamp(linear, 0.0, 1.0);
    return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, greaterThan(c, vec3(0.0031308)));
}

void main() {
    vec4 c = decodeSource(texture(u_tex, v_uv));
    vec3 composite = c.rgb + u_matte * (1.0 - clamp(c.a, 0.0, 1.0));
    o_color = vec4(srgbEncode(composite), 1.0);
}
