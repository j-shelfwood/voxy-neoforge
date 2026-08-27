package me.cortex.voxy.client.core.rendering.hierachical;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import me.cortex.voxy.client.RenderStatistics;
import me.cortex.voxy.client.config.RenderDistancePolicy;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.shader.AutoBindingShader;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.core.rendering.util.PrintfDebugUtil;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.WorldEngine;
import org.lwjgl.system.MemoryUtil;

import static me.cortex.voxy.client.core.rendering.util.PrintfDebugUtil.PRINTF_processor;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_UNPACK_IMAGE_HEIGHT;
import static org.lwjgl.opengl.GL12.GL_UNPACK_SKIP_IMAGES;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL30C.GL_RED_INTEGER;
import static org.lwjgl.opengl.GL42.glMemoryBarrier;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BARRIER_BIT;
import static org.lwjgl.opengl.GL45.*;

// TODO: swap to persistent gpu threads instead of dispatching MAX_ITERATIONS of compute layers
public class HierarchicalOcclusionTraverser {
    public static final boolean HIERARCHICAL_SHADER_DEBUG = System.getProperty("voxy.hierarchicalShaderDebug", "false").equals("true");

    public static final int MAX_REQUEST_QUEUE_SIZE = 64;
    public static final int MAX_QUEUE_SIZE = 200_000;

    private static final int MAX_ITERATIONS = WorldEngine.MAX_LOD_LAYER+1;
    private static final int LOCAL_WORK_SIZE_BITS = 5;

    private final AsyncNodeManager nodeManager;
    private final NodeCleaner nodeCleaner;
    private final RenderGenerationService meshGen;

    private final GlBuffer requestBuffer;

    private final GlBuffer nodeBuffer;
    private final GlBuffer uniformBuffer = new GlBuffer(1024).zero();
    private final GlBuffer statisticsBuffer = new GlBuffer(1024).zero();


    private int topNodeCount;
    private final Int2IntOpenHashMap topNode2idxMapping = new Int2IntOpenHashMap();//Used to store mapping from TLN to array index
    private final int[] idx2topNodeMapping = new int[MAX_QUEUE_SIZE];//Used to map idx to TLN id
    private final GlBuffer topNodeIds = new GlBuffer(MAX_QUEUE_SIZE*4).zero();
    private final GlBuffer queueMetaBuffer = new GlBuffer(4*4*MAX_ITERATIONS).zero();
    private final GlBuffer scratchQueueA = new GlBuffer(MAX_QUEUE_SIZE*4).zero();
    private final GlBuffer scratchQueueB = new GlBuffer(MAX_QUEUE_SIZE*4).zero();

