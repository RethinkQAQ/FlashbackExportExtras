#version 330

uniform sampler2D InDepth;
in vec2 texCoord;
layout(location = 0) out float fragDepth;

void main() {
    fragDepth = 1.0 - texture(InDepth, texCoord).r;
}
