#version 330 core
out vec4 FragColor;
in vec2 TexCoord;

uniform sampler2D screenTexture;
uniform vec2 direction;
uniform float radius;

void main() {
    float sigma = radius / 2.0;
    float weight = 1.0 / (2.0 * 3.14159 * sigma * sigma);
    vec4 color = texture(screenTexture, TexCoord) * weight;
    float totalWeight = weight;
    
    for (float i = 1.0; i <= radius; i++) {
        float w = exp(-(i * i) / (2.0 * sigma * sigma)) * weight;
        color += texture(screenTexture, TexCoord + i * direction) * w;
        color += texture(screenTexture, TexCoord - i * direction) * w;
        totalWeight += 2.0 * w;
    }
    
    FragColor = color / totalWeight;
}
