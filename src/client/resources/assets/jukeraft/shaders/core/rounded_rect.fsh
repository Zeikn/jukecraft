#version 330

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};
layout(std140) uniform RoundedRectParams {
    vec4 RectSizeRadiusBorder; // xy = RectSize, z = Radius, w = BorderWidth
    vec4 BorderColor;
    vec4 InnerRadiusPad; // x = InnerRadius
};

in vec4 vertexColor;
in vec2 localPos;

out vec4 fragColor;

float roundedBoxSDF(vec2 p, vec2 size, float radius) {
    vec2 q = abs(p) - size + radius;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
}

void main() {
    vec2 rectSize = RectSizeRadiusBorder.xy;
    float radius = RectSizeRadiusBorder.z;
    float borderWidth = RectSizeRadiusBorder.w;
    float innerRadius = InnerRadiusPad.x;

    float dist = roundedBoxSDF(localPos, rectSize, radius);
    float aa = max(fwidth(dist), 0.0001);
    float outerAlpha = 1.0 - smoothstep(-aa, aa, dist);

    vec4 col = vertexColor * ColorModulator;
    if (borderWidth > 0.0) {
        float innerDist = dist + borderWidth;
        float innerAlpha = 1.0 - smoothstep(-aa, aa, innerDist);
        col = mix(BorderColor, vertexColor * ColorModulator, innerAlpha);
    }

    // Punches a circular hole so this can render an annulus (ring) shape,
    // since alpha-blending a transparent quad on top can't erase pixels.
    if (innerRadius > 0.0) {
        float holeDist = length(localPos) - innerRadius;
        float holeAlpha = 1.0 - smoothstep(-aa, aa, holeDist);
        outerAlpha *= (1.0 - holeAlpha);
    }

    float alpha = col.a * outerAlpha;
    if (alpha <= 0.0) {
        discard;
    }

    fragColor = vec4(col.rgb, alpha);
}
