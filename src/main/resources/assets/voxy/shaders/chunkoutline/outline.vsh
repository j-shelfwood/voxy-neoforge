#version 460

layout(binding = 0, std140) uniform SceneUniform {
    mat4 MVP;
    ivec4 cameraBlockPos;
    vec4 negInnerBlock;
};

layout(binding = 1, std430) restrict readonly buffer ChunkPosBuffer {
    ivec2[] chunkPos;
};

ivec3 unpackPos(ivec2 pos) {
    return ivec3(pos.y>>10, (pos.x<<12)>>12, ((pos.y<<22)|int(uint(pos.x)>>10))>>10);
}

bool shouldRender(ivec3 icorner) {
    // Match the nearest-point-in-AABB test used by vanilla chunk visibility boundaries.
    // Section AABB is [icorner, icorner + 16], find nearest point to local camera origin.
    ivec3 maxCorner = icorner + 16;
    ivec3 nearest = ivec3(0);
    nearest.x = (icorner.x > 0) ? icorner.x : ((maxCorner.x < 0) ? maxCorner.x : 0);
    nearest.y = (icorner.y > 0) ? icorner.y : ((maxCorner.y < 0) ? maxCorner.y : 0);
    nearest.z = (icorner.z > 0) ? icorner.z : ((maxCorner.z < 0) ? maxCorner.z : 0);

    vec3 dist = vec3(nearest) - negInnerBlock.xyz;
    bool visible = (dist.x * dist.x + dist.z * dist.z) < (negInnerBlock.w * negInnerBlock.w);
    visible = visible && abs(dist.y) < negInnerBlock.w;
    return visible;
}

#ifdef TAA
vec2 getTAA();
#endif

void main() {
    uint id = (gl_InstanceID<<5)+gl_BaseInstance+(gl_VertexID>>3);

    ivec3 origin = unpackPos(chunkPos[id])*16;
    origin -= cameraBlockPos.xyz;

    if (!shouldRender(origin)) {
        gl_Position = vec4(-100.0f, -100.0f, -100.0f, 0.0f);
        return;
    }

    ivec3 cubeCornerI = ivec3(gl_VertexID&1, (gl_VertexID>>2)&1, (gl_VertexID>>1)&1)*16;
    //Expand the y height to be big (will be +- 8192)
    //TODO: make it W.R.T world height and offsets
    //cubeCornerI.y = cubeCornerI.y*1024-512;
    gl_Position = MVP * vec4(vec3(cubeCornerI+origin), 1);
    gl_Position.z -= 0.0005f;

    #ifdef TAA
    gl_Position.xy += getTAA()*gl_Position.w;//Apply TAA if we have it
    #endif
}
