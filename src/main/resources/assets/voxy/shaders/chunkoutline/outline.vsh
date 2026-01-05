#version 460

layout(binding = 0, std140) uniform SceneUniform {
    mat4 MVP;
    ivec4 section;
    vec4 negInnerSec;
    int boundaryBuffer;  // Configurable safety margin (0-4 blocks)
};

layout(binding = 1, std430) restrict readonly buffer ChunkPosBuffer {
    ivec2[] chunkPos;
};

ivec3 unpackPos(ivec2 pos) {
    return ivec3(pos.y>>10, (pos.x<<12)>>12, ((pos.y<<22)|int(uint(pos.x)>>10))>>10);
}

bool shouldRender(ivec3 icorner) {
    // MC 1.21.1 NeoForge: Configurable boundary buffer for LOD/vanilla chunk transition
    //
    // CORRECTED LOGIC: The buffer should SHRINK the "vanilla present" detection area,
    // NOT expand it. This causes LODs to render in a LARGER area (overlap with vanilla),
    // which is hidden by depth testing. The overlap prevents gaps/pop-in.
    //
    // boundaryBuffer controls the INWARD shrink:
    //   0 = exact match with Sodium (may have gaps at boundaries)
    //   1-4 = shrink detection, LODs overlap with vanilla edge (smoother transition)
    //
    // Higher values = MORE LOD overlap = smoother transitions but more overdraw

    int buf = boundaryBuffer;

    // SHRINK the detection area by moving corners INWARD (opposite of before)
    // This makes fewer chunks register as "vanilla present", so LODs render more
    ivec3 minCorner = icorner + buf;      // Move min corner INWARD (toward center)
    ivec3 maxCorner = icorner + 16 - buf; // Move max corner INWARD (toward center)

    vec3 corner = vec3(mix(mix(ivec3(0), minCorner, greaterThan(minCorner, ivec3(0))), maxCorner, lessThan(maxCorner, ivec3(0))))-negInnerSec.xyz;
    bool visible = (corner.x*corner.x + corner.z*corner.z) < (negInnerSec.w*negInnerSec.w);
    visible = visible && abs(corner.y) < negInnerSec.w;
    return visible;
}

#ifdef TAA
vec2 getTAA();
#endif

void main() {
    uint id = (gl_InstanceID<<5)+gl_BaseInstance+(gl_VertexID>>3);

    ivec3 origin = unpackPos(chunkPos[id])*16;
    origin -= section.xyz;

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