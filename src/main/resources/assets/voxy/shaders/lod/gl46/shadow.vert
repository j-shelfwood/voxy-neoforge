#version 460 core
#extension GL_ARB_gpu_shader_int64 : enable

#define QUAD_BUFFER_BINDING 1
#define MODEL_BUFFER_BINDING 3
#define MODEL_COLOUR_BUFFER_BINDING 4
#define POSITION_SCRATCH_BINDING 5
#define LIGHTING_SAMPLER_BINDING 1

#import <voxy:lod/quad_format.glsl>
#import <voxy:lod/block_model.glsl>
#import <voxy:lod/gl46/bindings.glsl>
#import <voxy:lod/quad_util.glsl>

layout(location = 0) out flat uvec4 interData;
layout(location = 1) out vec2 uv;

void main() {
    QuadData quad;
    setupQuad(quad, quadData[uint(gl_VertexID) >> 2], positionBuffer[gl_BaseInstance], (gl_VertexID & 3) == 1);

    uint cornerId = gl_VertexID & 3;
    gl_Position = getQuadCornerPos(quad, cornerId);
    float radialDistance = length(gl_Position.xy);
    float distortFactor = radialDistance * uShadowMapBias + (1.0 - uShadowMapBias);
    gl_Position.xy /= distortFactor;
    gl_Position.z *= 0.2;
    uv = getCornerUV(quad, cornerId);
    interData = quad.attributeData;
}