    // Prefetch margin to reduce edge pop-in when rotating without fully disabling culling.
    private static final float REQUEST_FRUSTUM_EXPANSION_BLOCKS =
            Float.parseFloat(System.getProperty("voxy.requestFrustumExpansionBlocks", "48.0"));
    // Keep near plane mostly strict to avoid aggressively warming geometry behind the camera.
    private static final float REQUEST_FRUSTUM_NEAR_EXPANSION_BLOCKS =
            Float.parseFloat(System.getProperty("voxy.requestFrustumNearExpansionBlocks", "0.0"));
    private static final boolean ENABLE_DIRECTIONAL_FRUSTUM_EXPANSION =
            System.getProperty("voxy.requestFrustumDirectionalExpansion", "true").equalsIgnoreCase("true");
    private static final float REQUEST_DIRECTIONAL_EXPANSION_MOTION_SCALE =
            Float.parseFloat(System.getProperty("voxy.requestFrustumDirectionalMotionScale", "3.0"));
    private static final float REQUEST_DIRECTIONAL_EXPANSION_MAX_EXTRA =
            Float.parseFloat(System.getProperty("voxy.requestFrustumDirectionalMaxExtra", "64.0"));
    private static final boolean ENABLE_ROTATION_FRUSTUM_EXPANSION =
            System.getProperty("voxy.requestFrustumRotationExpansion", "true").equalsIgnoreCase("true");
    private static final double REQUEST_ROTATION_EXPANSION_THRESHOLD_DEGREES =
            Double.parseDouble(System.getProperty("voxy.requestFrustumRotationThresholdDegrees", "0.35"));
    private static final float REQUEST_ROTATION_EXPANSION_DEGREES_SCALE =
            Float.parseFloat(System.getProperty("voxy.requestFrustumRotationDegreesScale", "28.0"));
    private static final float REQUEST_ROTATION_EXPANSION_MAX_EXTRA =
            Float.parseFloat(System.getProperty("voxy.requestFrustumRotationMaxExtra", "96.0"));
    private static final int HIZ_DISABLE_FROM_LOD =
            Math.max(0, Integer.parseInt(System.getProperty("voxy.hizDisableFromLod", String.valueOf(MAX_ITERATIONS + 1))));
    private static final int PERF_LOG_INTERVAL_FRAMES =
            Math.max(1, Integer.parseInt(System.getProperty("voxy.traversalPerfLogIntervalFrames", "300")));
    private double lastCamX = Double.NaN, lastCamY = Double.NaN, lastCamZ = Double.NaN;
    private float lastNearPlaneX = Float.NaN, lastNearPlaneY = Float.NaN, lastNearPlaneZ = Float.NaN;
    private double lastRequestBudget = Double.NaN;
    private int traversalFrameCounter = 0;
    private int perfLogFrameCounter = 0;
    private int lastTraversalIntervalFrames = 1;
    private int lastRequestBudgetSize = 0;
    private double lastRotationDegrees = 0.0;
    private float lastRotationExpansionBlocks = 0.0f;

    private static int BINDING_COUNTER = 1;
    private static final int SCENE_UNIFORM_BINDING = BINDING_COUNTER++;
    private static final int REQUEST_QUEUE_BINDING = BINDING_COUNTER++;
    private static final int RENDER_QUEUE_BINDING = BINDING_COUNTER++;
    private static final int NODE_DATA_BINDING = BINDING_COUNTER++;
    private static final int NODE_QUEUE_INDEX_BINDING = BINDING_COUNTER++;
    private static final int NODE_QUEUE_META_BINDING = BINDING_COUNTER++;
    private static final int NODE_QUEUE_SOURCE_BINDING = BINDING_COUNTER++;
    private static final int NODE_QUEUE_SINK_BINDING = BINDING_COUNTER++;
    private static final int RENDER_TRACKER_BINDING = BINDING_COUNTER++;
    private static final int STATISTICS_BUFFER_BINDING = BINDING_COUNTER++;

    private final int hizSampler = glGenSamplers();

    private final AutoBindingShader traversal = Shader.makeAuto(PRINTF_processor)
            .defineIf("DEBUG", HIERARCHICAL_SHADER_DEBUG)
            .defineIf("DISABLE_TRAVERSAL_VISIBILITY_CULLING", !VoxyConfig.CONFIG.isVisibilityCullingEnabled())
            .define("MAX_ITERATIONS", MAX_ITERATIONS)
            .define("LOCAL_SIZE_BITS", LOCAL_WORK_SIZE_BITS)
            .define("MAX_REQUEST_QUEUE_SIZE", MAX_REQUEST_QUEUE_SIZE)
            .define("HIZ_DISABLE_FROM_LOD", HIZ_DISABLE_FROM_LOD)

            .define("HIZ_BINDING", 0)

            .define("SCENE_UNIFORM_BINDING", SCENE_UNIFORM_BINDING)
            .define("REQUEST_QUEUE_BINDING", REQUEST_QUEUE_BINDING)
            .define("RENDER_QUEUE_BINDING", RENDER_QUEUE_BINDING)
            .define("NODE_DATA_BINDING", NODE_DATA_BINDING)

