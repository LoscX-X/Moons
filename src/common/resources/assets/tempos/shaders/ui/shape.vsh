UI_LOCATION(0) in vec3 Position;
UI_LOCATION(1) in vec2 UV0;
UI_LOCATION(2) in vec4 Color;
UI_LOCATION(3) in vec4 Shape;
UI_LOCATION(4) in vec4 Details;

UI_LOCATION(0) out vec2 local;
UI_LOCATION(1) out vec4 tint;
UI_LOCATION(2) flat out vec4 shape;
UI_LOCATION(3) flat out vec4 details;

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
#ifdef UI_TEXTURE_MATRIX_FIRST
    mat4 TextureMat;
#endif
    vec4 ColorModulator;
    vec3 ModelOffset;
#ifndef UI_TEXTURE_MATRIX_FIRST
    mat4 TextureMat;
#endif
};

vec3 readableAccent(vec3 encoded) {
    vec3 bytes = floor(encoded * 255.0 + 0.5);
    float phase = (bytes.r * 256.0 + bytes.g) / 65535.0;
    float hue = fract(ColorModulator.x + phase);
    vec3 wheel = clamp(abs(fract(hue + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0) - 1.0, 0.0, 1.0);
    vec3 rgb = mix(vec3(1.0), wheel, ColorModulator.y) * ColorModulator.z * encoded.b;
    rgb = floor(rgb * 255.0 + 0.5) / 255.0;
    vec3 linear = mix(pow((rgb + 0.055) / 1.055, vec3(2.4)), rgb / 12.92, lessThanEqual(rgb, vec3(0.04045)));
    float luminance = dot(linear, vec3(0.2126, 0.7152, 0.0722));
    float target = 4.0 * (ColorModulator.w + 0.05) - 0.05;
    if (luminance >= target) return rgb;
    linear = mix(linear, vec3(1.0), (target - luminance) / (1.0 - luminance));
    vec3 srgb = mix(1.055 * pow(linear, vec3(1.0 / 2.4)) - 0.055, 12.92 * linear, lessThanEqual(linear, vec3(0.0031308)));
    return min(vec3(1.0), ceil(srgb * 255.0) / 255.0);
}

void main() {
    gl_Position = vec4(Position, 1.0);
    local = UV0;
    tint = Color;
    shape = Shape;
    if (Shape.z >= 16.0) {
        shape.z -= 16.0;
        tint.rgb = readableAccent(Color.rgb);
    }
    details = Details;
}
