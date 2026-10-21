#version 300 es
// Halation bright pass (grade.Halation), written at half resolution: each output texel averages the bright
// pass of the four full-resolution texels it covers. Bright pass: k = e / (KNEE + e) with
// e = max(0, Y - THRESHOLD) on Rec.709 luminance of the non-negative colour, times that colour.
// HALATION_*, LUMA_709 come from the GlslConstants prelude.
precision highp float;

in vec2 v_uv;

uniform sampler2D u_tex;      // graded full-resolution texture, premultiplied linear light
uniform vec2 u_srcTexel;      // one source texel in texture units

layout(location = 0) out vec4 o_color;

vec3 brightPass(vec4 c) {
    // Premultiplied input: the bright pass of the straight colour, weighted by coverage.
    if (c.a <= 0.0) return vec3(0.0);
    vec3 rgb = max(c.rgb / c.a, vec3(0.0));
    float e = max(0.0, dot(LUMA_709, rgb) - HALATION_THRESHOLD);
    return e > 0.0 ? (e / (HALATION_KNEE + e)) * rgb * c.a : vec3(0.0);
}

void main() {
    vec2 d = 0.5 * u_srcTexel;
    vec3 sum = brightPass(texture(u_tex, v_uv + vec2(-d.x, -d.y)))
        + brightPass(texture(u_tex, v_uv + vec2(d.x, -d.y)))
        + brightPass(texture(u_tex, v_uv + vec2(-d.x, d.y)))
        + brightPass(texture(u_tex, v_uv + vec2(d.x, d.y)));
    o_color = vec4(0.25 * sum, 1.0);
}
