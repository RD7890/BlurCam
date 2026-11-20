package com.blurcam.app.pipeline

/**
 * GLSL ES 3.00 sources. These exact strings are also executed by the offline test harness
 * (tools/harness) on a real depth map, so what ships is what was tested.
 *
 * Conventions: every texture/FBO stores the UPRIGHT image with row 0 = image TOP (v = 0 at top).
 * uFlip = +1 when drawing to a window surface (encoder), -1 when drawing into an FBO / pbuffer.
 */
object Shaders {

    const val VERTEX = """#version 300 es
uniform float uFlip;
out vec2 vUv;
void main() {
    vec2 p = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1)) * 2.0 - 1.0;
    gl_Position = vec4(p, 0.0, 1.0);
    vUv = vec2(p.x * 0.5 + 0.5, 0.5 - 0.5 * p.y * uFlip);
}
"""

    /** Planar YUV (any rotation) or RGB bitmap -> upright RGB at output size. */
    const val INGEST = """#version 300 es
precision highp float;
precision highp sampler2D;
in vec2 vUv;
out vec4 outColor;

uniform int uMode;        // 0 = planar YUV, 1 = RGB bitmap
uniform int uRot;         // clockwise quarter turns that make the stored frame upright
uniform sampler2D uY;
uniform sampler2D uU;
uniform sampler2D uV;
uniform sampler2D uRgb;
uniform vec4 uYuvA;       // yOffset, yScale, chromaScale, unused
uniform vec4 uYuvB;       // kR, kGu, kGv, kB

vec2 toStorage(vec2 uv) {
    if (uRot == 1) return vec2(uv.y, 1.0 - uv.x);
    if (uRot == 2) return vec2(1.0 - uv.x, 1.0 - uv.y);
    if (uRot == 3) return vec2(1.0 - uv.y, uv.x);
    return uv;
}

void main() {
    if (uMode == 1) {
        outColor = vec4(textureLod(uRgb, vUv, 0.0).rgb, 1.0);
        return;
    }
    vec2 st = toStorage(vUv);
    float y = (textureLod(uY, st, 0.0).r - uYuvA.x) * uYuvA.y;
    float u = (textureLod(uU, st, 0.0).r - 0.5) * uYuvA.z;
    float v = (textureLod(uV, st, 0.0).r - 0.5) * uYuvA.z;
    vec3 rgb = vec3(y + uYuvB.x * v, y - uYuvB.y * u - uYuvB.z * v, y + uYuvB.w * u);
    outColor = vec4(clamp(rgb, 0.0, 1.0), 1.0);
}
"""

    /**
     * Circle-of-confusion map. Depth (low-res, temporally interpolated) is upsampled with a joint
     * bilateral filter guided by the full-res frame so depth edges snap to real image edges.
     */
    const val COC = """#version 300 es
precision highp float;
precision highp sampler2D;
in vec2 vUv;
out vec4 outColor;

uniform sampler2D uRgb;       // upright frame with mip chain
uniform sampler2D uDepthA;    // R32F, 0..1, 1 = near
uniform sampler2D uDepthB;
uniform vec2 uDepthSize;
uniform float uMix;           // 0 -> A, 1 -> B
uniform float uFocus;         // focus disparity 0..1
uniform float uDof;           // half width of the in-focus zone (disparity units)
uniform float uSlope;         // disparity span over which blur ramps from 0 to max
uniform float uLod;           // mip level matching the depth resolution
uniform float uSigma;         // guide colour tolerance

float depthAt(ivec2 i) {
    ivec2 mx = ivec2(uDepthSize) - 1;
    i = clamp(i, ivec2(0), mx);
    return mix(texelFetch(uDepthA, i, 0).r, texelFetch(uDepthB, i, 0).r, uMix);
}

void main() {
    vec2 pos = vUv * uDepthSize - 0.5;
    vec2 base = floor(pos);
    vec2 f = pos - base;
    vec3 c = textureLod(uRgb, vUv, 0.0).rgb;
    float acc = 0.0;
    float ws = 0.0;
    for (int j = 0; j < 2; j++) {
        for (int i = 0; i < 2; i++) {
            ivec2 idx = ivec2(base) + ivec2(i, j);
            ivec2 cl = clamp(idx, ivec2(0), ivec2(uDepthSize) - 1);
            vec2 cuv = (vec2(cl) + 0.5) / uDepthSize;
            vec3 g = textureLod(uRgb, cuv, uLod).rgb;
            vec3 dc = c - g;
            float sim = exp(-dot(dc, dc) / (2.0 * uSigma * uSigma));
            float wb = (i == 0 ? 1.0 - f.x : f.x) * (j == 0 ? 1.0 - f.y : f.y);
            float w = wb * (0.01 + sim);
            acc += w * depthAt(idx);
            ws += w;
        }
    }
    float d = acc / ws;
    float coc = clamp((abs(d - uFocus) - uDof) / uSlope, 0.0, 1.0);
    outColor = vec4(coc, 0.0, 0.0, 1.0);
}
"""

    /**
     * Disc-kernel gather with scatter-style weights: a sample only contributes if its OWN blur
     * disc reaches this pixel. In-focus pixels (the subject) therefore never bleed into the
     * background, and background never smears over the subject: no halos.
     */
    const val GATHER = """#version 300 es
precision highp float;
precision highp sampler2D;
in vec2 vUv;
out vec4 outColor;

uniform sampler2D uRgb;
uniform sampler2D uCoc;
uniform float uAspect;     // outH / outW
uniform float uRmax;       // max blur radius as a fraction of frame height
uniform float uHeightPx;
uniform float uBloom;      // 0..1

const float GOLDEN = 2.39996323;

void main() {
    vec3 base = textureLod(uRgb, vUv, 0.0).rgb;
    float cp = textureLod(uCoc, vUv, 0.0).r * uRmax;
    if (cp < 0.0006) {
        outColor = vec4(base, 1.0);
        return;
    }
    int taps = int(clamp(cp * uHeightPx * 1.8, 14.0, 64.0));
    vec3 acc = base;
    float wsum = 1.0;
    const float soft = 0.0025;
    for (int i = 0; i < 64; i++) {
        if (i >= taps) break;
        float t = (float(i) + 0.5) / float(taps);
        float rad = sqrt(t) * cp;
        float a = float(i) * GOLDEN;
        vec2 uv = vUv + vec2(cos(a) * uAspect, sin(a)) * rad;
        vec3 c = textureLod(uRgb, uv, 0.0).rgb;
        float cq = textureLod(uCoc, uv, 0.0).r * uRmax;
        float w = clamp((cq + soft - rad) / (2.0 * soft), 0.0, 1.0);
        float luma = dot(c, vec3(0.299, 0.587, 0.114));
        w *= 1.0 + uBloom * 10.0 * pow(luma, 5.0);
        acc += c * w;
        wsum += w;
    }
    vec3 blurred = acc / wsum;
    float m = smoothstep(0.0006, 0.004, cp);
    outColor = vec4(mix(base, blurred, m), 1.0);
}
"""
}
