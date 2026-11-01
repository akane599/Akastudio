#version 300 es
// Image layer: samples the (graded) linear texture and applies rounded-rect SDF coverage with 1-px
// analytic anti-aliasing. Output is premultiplied linear light, encoded for the working target.
// Mirrors RenderMath.roundedRectSdf / sdfCoverage / encodeWorking.
precision highp float;

in vec2 v_uv;
in vec2 v_local;

uniform sampler2D u_tex;
uniform vec2 u_halfSize;     // half extents of the destination, canvas pixels
uniform float u_radius;      // corner radius, canvas pixels
uniform float u_aaScale;     // target pixels per canvas pixel
uniform int u_dstLog;        // 1 when the target is an RGBA8 log-domain fallback

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

float roundedRectSdf(vec2 p, vec2 halfSize, float radius) {
    float r = clamp(radius, 0.0, max(0.0, min(halfSize.x, halfSize.y)));
    vec2 q = abs(p) - halfSize + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

void main() {
    float d = roundedRectSdf(v_local, u_halfSize, u_radius);
    float coverage = clamp(0.5 - d * u_aaScale, 0.0, 1.0);
    o_color = encodeTarget(texture(u_tex, v_uv) * coverage);
}
