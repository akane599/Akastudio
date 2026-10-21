#version 300 es
// Stages 1-6 of CpuGradingPipeline (PixelGrader.grade) on one premultiplied linear texel, in this order:
// harmonize (OKLab affine), exposure, white balance (3x3), contrast (OKLab L smoothstep), look LUT pair mixed
// by the skin mask and intensity, saturation (OKLCh chroma). The OKLab matrices and the SKIN_*
// window come from the GlslConstants prelude inserted after the #version line at load time.
precision highp float;
precision highp int;
precision highp sampler3D;

in vec2 v_uv;

uniform sampler2D u_tex;              // working texture: premultiplied linear light
uniform highp sampler3D u_lutLook;    // full look, encoded sRGB -> encoded sRGB, red fastest
uniform highp sampler3D u_lutSkinSafe;
uniform float u_lutSize;              // lattice nodes per axis (33)
uniform int u_harmonize;              // 0 when the harmonization is the identity (stage skipped)
uniform vec3 u_harmonizeScale;        // OKLab L, a, b
uniform vec3 u_harmonizeOffset;
uniform float u_exposure;             // 2^exposureEv
uniform mat3 u_whiteBalance;          // WhiteBalance.matrix
uniform float u_contrast;             // [-1, 1]; 0 skips the stage
uniform float u_lookIntensity;        // [0, 1]; 0 skips the stage
uniform float u_skinProtection;       // [0, 1]
uniform float u_chromaScale;          // max(0, 1 + saturation); 1 skips the stage

layout(location = 0) out vec4 o_color;

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

// IEC 61966-2-1, sign-preserving like ColorMath.
float srgbEncode(float c) {
    float a = abs(c);
    float e = a <= 0.0031308 ? a * 12.92 : 1.055 * pow(a, 1.0 / 2.4) - 0.055;
    return sign(c) * e;
}

float srgbDecode(float c) {
    float a = abs(c);
    float l = a <= 0.04045 ? a / 12.92 : pow((a + 0.055) / 1.055, 2.4);
    return sign(c) * l;
}

vec3 srgbEncode3(vec3 c) { return vec3(srgbEncode(c.r), srgbEncode(c.g), srgbEncode(c.b)); }
vec3 srgbDecode3(vec3 c) { return vec3(srgbDecode(c.r), srgbDecode(c.g), srgbDecode(c.b)); }

