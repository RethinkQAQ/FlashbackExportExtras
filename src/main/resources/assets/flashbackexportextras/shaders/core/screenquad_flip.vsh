#version 330

in vec3 Position;
in vec2 UV0;

out vec2 texCoord;

void main() {
    gl_Position = vec4(Position.xy * 2.0 - 1.0, 0.0, 1.0);
    // Flip Y-axis: GL bottom-left origin → image top-left origin
    texCoord = vec2(UV0.x, 1.0 - UV0.y);
}
