#version 300 es
// Resampling copy: decodes the source domain, scales colour by u_gain (alpha untouched) and encodes for the
// target. Used for the cover-fit downsample of the frosted background and its dimmed bilinear upscale.
precision highp float;

in vec2 v_uv;

uniform sampler2D u_tex;
uniform float u_gain;
uniform int u_srcLog;        // 1 when the source is an RGBA8 log-domain fallback target
uniform int u_dstLog;        // 1 when the target is an RGBA8 log-domain fallback target

layout(location = 0) out vec4 o_color;

const float LOG_K = 64.0;
const float LOG_MAX = 16.0;

vec3 logEncode(vec3 x) {
    return log2(1.0 + LOG_K * clamp(x, 0.0, LOG_MAX)) / log2(1.0 + LOG_K * LOG_MAX);
}

vec3 logDecode(vec3 e) {
    return (exp2(e * log2(1.0 + LOG_K * LOG_MAX)) - 1.0) / LOG_K;
}

vec4 decodeSource(vec4 c) {
    if (u_srcLog == 0) return c;
    if (c.a <= 0.0) return vec4(0.0);
    return vec4(logDecode(c.rgb / c.a) * c.a, c.a);
}

vec4 encodeTarget(vec4 c) {
    if (u_dstLog == 0) return c;
    if (c.a <= 0.0) return vec4(0.0);
    return vec4(logEncode(c.rgb / c.a) * c.a, c.a);
}

void main() {
    vec4 c = decodeSource(texture(u_tex, v_uv));
    o_color = encodeTarget(vec4(c.rgb * u_gain, c.a));
}