float hermite(float edge0, float edge1, float x) {
    float t = clamp((x - edge0) / (edge1 - edge0), 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}

float band(float x, float lo, float hi, float feather) {
    return hermite(lo - feather, lo, x) * (1.0 - hermite(hi, hi + feather, x));
}

// analysis.SkinMask.weight: soft lightness, chroma and circular hue bands in OKLCh.
float skinWeight(vec3 lab) {
    float sL = band(lab.x, SKIN_L_LO, SKIN_L_HI, SKIN_L_FEATHER);
    if (sL == 0.0) return 0.0;
    float chroma = length(lab.yz);
    float sC = band(chroma, SKIN_C_LO, SKIN_C_HI, SKIN_C_FEATHER);
    if (sC == 0.0) return 0.0;
    float hue = degrees(atan(lab.z, lab.y));
    if (hue < 0.0) hue += 360.0;
    if (hue >= 360.0) hue = 0.0;
    float raw = mod(abs(hue - SKIN_HUE_CENTRE_DEG), 360.0);
    float hueDistance = raw > 180.0 ? 360.0 - raw : raw;
    float sH = 1.0 - hermite(SKIN_HUE_HALF_WIDTH_DEG, SKIN_HUE_HALF_WIDTH_DEG + SKIN_HUE_FEATHER_DEG, hueDistance);
    return sL * sC * sH;
}

// Lut3D.sample: tetrahedral interpolation over the lattice nodes, walked from the cell origin along the axes
// in decreasing-fraction order (ties broken exactly as on the CPU). Hardware trilinear filtering of the same
// texture at (x * (N - 1) + 0.5) / N misses the 2/255 parity budget on strongly curved looks (measured up to
// 2.8/255 on saturated greens), so the nodes are fetched directly.
vec3 sampleLut(highp sampler3D lut, vec3 encoded) {
    int maxIndex = int(u_lutSize) - 1;
    vec3 f = clamp(encoded, 0.0, 1.0) * float(maxIndex);
    ivec3 base = min(ivec3(f), ivec3(maxIndex - 1));
    vec3 t = f - vec3(base);
    ivec3 first;
    ivec3 second;
    vec3 w; // f1, f2, f3
    if (t.r >= t.g) {
        if (t.g >= t.b) { first = ivec3(1, 0, 0); second = ivec3(0, 1, 0); w = vec3(t.r, t.g, t.b); }
        else if (t.r >= t.b) { first = ivec3(1, 0, 0); second = ivec3(0, 0, 1); w = vec3(t.r, t.b, t.g); }
        else { first = ivec3(0, 0, 1); second = ivec3(1, 0, 0); w = vec3(t.b, t.r, t.g); }
    } else {
        if (t.r >= t.b) { first = ivec3(0, 1, 0); second = ivec3(1, 0, 0); w = vec3(t.g, t.r, t.b); }
        else if (t.g >= t.b) { first = ivec3(0, 1, 0); second = ivec3(0, 0, 1); w = vec3(t.g, t.b, t.r); }
        else { first = ivec3(0, 0, 1); second = ivec3(0, 1, 0); w = vec3(t.b, t.g, t.r); }
    }
    vec3 v0 = texelFetch(lut, base, 0).rgb;
    vec3 v1 = texelFetch(lut, base + first, 0).rgb;
    vec3 v2 = texelFetch(lut, base + first + second, 0).rgb;
    vec3 v3 = texelFetch(lut, base + ivec3(1), 0).rgb;
    return (1.0 - w.x) * v0 + (w.x - w.y) * v1 + (w.y - w.z) * v2 + w.z * v3;
}

void main() {
    vec4 src = texture(u_tex, v_uv);
    if (src.a <= 0.0) {
        o_color = vec4(0.0);
        return;
    }
    vec3 rgb = src.rgb / src.a;

    // 1. Harmonize.
    if (u_harmonize != 0) {
        rgb = linearFromOkLab(u_harmonizeScale * okLabFromLinear(rgb) + u_harmonizeOffset);
    }

    // 2. Exposure.
    rgb *= u_exposure;

    // 3. White balance.
    rgb = u_whiteBalance * rgb;

    // 4. Contrast on OKLab L (both L terms of the bracket use the clamped value).
    bool contrastApplied = u_contrast != 0.0;
    vec3 stage4Lab = vec3(0.0);
    if (contrastApplied) {
        vec3 lab = okLabFromLinear(rgb);
        float lc = clamp(lab.x, 0.0, 1.0);
        float s = lc * lc * (3.0 - 2.0 * lc);
        stage4Lab = vec3(lab.x + u_contrast * (s - lc), lab.yz);
        rgb = linearFromOkLab(stage4Lab);
    }

    // 5. Look LUT pair, skin-protected, mixed by intensity in the encoded domain; HDR headroom kept via m.
    if (u_lookIntensity > 0.0) {
        float mask = skinWeight(contrastApplied ? stage4Lab : okLabFromLinear(rgb)) * u_skinProtection;
        float m = max(1.0, max(rgb.r, max(rgb.g, rgb.b)));
        vec3 encoded = srgbEncode3(rgb / m);
        vec3 full = sampleLut(u_lutLook, encoded);
        vec3 safe = sampleLut(u_lutSkinSafe, encoded);
        vec3 looked = full + (safe - full) * mask;
        rgb = srgbDecode3(encoded + (looked - encoded) * u_lookIntensity) * m;
    }

    // 6. Saturation as OKLCh chroma scale.
    if (u_chromaScale != 1.0) {
        vec3 lab = okLabFromLinear(rgb);
        rgb = linearFromOkLab(vec3(lab.x, lab.yz * u_chromaScale));
    }

    o_color = vec4(rgb * src.a, src.a);
}
