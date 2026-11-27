UI_LOCATION(0) in vec2 local;
UI_LOCATION(1) in vec4 tint;
UI_LOCATION(2) flat in vec4 shape;
UI_LOCATION(3) flat in vec4 details;
UI_LOCATION(0) out vec4 fragColor;

const float turn = 6.28318530718;

float roundedDistance() {
    float r = local.y < 0.0
        ? (local.x < 0.0 ? details.x : details.y)
        : (local.x < 0.0 ? details.w : details.z);
    r = clamp(r, 0.0, min(shape.x, shape.y));
    vec2 q = abs(local) - shape.xy + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

float arcDistance() {
    float width = min(shape.w, shape.x);
    float mid = shape.x - width * 0.5;
    float angle = mod(atan(local.y, local.x) - details.x, turn);
    if (details.y >= turn - 0.00001 || angle <= details.y)
        return abs(length(local) - mid) - width * 0.5;
    vec2 a = mid * vec2(cos(details.x), sin(details.x));
    float end = details.x + details.y;
    vec2 b = mid * vec2(cos(end), sin(end));
    return min(length(local - a), length(local - b)) - width * 0.5;
}

void main() {
    int kind = int(shape.z);
    float d = kind == 3 ? arcDistance() : roundedDistance();
    if (kind == 1) d = max(d, -d - shape.w);
    float aa = max(fwidth(d) * 0.5, 0.0001);
    float coverage = kind == 2
        ? 1.0 - smoothstep(-aa, shape.w, d)
        : 1.0 - smoothstep(-aa, aa, d);
    vec3 color = tint.rgb;
    if (kind == 4) {
        float hue = clamp(0.5 + 0.5 * local.x / shape.x, 0.0, 1.0);
        color = clamp(abs(fract(hue + vec3(0.0, 2.0/3.0, 1.0/3.0)) * 6.0 - 3.0) - 1.0, 0.0, 1.0);
    }
    fragColor = vec4(color, tint.a * coverage);
}
