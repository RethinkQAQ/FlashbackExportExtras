#version 330

uniform sampler2D InSampler;

in vec2 texCoord;
out vec4 fragColor;

vec3 srgbDecodeSafe(vec3 color) {
    vec3 signColor = sign(color);
    vec3 absoluteColor = abs(color);
    vec3 linear = mix(
        pow((absoluteColor + vec3(0.055)) / vec3(1.055), vec3(2.4)),
        absoluteColor / vec3(12.92),
        lessThan(absoluteColor, vec3(0.04045))
    );
    return linear * signColor;
}

void main() {
    vec4 color = texture(InSampler, texCoord);
    fragColor = vec4(srgbDecodeSafe(color.rgb), 1.0);
}
