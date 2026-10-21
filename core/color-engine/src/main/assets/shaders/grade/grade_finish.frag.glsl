#version 300 es
// Stages 7-8 of CpuGradingPipeline.grade over the graded layer:
// 7. halation: the three blurred bright-pass levels mixed HALATION_WEIGHTS, tinted HALATION_TINT and added
//    scaled by u_halationAmount (0 = off);
// 8. film grain (grade.FilmGrain): bilinear value noise on a lattice every u_grainSize OUTPUT pixels, built
//    from the same PCG-RXS-M-XS hash, node seeding and 4-uniform sum as the CPU, applied in OKLab with the
//    mid-tone envelope 4L(1 - L). The lattice runs in output-pixel space (the layer's canvas pixels), so the
//    grain keeps its size when the crop zooms; u_grainSeed is the layer slot.
precision highp float;
precision highp int;

in vec2 v_uv;

uniform sampler2D u_tex;              // graded texture, premultiplied linear light
uniform sampler2D u_glow0;            // bright pass blurred to 1, 2 and 4 sigma (same normalized space)
uniform sampler2D u_glow1;
uniform sampler2D u_glow2;
uniform float u_halationAmount;       // includes Halation.STRENGTH; 0 = off
uniform float u_grainAmount;          // 0 = off
uniform float u_grainSize;            // lattice spacing, output pixels
uniform float u_grainLuma;            // amount * FilmGrain.STRENGTH
uniform float u_grainChroma;          // grainLuma * COLOR_RATIO * look grainChroma
uniform int u_grainSeed;
uniform vec2 u_grainOrigin;           // crop region origin, working texels (row 0 = top of the source)
uniform vec2 u_grainScale;            // output pixels per working texel

layout(location = 0) out vec4 o_color;

const float INV_2_24 = 1.0 / 16777216.0;

float cbrtSigned(float x) {
    if (x == 0.0) return 0.0;
    return sign(x) * exp2(log2(abs(x)) / 3.0);
}

vec3 okLabFromLinear(vec3 rgb) {
    vec3 lms = OKLAB_M1 * rgb;
    return OKLAB_M2 * vec3(cbrtSigned(lms.x), cbrtSigned(lms.y), cbrtSigned(lms.z));
}

vec3 linearFromOkLab(vec3 lab) {
    vec3 lms = OKLAB_M2_INV * lab;
    return OKLAB_M1_INV * (lms * lms * lms);
}

// FilmGrain.pcg: uint arithmetic wraps exactly like the Kotlin Int version.
uint pcg(uint v) {
    uint state = v * 747796405u + 2891336453u;
    uint word = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;
    return (word >> 22u) ^ word;
}

float uniform01(uint h) {
    return float(h >> 8u) * INV_2_24;
}

// FilmGrain.lattice: approximately standard-normal value at node (ix, iy). int -> uint keeps the bit pattern.
float lattice(int ix, int iy, int channel) {
    uint h0 = pcg(uint(ix) + pcg(uint(iy) + pcg(uint(u_grainSeed) + pcg(uint(channel)))));
    uint h1 = pcg(h0);
    uint h2 = pcg(h1);
    uint h3 = pcg(h2);
    float sum = uniform01(h0) + uniform01(h1) + uniform01(h2) + uniform01(h3);
    return (sum - 2.0) * GRAIN_UNIT_VARIANCE;
}

// FilmGrain.noise at output-pixel position p (the pixel centre, x + 0.5 on the CPU).
float valueNoise(vec2 p, int channel) {
    vec2 f = p / u_grainSize - 0.5;
    vec2 f0 = floor(f);
    vec2 t = f - f0;
    int ix = int(f0.x);
    int iy = int(f0.y);
    float n00 = lattice(ix, iy, channel);
    float n10 = lattice(ix + 1, iy, channel);
    float n01 = lattice(ix, iy + 1, channel);
    float n11 = lattice(ix + 1, iy + 1, channel);
    float top = n00 + (n10 - n00) * t.x;
    float bottom = n01 + (n11 - n01) * t.x;
    return top + (bottom - top) * t.y;
}

void main() {
    vec4 src = texture(u_tex, v_uv);
    if (src.a <= 0.0) {
        o_color = vec4(0.0);
        return;
    }
    vec3 rgb = src.rgb / src.a;

    if (u_halationAmount > 0.0) {
        vec3 glow = HALATION_WEIGHTS.x * texture(u_glow0, v_uv).rgb
            + HALATION_WEIGHTS.y * texture(u_glow1, v_uv).rgb
            + HALATION_WEIGHTS.z * texture(u_glow2, v_uv).rgb;
        rgb += u_halationAmount * HALATION_TINT * glow;
    }

    if (u_grainAmount > 0.0) {
        vec3 lab = okLabFromLinear(rgb);
        float lc = clamp(lab.x, 0.0, 1.0);
        float envelope = 4.0 * lc * (1.0 - lc);
        if (envelope != 0.0) {
            vec2 p = (gl_FragCoord.xy - u_grainOrigin) * u_grainScale;
            lab.x += valueNoise(p, 0) * u_grainLuma * envelope;
            if (u_grainChroma > 0.0) {
                lab.y += valueNoise(p, 1) * u_grainChroma * envelope;
                lab.z += valueNoise(p, 2) * u_grainChroma * envelope;
            }
            rgb = linearFromOkLab(lab);
        }
    }

    o_color = vec4(rgb * src.a, src.a);
}
