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
uniform vec4 u_dstRect;
uniform vec4 u_view;
uniform vec2 u_targetSize;
uniform mat3 u_uvMatrix;

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
    // Reconstruct the canvas pixel centre before mapping to UV. Interpolating UV from differently
    // clipped tile quads can move LINEAR sampling across a filter precision step at a texel centre.
    vec2 targetPx = vec2(gl_FragCoord.x, u_targetSize.y - gl_FragCoord.y);
    vec2 canvasPx = (targetPx - u_view.zw) / u_view.xy;
    vec2 unit = (canvasPx - u_dstRect.xy) / (u_dstRect.zw - u_dstRect.xy);
    vec2 uv = (u_uvMatrix * vec3(unit, 1.0)).xy;
    o_color = encodeTarget(texture(u_tex, uv) * coverage);
}
