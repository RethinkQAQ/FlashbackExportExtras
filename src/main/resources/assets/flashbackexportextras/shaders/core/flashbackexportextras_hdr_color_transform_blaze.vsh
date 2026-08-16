#version 330

out vec2 texCoord;

void main() {
    // The two vertices at x/y = 3 lie outside the viewport. Together with
    // (-1, -1) they form a single triangle that covers the entire screen.
    vec2 triangle = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    gl_Position = vec4(triangle * 2.0 - 1.0, 0.0, 1.0);
    // Interpolation across the visible part of the oversized triangle maps
    // this 0..2 range to the source texture's complete 0..1 range.
    texCoord = vec2(triangle.x, 1.0 - triangle.y);
}
