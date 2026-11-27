UI_LOCATION(0) in vec2 local;
UI_LOCATION(1) in vec4 tint;
UI_LOCATION(0) out vec4 fragColor;
uniform sampler2D Sampler0;

void main() {
    vec4 color = texture(Sampler0, local) * tint;
    if (color.a == 0.0) discard;
    fragColor = color;
}