            .define("NODE_QUEUE_INDEX_BINDING", NODE_QUEUE_INDEX_BINDING)
            .define("NODE_QUEUE_META_BINDING", NODE_QUEUE_META_BINDING)
            .define("NODE_QUEUE_SOURCE_BINDING", NODE_QUEUE_SOURCE_BINDING)
            .define("NODE_QUEUE_SINK_BINDING", NODE_QUEUE_SINK_BINDING)

            .define("RENDER_TRACKER_BINDING", RENDER_TRACKER_BINDING)

            .defineIf("HAS_STATISTICS", RenderStatistics.enabled)
            .defineIf("STATISTICS_BUFFER_BINDING", RenderStatistics.enabled, STATISTICS_BUFFER_BINDING)

            .add(ShaderType.COMPUTE, "voxy:lod/hierarchical/traversal_dev.comp")
            .compile();


    public HierarchicalOcclusionTraverser(AsyncNodeManager nodeManager, NodeCleaner nodeCleaner, RenderGenerationService meshGen) {
        this.nodeCleaner = nodeCleaner;
        this.nodeManager = nodeManager;
        this.meshGen = meshGen;
        this.requestBuffer = new GlBuffer(MAX_REQUEST_QUEUE_SIZE*8L+8).zero();
        this.nodeBuffer = new GlBuffer(nodeManager.maxNodeCount*16L).fill(-1);


        glSamplerParameteri(this.hizSampler, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_NEAREST);
        glSamplerParameteri(this.hizSampler, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glSamplerParameteri(this.hizSampler, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glSamplerParameteri(this.hizSampler, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);

        this.traversal
                .ubo("SCENE_UNIFORM_BINDING", this.uniformBuffer)
                .ssbo("REQUEST_QUEUE_BINDING", this.requestBuffer)
                .ssbo("NODE_DATA_BINDING", this.nodeBuffer)
                .ssbo("NODE_QUEUE_META_BINDING", this.queueMetaBuffer)
                .ssbo("RENDER_TRACKER_BINDING", this.nodeCleaner.visibilityBuffer)
                .ssboIf("STATISTICS_BUFFER_BINDING", this.statisticsBuffer);

        this.topNode2idxMapping.defaultReturnValue(-1);
        this.nodeManager.setTLNAddRemoveCallbacks(this::addTLN, this::remTLN);
    }

    private void addTLN(int id) {
        int aid = this.topNodeCount++;//Increment buffer
        if (this.topNodeCount > this.topNodeIds.size()/4) {
            throw new IllegalStateException("Top level node count greater than capacity");
        }

        //Use clear buffer, yes know is a bad idea, TODO: replace
        //Add the new top level node to the queue
        MemoryUtil.memPutInt(SCRATCH, id);
        nglClearNamedBufferSubData(this.topNodeIds.id, GL_R32UI, aid * 4L, 4, GL_RED_INTEGER, GL_UNSIGNED_INT, SCRATCH);

        if (this.topNode2idxMapping.put(id, aid) != -1) {
            throw new IllegalStateException();
        }
        this.idx2topNodeMapping[aid] = id;
    }

    private void remTLN(int id) {
        //Remove id
        int idx = this.topNode2idxMapping.remove(id);
        //Decrement count
        this.topNodeCount--;
        if (idx == -1) {
            throw new IllegalStateException();
        }

        //Count has already been decremented so is an exact match
        //If we are at the end of the array we dont need to do anything
        if (idx == this.topNodeCount) {
            return;
        }

        //Move the entry at the end to the current index
        int endTLNId = this.idx2topNodeMapping[this.topNodeCount];
        this.idx2topNodeMapping[idx] = endTLNId;//Set the old to the new
        if (this.topNode2idxMapping.put(endTLNId, idx) == -1)
            throw new IllegalStateException();

        //Move it server side, from end to new idx
        MemoryUtil.memPutInt(SCRATCH, endTLNId);
        nglClearNamedBufferSubData(this.topNodeIds.id, GL_R32UI, idx*4L, 4, GL_RED_INTEGER, GL_UNSIGNED_INT, SCRATCH);
    }

    private double computeRotationDegrees(Viewport<?> viewport) {
        if (Float.isNaN(this.lastNearPlaneX)) {
            return 0.0;
        }
        float nearPlaneX = viewport.frustumPlanes[4].x;
        float nearPlaneY = viewport.frustumPlanes[4].y;
        float nearPlaneZ = viewport.frustumPlanes[4].z;
        double dot = nearPlaneX * this.lastNearPlaneX
                + nearPlaneY * this.lastNearPlaneY
                + nearPlaneZ * this.lastNearPlaneZ;
        dot = Math.max(-1.0, Math.min(1.0, dot));
        return Math.toDegrees(Math.acos(dot));
    }

    private void setFrustum(Viewport<?> viewport, long ptr, double camDx, double camDy, double camDz, double rotationDegrees) {
        float motionLen = (float) Math.sqrt(camDx * camDx + camDy * camDy + camDz * camDz);
        float invMotionLen = motionLen > 0.0001f ? 1.0f / motionLen : 0.0f;
        float rotationExpansion = 0.0f;
        if (ENABLE_ROTATION_FRUSTUM_EXPANSION && rotationDegrees >= REQUEST_ROTATION_EXPANSION_THRESHOLD_DEGREES) {
            rotationExpansion = Math.min(
                    REQUEST_ROTATION_EXPANSION_MAX_EXTRA,
                    (float) rotationDegrees * REQUEST_ROTATION_EXPANSION_DEGREES_SCALE
            );
        }
        this.lastRotationExpansionBlocks = rotationExpansion;
        for (int i = 0; i < 6; i++) {
            var plane = viewport.frustumPlanes[i];
            float nx = plane.x;
            float ny = plane.y;
            float nz = plane.z;
            float d = plane.w;

            float expansion = (i == 4) ? REQUEST_FRUSTUM_NEAR_EXPANSION_BLOCKS : REQUEST_FRUSTUM_EXPANSION_BLOCKS;
            if (ENABLE_DIRECTIONAL_FRUSTUM_EXPANSION && i != 4 && motionLen > 0.0001f) {
                float nLen = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (nLen > 0.0001f) {
                    // Preferentially expand planes facing camera motion direction.
                    float dot = (float) ((nx * camDx + ny * camDy + nz * camDz) * invMotionLen / nLen);
                    float directionalWeight = Math.max(0.0f, dot);
                    float directionalExtra = Math.min(
                            REQUEST_DIRECTIONAL_EXPANSION_MAX_EXTRA,
                            directionalWeight * motionLen * REQUEST_DIRECTIONAL_EXPANSION_MOTION_SCALE
                    );
                    expansion += directionalExtra;
                }
            }
            if (rotationExpansion > 0.0f && i != 4) {
                expansion += rotationExpansion;
            }
            if (expansion != 0.0f) {
                float nLen = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                d += expansion * nLen;
            }

            MemoryUtil.memPutFloat(ptr, nx); ptr += 4;
            MemoryUtil.memPutFloat(ptr, ny); ptr += 4;
            MemoryUtil.memPutFloat(ptr, nz); ptr += 4;
            MemoryUtil.memPutFloat(ptr, d); ptr += 4;
        }
    }

    private void uploadUniform(Viewport<?> viewport) {
        long ptr = UploadStream.INSTANCE.upload(this.uniformBuffer, 0, 1024);
        double camDx = 0.0;
        double camDy = 0.0;
        double camDz = 0.0;
        if (!Double.isNaN(this.lastCamX)) {
            camDx = viewport.cameraX - this.lastCamX;
            camDy = viewport.cameraY - this.lastCamY;
            camDz = viewport.cameraZ - this.lastCamZ;
        }
        double rotationDegrees = this.computeRotationDegrees(viewport);
        this.lastRotationDegrees = rotationDegrees;

        viewport.MVP.getToAddress(ptr); ptr += 4*4*4;

        viewport.section.getToAddress(ptr); ptr += 4*3;

        //MemoryUtil.memPutFloat(ptr, viewport.width); ptr += 4;
        MemoryUtil.memPutInt(ptr, viewport.hiZBuffer.getPackedLevels()); ptr += 4;

        viewport.innerTranslation.getToAddress(ptr); ptr += 4*3;

        //MemoryUtil.memPutFloat(ptr, viewport.height); ptr += 4;

        final float screenspaceAreaDecreasingSize = VoxyConfig.CONFIG.getSubDivisionSize()*VoxyConfig.CONFIG.getSubDivisionSize();
        //Screen space size for descending
        MemoryUtil.memPutFloat(ptr, (float) (screenspaceAreaDecreasingSize) /(viewport.width*viewport.height)); ptr += 4;

        this.setFrustum(viewport, ptr, camDx, camDy, camDz, rotationDegrees); ptr += 4*4*6;

        MemoryUtil.memPutInt(ptr, (int) (viewport.getRenderList().size()/4-1)); ptr += 4;

        //VisibilityId
        MemoryUtil.memPutInt(ptr, this.nodeCleaner.visibilityId); ptr += 4;

        {
            this.lastCamX = viewport.cameraX;
            this.lastCamY = viewport.cameraY;
            this.lastCamZ = viewport.cameraZ;
            this.lastNearPlaneX = viewport.frustumPlanes[4].x;
            this.lastNearPlaneY = viewport.frustumPlanes[4].y;
            this.lastNearPlaneZ = viewport.frustumPlanes[4].z;

            final double targetCount = 4000.0;
            double fillness = Math.max(0.0, (targetCount - this.meshGen.getTaskCount()) / targetCount);
            fillness = Math.pow(fillness, 2.0);
            int requestBudget = (int) Math.ceil(fillness * MAX_REQUEST_QUEUE_SIZE);
            this.lastRequestBudget = requestBudget;
            this.lastRequestBudgetSize = Math.max(0, Math.min(MAX_REQUEST_QUEUE_SIZE, requestBudget));
            MemoryUtil.memPutInt(ptr, this.lastRequestBudgetSize);ptr += 4;
        }

        MemoryUtil.memPutInt(ptr, this.nodeManager.getCurrentPhaseLevel()); ptr += 4;

        if (VoxyConfig.CONFIG.isCameraDistanceCullingEnabled()) {
            MemoryUtil.memPutFloat(ptr, RenderDistancePolicy.getTraversalDistanceSquaredBlocks());
        } else {
            // Disable traversal distance clipping when camera-distance culling is off.
            MemoryUtil.memPutFloat(ptr, -1.0f);
        }
        ptr += 4;
    }

    private void bindings(Viewport<?> viewport) {
        glBindBuffer(GL_DISPATCH_INDIRECT_BUFFER, this.queueMetaBuffer.id);

        //Bind the hiz buffer
        glBindTextureUnit(0, viewport.hiZBuffer.getHizTextureId());
        glBindSampler(0, this.hizSampler);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, RENDER_QUEUE_BINDING, viewport.getRenderList().id);
    }

    public void doTraversal(Viewport<?> viewport) {
        int traversalInterval = this.computeTraversalInterval(viewport);
        this.lastTraversalIntervalFrames = traversalInterval;
        this.traversalFrameCounter++;
        if (traversalInterval > 1 && (this.traversalFrameCounter % traversalInterval) != 0) {
            this.maybeLogPerf(viewport, false);
            return;
        }
        this.uploadUniform(viewport);
        //UploadStream.INSTANCE.commit(); //Done inside traversal

        this.traversal.bind();
        this.bindings(viewport);
        PrintfDebugUtil.bind();

        if (RenderStatistics.enabled) {
            this.statisticsBuffer.zero();
        }

        //Clear the render output counter
        nglClearNamedBufferSubData(viewport.getRenderList().id, GL_R32UI, 0, 4, GL_RED_INTEGER, GL_UNSIGNED_INT, 0);

        //Traverse
        this.traverseInternal();

        this.downloadResetRequestQueue();

        if (RenderStatistics.enabled) {
            DownloadStream.INSTANCE.download(this.statisticsBuffer, down->{
                for (int i = 0; i < MAX_ITERATIONS; i++) {
                    RenderStatistics.hierarchicalTraversalCounts[i] = MemoryUtil.memGetInt(down.address+i*4L);
                }

                for (int i = 0; i < MAX_ITERATIONS; i++) {
                    RenderStatistics.hierarchicalRenderSections[i] = MemoryUtil.memGetInt(down.address+MAX_ITERATIONS*4L+i*4L);
                }
            });
        }

        //Bind the hiz buffer
        glBindSampler(0, 0);
        glBindTextureUnit(0, 0);
        this.maybeLogPerf(viewport, true);
    }

    private int computeTraversalInterval(Viewport<?> viewport) {
        return 1;
    }

    private void traverseInternal() {
        {
            //Fix mesa bug
            glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
            glPixelStorei(GL_UNPACK_IMAGE_HEIGHT, 0);
            glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
            glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
            glPixelStorei(GL_UNPACK_SKIP_IMAGES, 0);
        }

        int firstDispatchSize = (this.topNodeCount+(1<<LOCAL_WORK_SIZE_BITS)-1)>>LOCAL_WORK_SIZE_BITS;
        /*
        //prime the queue Todo: maybe move after the traversal? cause then it is more efficient work since it doesnt need to wait for this before starting?
        glClearNamedBufferData(this.queueMetaBuffer.id, GL_RGBA32UI, GL_RGBA, GL_UNSIGNED_INT, new int[]{0,1,1,0});//Prime the metadata buffer, which also contains

        //Set the first entry
        glClearNamedBufferSubData(this.queueMetaBuffer.id, GL_RGBA32UI, 0, 16, GL_RGBA, GL_UNSIGNED_INT, new int[]{firstDispatchSize,1,1,initialQueueSize});
         */
        {//TODO:FIXME: THIS IS BULLSHIT BY INTEL need to fix the clearing
            long ptr = UploadStream.INSTANCE.upload(this.queueMetaBuffer, 0, 16*MAX_ITERATIONS);
            MemoryUtil.memPutInt(ptr +  0, firstDispatchSize);
            MemoryUtil.memPutInt(ptr +  4, 1);
            MemoryUtil.memPutInt(ptr +  8, 1);
            MemoryUtil.memPutInt(ptr + 12, this.topNodeCount);
            for (int i = 1; i < MAX_ITERATIONS; i++) {
                MemoryUtil.memPutInt(ptr + (i*16)+ 0, 0);
                MemoryUtil.memPutInt(ptr + (i*16)+ 4, 1);
                MemoryUtil.memPutInt(ptr + (i*16)+ 8, 1);
                MemoryUtil.memPutInt(ptr + (i*16)+12, 0);
            }
            UploadStream.INSTANCE.commit();
        }

        //Execute first iteration
        glUniform1ui(NODE_QUEUE_INDEX_BINDING, 0);

        //Use the top node id buffer
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, NODE_QUEUE_SOURCE_BINDING, this.topNodeIds.id);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, NODE_QUEUE_SINK_BINDING, this.scratchQueueB.id);

