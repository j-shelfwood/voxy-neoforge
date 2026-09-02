#version 460 core

layout(binding = 0) uniform sampler2D blockModelAtlas;
layout(location = 0) in flat uvec4 interData;
layout(location = 1) in vec2 uv;

uint getFace() {
    return (interData.x >> 4) & 7u;
}

vec2 getBaseUV() {
    uint face = getFace();
    uint modelId = interData.x >> 16;
    vec2 modelUV = vec2(modelId & 0xFFu, (modelId >> 8) & 0xFFu) * (1.0 / 256.0);
    return modelUV + (vec2(face >> 1, face & 1u) * (1.0 / (vec2(3.0, 2.0) * 256.0)));
}

bool useDiscard() {
    return (interData.x & 1u) == 1u;
}

void main() {
    vec2 tile;
    vec2 uv2 = modf(uv, tile) * (1.0 / (vec2(3.0, 2.0) * 256.0));
    vec2 texPos = uv2 + getBaseUV();

    if (any(notEqual(clamp(tile, vec2(0.0), vec2((interData.x >> 8) & 0xFu, (interData.x >> 12) & 0xFu)), tile))) {
        discard;
    }

    // Preserve alpha-cutout foliage and leave opaque geometry fully solid.
    if (useDiscard() && textureLod(blockModelAtlas, texPos, 0.0).a <= 0.1) {
        discard;
    }
}
