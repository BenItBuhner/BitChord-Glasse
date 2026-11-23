package com.benitbuhner.liquidglass

/** AGSL programs (API 33+). Coordinates are local pixels of the glass node. */
internal object Shaders {

    private const val COMMON = """
uniform shader content;
uniform float uMaxD;
uniform float uCheb[8];
uniform float2 uMag;
uniform float uAberration;
uniform float4 uTint;
uniform float4 uTintDark;
uniform float uAdaptive;
uniform float uSaturation;
uniform float uBrightness;
uniform float2 uLight;
uniform float uSpecular;
uniform float uRimDark;

float profD(float t) {
    float x = 2.0 * t - 1.0;
    float b1 = 0.0;
    float b2 = 0.0;
    for (int k = 7; k >= 1; k--) {
        float tmp = 2.0 * x * b1 - b2 + uCheb[k];
        b2 = b1;
        b1 = tmp;
    }
    return uCheb[0] + x * b1 - b2;
}

float luma(half3 c) { return dot(float3(c), float3(0.2126, 0.7152, 0.0722)); }

half4 sampleGlass(float2 sc, float2 v) {
    half4 col;
    if (uAberration > 0.001) {
        float2 va = v * uAberration;
        half4 mid = content.eval(sc);
        col = half4(content.eval(sc + va).r, mid.g, content.eval(sc - va).b, mid.a);
    } else {
        col = content.eval(sc);
    }
    return col;
}

// Glass optics + tint + specular, given position c, inward depth dIn, outward normal g, bezel bz,
// center-relative position p and the AA coverage.
half4 shade(float2 c, float2 p, float dIn, float2 g, float bz, float aa, float lavg) {
    float t = clamp(dIn / bz, 0.0, 1.0);
    float m = t < 1.0 ? max(profD(t), 0.0) * uMaxD : 0.0;
    float2 v = -p * uMag - g * m;
    half4 col = sampleGlass(c + v, v);

    // unpremultiply, colour-grade (saturate / brighten like the CSS backdrop chain), re-premultiply
    float a = float(col.a);
    float3 rgb = a > 0.001 ? float3(col.rgb) / a : float3(0.0);
    float l = dot(rgb, float3(0.2126, 0.7152, 0.0722));
    rgb = mix(float3(l), rgb, uSaturation) * uBrightness;
    rgb = clamp(rgb, 0.0, 1.0);

    // adaptive tint: over dark content lean on the dark tint, over light content the light one
    float k = uAdaptive * (1.0 - smoothstep(0.25, 0.6, lavg));
    float4 tint = mix(uTint, uTintDark, k);
    float ta = tint.a;
    float3 outRgb = rgb * a * (1.0 - ta) + tint.rgb * ta;
    float outA = a * (1.0 - ta) + ta;

    // specular: a thin rim line brightest facing the light (and its opposite edge),
    // plus a soft inner glow band across the bezel on the lit side
    float fac = dot(g, uLight);
    float rimLine = 1.0 - smoothstep(0.0, 1.6, dIn);
    float band = 1.0 - smoothstep(0.0, bz * 0.7, dIn);
    float hl = rimLine * (0.28 + 0.72 * pow(abs(fac), 2.0)) + band * 0.22 * pow(max(fac, 0.0), 2.0);
    hl *= uSpecular;
    outRgb = outRgb + (float3(outA) - outRgb) * clamp(hl, 0.0, 1.0);
    // faint darkening on the shadow-side rim so the edge reads on light backdrops
    outRgb *= 1.0 - uRimDark * band * 0.5 * max(-fac, 0.0);
    return half4(half3(outRgb * aa), half(outA * aa));
}

float lumAt(float2 q) {
    half4 s = content.eval(q);
    return s.a > 0.01 ? luma(s.rgb / s.a) : 0.6;
}
"""

    /** One rounded rectangle filling the node. */
    const val GLASS = COMMON + """
uniform float2 uSize;
uniform float uRadius;
uniform float uBezel;

float sdRR(float2 p, float2 hs, float r) {
    float2 q = abs(p) - hs + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

float2 gradRR(float2 p, float2 hs, float r) {
    float2 q = abs(p) - (hs - r);
    float2 s = float2(p.x >= 0.0 ? 1.0 : -1.0, p.y >= 0.0 ? 1.0 : -1.0);
    if (q.x > 0.0 && q.y > 0.0) return normalize(q) * s;
    if (q.x > q.y) return float2(s.x, 0.0);
    return float2(0.0, s.y);
}

half4 main(float2 c) {
    float2 hs = uSize * 0.5;
    float2 p = c - hs;
    float r = min(uRadius, min(hs.x, hs.y));
    float d = sdRR(p, hs, r);
    float aa = clamp(0.5 - d, 0.0, 1.0);
    if (aa <= 0.0) return half4(0.0);
    float bz = max(1.0, min(uBezel, min(hs.x, hs.y)));
    float lavg = 0.6;
    if (uAdaptive > 0.0) {
        lavg = (lumAt(hs) + lumAt(hs * float2(0.4, 0.5)) + lumAt(hs * float2(1.6, 0.5))
              + lumAt(hs * float2(0.5, 1.5)) + lumAt(hs * float2(1.5, 1.5))) / 5.0;
    }
    return shade(c, p, max(-d, 0.0), gradRR(p, hs, r), bz, aa, lavg);
}
"""

    /** Smooth union of up to 8 rounded rects (Apple's GlassEffectContainer). */
    const val BLOBS = COMMON + """
uniform float4 uShapes[8];   // cx, cy, w, h
uniform float uRadii[8];
uniform float uCount;
uniform float uK;
uniform float uBezel;

float sdRR(float2 p, float2 hs, float r) {
    float2 q = abs(p) - hs + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

float field(float2 c) {
    float d = 1e5;
    for (int i = 0; i < 8; i++) {
        if (float(i) >= uCount) break;
        float4 s = uShapes[i];
        float2 hs = s.zw * 0.5;
        float di = sdRR(c - s.xy, hs, min(uRadii[i], min(hs.x, hs.y)));
        if (i == 0) {
            d = di;
        } else if (uK <= 0.0) {
            d = min(d, di);
        } else {
            float h = max(uK - abs(d - di), 0.0) / uK;
            d = min(d, di) - h * h * uK * 0.25;
        }
    }
    return d;
}

// centre of the nearest shape, so the gentle lens magnifies toward each blob's own centre
float2 nearestCenter(float2 c) {
    float best = 1e9;
    float2 cc = c;
    for (int i = 0; i < 8; i++) {
        if (float(i) >= uCount) break;
        float4 s = uShapes[i];
        float2 hs = s.zw * 0.5;
        float di = sdRR(c - s.xy, hs, min(uRadii[i], min(hs.x, hs.y)));
        if (di < best) { best = di; cc = s.xy; }
    }
    return cc;
}

half4 main(float2 c) {
    float d = field(c);
    float aa = clamp(0.5 - d, 0.0, 1.0);
    if (aa <= 0.0) return half4(0.0);
    float e = 0.75;
    float2 g = float2(field(c + float2(e, 0.0)) - field(c - float2(e, 0.0)),
                      field(c + float2(0.0, e)) - field(c - float2(0.0, e)));
    float gl = length(g);
    g = gl > 1e-4 ? g / gl : float2(0.0, -1.0);
    float2 ctr = nearestCenter(c);
    float lavg = 0.6;
    if (uAdaptive > 0.0) lavg = (lumAt(ctr) + lumAt(c)) * 0.5;
    return shade(c, c - ctr, max(-d, 0.0), g, uBezel, aa, lavg);
}
"""
}
