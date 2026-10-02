#version 150

uniform sampler2D Sampler0;
in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord);
    if (color.a < 0.001) discard;
    fragColor = color;
}
