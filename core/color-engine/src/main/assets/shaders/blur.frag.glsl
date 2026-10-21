#version 300 es
// One direction of a separable Gaussian. Taps are mirrored around the centre with normalized weights from
// RenderMath.gaussianWeights; filtering happens in linear light even on log-domain fallback targets.
precision highp float;

in vec2 v_uv;

uniform sampler2D u_tex;
uniform vec2 u_texelStep;            // one texel along the blur direction, texture units
uniform float u_weights[33];         // index 0 = centre; MAX_RADIUS + 1 entries
uniform int u_blurRadius;            // taps used on each side, <= MAX_RADIUS
uniform int u_srcLog;
uniform int u_dstLog;

layout(location = 0) out vec4 o_color;

const int MAX_RADIUS = 32;
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
    vec4 sum = decodeSource(texture(u_tex, v_uv)) * u_weights[0];
    int radius = min(u_blurRadius, MAX_RADIUS);
    for (int i = 1; i <= MAX_RADIUS; ++i) {
        if (i > radius) break;
        vec2 offset = u_texelStep * float(i);
        sum += (decodeSource(texture(u_tex, v_uv + offset)) + decodeSource(texture(u_tex, v_uv - offset))) * u_weights[i];
    }
    o_color = encodeTarget(sum);
}
