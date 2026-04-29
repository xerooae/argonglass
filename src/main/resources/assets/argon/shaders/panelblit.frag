#version 330 core
out vec4 FragColor;
in vec2 TexCoord;

uniform sampler2D blurTexture;
uniform vec2 rectSize;
uniform float cornerRadius;

float roundedRectSDF(vec2 p, vec2 b, float r) {
    vec2 q = abs(p) - b + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

void main() {
    vec2 p = (TexCoord - 0.5) * rectSize;
    float d = roundedRectSDF(p, rectSize * 0.5, cornerRadius);
    float alpha = 1.0 - smoothstep(-1.0, 1.0, d);
    
    if (alpha <= 0.0) discard;
    
    vec4 color = texture(blurTexture, TexCoord);
    FragColor = vec4(color.rgb, color.a * alpha);
}