        //Dont need to use indirect to dispatch the first iteration
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_COMMAND_BARRIER_BIT|GL_BUFFER_UPDATE_BARRIER_BIT);
        if (firstDispatchSize!=0) {
            //for some reason amd driver loves spitting out errors when its 0 (even tho it should just ignore it afak) so we do it ourselves
            glDispatchCompute(firstDispatchSize, 1,1);
        }
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_COMMAND_BARRIER_BIT);

        //Dispatch max iterations
        for (int iter = 1; iter < MAX_ITERATIONS; iter++) {
            glUniform1ui(NODE_QUEUE_INDEX_BINDING, iter);

            //Flipflop buffers
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, NODE_QUEUE_SOURCE_BINDING, ((iter & 1) == 0 ? this.scratchQueueA : this.scratchQueueB).id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, NODE_QUEUE_SINK_BINDING, ((iter & 1) == 0 ? this.scratchQueueB : this.scratchQueueA).id);

            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_COMMAND_BARRIER_BIT);

            //Dispatch and barrier
            glDispatchComputeIndirect(iter * 4 * 4);
        }

        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_COMMAND_BARRIER_BIT);
    }


    private void downloadResetRequestQueue() {
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        DownloadStream.INSTANCE.download(this.requestBuffer, this::forwardDownloadResult);
        nglClearNamedBufferSubData(this.requestBuffer.id, GL_R32UI, 0, 4, GL_RED_INTEGER, GL_UNSIGNED_INT, 0);
    }

    private void forwardDownloadResult(long ptr, long size) {
        int count = MemoryUtil.memGetInt(ptr);ptr += 8;//its 8 since we need to skip the second value (which is empty)
        if (count < 0 || count > 50000) {
            Logger.error(new IllegalStateException("Count unexpected extreme value: " + count + " things may get weird"));
            return;
        }
        if (count > (this.requestBuffer.size()>>3)-1) {
            //This should not break the synchonization between gpu and cpu as in the traversal shader is
            // `if (atomRes < REQUEST_QUEUE_SIZE) {` which forcefully clamps to the request size

            //Logger.warn("Count over max buffer size, clamping, got count: " + count + ".");

            count = (int) ((this.requestBuffer.size()>>3)-1);

            //Write back the clamped count
            MemoryUtil.memPutInt(ptr-8, count);
        }
        //if (count > REQUEST_QUEUE_SIZE) {
        //    Logger.warn("Count larger than 'maxRequestCount', overflow captured. Overflowed by " + (count-REQUEST_QUEUE_SIZE));
        //}
        if (count != 0) {
            this.nodeManager.submitRequestBatch(new MemoryBuffer(count*8L+8).cpyFrom(ptr-8));// the -8 is because we incremented it by 8
        }
    }

    public GlBuffer getNodeBuffer() {
        return this.nodeBuffer;
    }

    public void free() {
        this.traversal.free();
        this.requestBuffer.free();
        this.nodeBuffer.free();
        this.uniformBuffer.free();
        this.statisticsBuffer.free();
        this.queueMetaBuffer.free();
        this.topNodeIds.free();
        this.scratchQueueA.free();
        this.scratchQueueB.free();
        glDeleteSamplers(this.hizSampler);
    }

    private void maybeLogPerf(Viewport<?> viewport, boolean traversedThisFrame) {
        boolean forceLog = this.lastRotationDegrees >= REQUEST_ROTATION_EXPANSION_THRESHOLD_DEGREES
                || this.lastRotationExpansionBlocks > 0.0f;
        if (!forceLog && ++this.perfLogFrameCounter < PERF_LOG_INTERVAL_FRAMES) {
            return;
        }
        this.perfLogFrameCounter = 0;

        Logger.info(
                "VOXY_PERF traversal",
                "interval_frames=" + this.lastTraversalIntervalFrames,
                "executed=" + traversedThisFrame,
                "request_budget=" + this.lastRequestBudgetSize,
                "phase=" + this.nodeManager.getCurrentPhaseLevel(),
                "top_node_count=" + this.topNodeCount,
                "mesh_queue=" + this.meshGen.getTaskCount(),
                "current_max_node_id=" + this.nodeManager.getCurrentMaxNodeId(),
                "camera_distance_culling=" + VoxyConfig.CONFIG.isCameraDistanceCullingEnabled(),
                "visibility_culling=" + VoxyConfig.CONFIG.isVisibilityCullingEnabled(),
                "rotation_degrees=" + String.format("%.3f", this.lastRotationDegrees),
                "rotation_expansion_blocks=" + String.format("%.2f", this.lastRotationExpansionBlocks),
                "viewport=" + viewport.width + "x" + viewport.height
        );
    }

    private static final long SCRATCH = MemoryUtil.nmemAlloc(32);//32 bytes of scratch memory
}
