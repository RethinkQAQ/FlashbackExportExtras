#version 330

uniform sampler2D InDepth;

layout(std140) uniform DepthParameters {
    float NearPlane;
    float FarPlane;
    float ReversedZ;
    float Linearize;
};
in vec2 texCoord;
layout(location = 0) out float fragDepth;

void main() {
    float depth = texture(InDepth, texCoord).r;
    if (ReversedZ > 0.5) {
        depth = 1.0 - depth;
    }
    if (Linearize > 0.5) {
        float twoNearFar = 2.0 * NearPlane * FarPlane;
        float farMinusNear = FarPlane - NearPlane;
        float farPlusNear = FarPlane + NearPlane;
        depth = twoNearFar / (farPlusNear - (2.0 * depth - 1.0) * farMinusNear);
    }
    fragDepth = depth;
}
