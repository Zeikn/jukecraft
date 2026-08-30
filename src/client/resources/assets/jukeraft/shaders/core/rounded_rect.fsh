#version 150

uniform vec2 RectSize;
uniform float Radius;
uniform float BorderWidth;
uniform vec4 BorderColor;
uniform float InnerRadius;

in vec4 vertexColor;
in vec2 localPos;

out vec4 fragColor;

float roundedBoxSDF(vec2 p, vec2 size, float radius) {
    vec2 q = abs(p) - size + radius;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
}

void main() {
    float dist = roundedBoxSDF(localPos, RectSize, Radius);
    float aa = max(fwidth(dist), 0.0001);
    float outerAlpha = 1.0 - smoothstep(-aa, aa, dist);

    vec4 col = vertexColor;
    if (BorderWidth > 0.0) {
        float innerDist = dist + BorderWidth;
        float innerAlpha = 1.0 - smoothstep(-aa, aa, innerDist);
        col = mix(BorderColor, vertexColor, innerAlpha);
    }

    // Punches a circular hole so this can render an annulus (ring) shape,
    // since alpha-blending a transparent quad on top can't erase pixels.
    if (InnerRadius > 0.0) {
        float holeDist = length(localPos) - InnerRadius;
        float holeAlpha = 1.0 - smoothstep(-aa, aa, holeDist);
        outerAlpha *= (1.0 - holeAlpha);
    }

    fragColor = vec4(col.rgb, col.a * outerAlpha);
}
