#version 460

layout(binding = 0, std140) uniform SceneUniform {
    mat4 MVP;
    ivec4 cameraBlockPos;
    vec4 negInnerBlock;
};

layout(binding = 1, std430) restrict readonly buffer ChunkPosBuffer {
    ivec4[] chunkPos;
};

#ifdef TAA
vec2 getTAA();
#endif

void main() {
    uint id = (gl_InstanceID<<5)+gl_BaseInstance+(gl_VertexID>>3);

    ivec4 span = chunkPos[id];
    int heightBlocks = max(16, (span.w - span.z) * 16);
    ivec3 origin = ivec3(span.x * 16, span.z * 16, span.y * 16);
    origin -= cameraBlockPos.xyz;

    ivec3 cubeCornerI = ivec3(gl_VertexID&1, 0, (gl_VertexID>>1)&1)*16;
    cubeCornerI.y = ((gl_VertexID>>2)&1) * heightBlocks;
    //Expand the y height to be big (will be +- 8192)
    //TODO: make it W.R.T world height and offsets
    //cubeCornerI.y = cubeCornerI.y*1024-512;
    gl_Position = MVP * vec4(vec3(cubeCornerI+origin), 1);
    gl_Position.z -= 0.0005f;

    #ifdef TAA
    gl_Position.xy += getTAA()*gl_Position.w;//Apply TAA if we have it
    #endif
}
