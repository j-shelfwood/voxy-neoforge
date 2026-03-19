package me.cortex.voxy.client.core;

// MC 1.21.1: These classes are in .platform package (moved to .opengl in 1.21.8+)
import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.voxy.client.TimingStatistics;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.compat.IrisCompatManager;
import me.cortex.voxy.client.config.RenderDistancePolicy;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.iris.VoxyUniforms;
import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.model.ModelStore;
import me.cortex.voxy.client.core.rendering.ChunkBoundRenderer;
import me.cortex.voxy.client.core.rendering.RenderDistanceTracker;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.ViewportSelector;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.rendering.section.IUsesMeshlets;
import me.cortex.voxy.client.core.rendering.section.backend.AbstractSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicSectionGeometryData;
import me.cortex.voxy.client.core.rendering.section.geometry.IGeometryData;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.core.rendering.util.PrintfDebugUtil;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.client.core.util.GPUTiming;
// MC 1.21.1 NeoForge: Iris shader integration excluded
// import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.VoxyCommon;
    // TODO: FogParameters integration not wired on NeoForge 1.21.1 yet
    // import net.minecraft.client.renderer.FogParameters;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL11;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.lwjgl.opengl.GL11.GL_VIEWPORT;
import static org.lwjgl.opengl.GL11.glGetIntegerv;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL33.glBindSampler;
import static org.lwjgl.opengl.GL33C.GL_SAMPLER_BINDING;
import static org.lwjgl.opengl.GL31C.GL_UNIFORM_BUFFER;
import static org.lwjgl.opengl.GL31C.GL_UNIFORM_BUFFER_BINDING;
import static org.lwjgl.opengl.GL40C.GL_DRAW_INDIRECT_BUFFER;
import static org.lwjgl.opengl.GL40C.GL_DRAW_INDIRECT_BUFFER_BINDING;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER_BINDING;
import static org.lwjgl.opengl.ARBIndirectParameters.GL_PARAMETER_BUFFER_ARB;
import static org.lwjgl.opengl.ARBIndirectParameters.GL_PARAMETER_BUFFER_BINDING_ARB;
import static org.lwjgl.opengl.GL45C.glBindTextureUnit;

public class VoxyRenderSystem {
    private static final long SPARSE_GEOMETRY_MIN_BYTES = 1024L * 1024L * 1024L; // 1GB virtual address space floor for NVIDIA sparse buffers
    private static final boolean FORCE_GC_ON_RENDERER_CREATE =
            Boolean.getBoolean("voxy.forceGcOnRendererCreate");
    private static final boolean FORCE_SYNC_ON_RENDERER_CREATE =
            Boolean.getBoolean("voxy.syncOnRendererCreate");
    private static final boolean FORCE_SYNC_IN_FREX_WORK_LOOP =
            Boolean.getBoolean("voxy.syncInFrexWorkLoop");
    private static final boolean QUERY_VIEWPORT_EACH_FRAME =
            Boolean.getBoolean("voxy.queryViewportEachFrame");
    private static final int TRACKER_MAX_PASSES_PER_FRAME = 24;
    private static final long TRACKER_BUDGET_NS = 2_000_000L;
    private static final int MODEL_MAX_PASSES_PER_FRAME = 6;
    private static final long MODEL_BUDGET_NS_BASE =
            Long.getLong("voxy.modelBudgetBaseNs", 1_200_000L);
    private static final long MODEL_BUDGET_NS_MIN =
            Long.getLong("voxy.modelBudgetMinNs", 250_000L);
    private static final long MODEL_BUDGET_NS_MAX =
            Long.getLong("voxy.modelBudgetMaxNs", 2_000_000L);
    private static final long TARGET_FRAME_BUDGET_NS =
            Long.getLong("voxy.targetFrameBudgetNs", 16_666_667L);

    private static final boolean RENDER_LODS_IN_IRIS_SHADOW_PASS =
            System.getProperty("voxy.renderLodsInIrisShadowPass", "false").equalsIgnoreCase("true");
    private static final boolean ENABLE_IRIS_RENDERER_RECREATE =
            System.getProperty("voxy.enableIrisRendererRecreate", "false").equalsIgnoreCase("true");
    private static final long IRIS_RECREATE_MIN_INTERVAL_NANOS =
            Long.getLong("voxy.irisRecreateMinIntervalMs", 1500L) * 1_000_000L;
    private static final AtomicBoolean IRIS_RECREATE_QUEUED = new AtomicBoolean(false);
    private static volatile String pendingIrisRecreateReason = "unspecified";
    private static volatile long lastIrisRecreateNanos = 0L;

    private final WorldEngine worldIn;


    private final ModelBakerySubsystem modelService;
    private final RenderGenerationService renderGen;
    private final IGeometryData geometryData;
    private final AsyncNodeManager nodeManager;
    private final NodeCleaner nodeCleaner;
    private final HierarchicalOcclusionTraverser traversal;


    private final RenderDistanceTracker renderDistanceTracker;
    public final ChunkBoundRenderer chunkBoundRenderer;

    private final ViewportSelector<?> viewportSelector;

    private final AbstractRenderPipeline pipeline;
    private volatile boolean shuttingDown = false;

    public boolean isUsingIrisPipeline() {
        return this.pipeline instanceof IrisVoxyRenderPipeline;
    }

    public String getPipelineSimpleName() {
        return this.pipeline.getClass().getSimpleName();
    }

    public boolean isShuttingDown() {
        return this.shuttingDown;
    }

    public VoxyLoadingSnapshot getLoadingSnapshot() {
        int loadedSections = -1;
        if (this.pipeline instanceof AbstractRenderPipeline arp) {
            loadedSections = arp.getSectionCount();
        }
        int meshQueue = this.renderGen.getTaskCount();
        int modelQueue = this.modelService.getProcessingCount();
        boolean nodeWorkPending = meshQueue > 0 || modelQueue > 0;
        return new VoxyLoadingSnapshot(meshQueue, modelQueue, nodeWorkPending, Math.max(0, loadedSections), this.shuttingDown);
    }

    public static void scheduleRendererRecreate(String reason) {
        if (!ENABLE_IRIS_RENDERER_RECREATE) {
            return;
        }
        var mc = Minecraft.getInstance();
        if (mc == null || mc.levelRenderer == null) {
            return;
        }

        long now = System.nanoTime();
        long sinceLast = now - lastIrisRecreateNanos;
        if (sinceLast < IRIS_RECREATE_MIN_INTERVAL_NANOS) {
            return;
        }
        if (!IRIS_RECREATE_QUEUED.compareAndSet(false, true)) {
            return;
        }
        lastIrisRecreateNanos = now;
        pendingIrisRecreateReason = reason;

        Logger.info("[VoxyRecreate] Queued renderer recreate; reason='" + reason + "'");
    }

    public static boolean isIrisRendererRecreateEnabled() {
        return ENABLE_IRIS_RENDERER_RECREATE;
    }

    public static boolean applyScheduledRendererRecreate(IGetVoxyRenderSystem getter, String source) {
        if (!ENABLE_IRIS_RENDERER_RECREATE) {
            IRIS_RECREATE_QUEUED.set(false);
            pendingIrisRecreateReason = "unspecified";
            return false;
        }
        if (!IRIS_RECREATE_QUEUED.get()) {
            return false;
        }
        // Never recreate during Iris shadow rendering; Iris may still use shadow targets in-flight.
        if (IrisCompatManager.isShadowActive()) {
            return false;
        }
        if (getter == null) {
            IRIS_RECREATE_QUEUED.set(false);
            pendingIrisRecreateReason = "unspecified";
            return false;
        }
        try {
            String reason = pendingIrisRecreateReason;
            var before = getter.getVoxyRenderSystem();
            String beforePipeline = before == null ? "none" : before.getPipelineSimpleName();
            // Snapshot the built-section mask before shutdown so the new renderer can inherit it.
            // Without this, the new ChunkBoundRenderer starts empty and LODs render over clouds/Iris
            // geometry until Embeddium re-fires section-built events (which can take many seconds).
            var oldChunkBoundRenderer = before != null ? before.chunkBoundRenderer : null;
            Logger.info("[VoxyRecreate] Applying renderer recreate at frame boundary; source='" + source + "' reason='" + reason + "' before=" + beforePipeline);
            VoxyUniforms.resetTemporalState("renderer_recreate:" + reason);
            getter.shutdownRenderer();
            var mc = Minecraft.getInstance();
            if (mc.level != null) {
                getter.createRenderer();
            }
            var after = getter.getVoxyRenderSystem();
            // Replay the old section mask into the new renderer immediately so LODs are correctly
            // depth-masked from the first frame after recreate (fixes cloud/shader-pass z-fighting).
            if (after != null && oldChunkBoundRenderer != null) {
                after.chunkBoundRenderer.replayFrom(oldChunkBoundRenderer);
            }
            String afterPipeline = after == null ? "none" : after.getPipelineSimpleName();
            Logger.info("[VoxyRecreate] Renderer recreate complete; before=" + beforePipeline + " after=" + afterPipeline);
        } catch (Throwable t) {
            Logger.error("[VoxyRecreate] Renderer recreate failed", t);
        } finally {
            IRIS_RECREATE_QUEUED.set(false);
            pendingIrisRecreateReason = "unspecified";
        }
        return true;
    }

    private static AbstractSectionRenderer.Factory<?,? extends IGeometryData> getRenderBackendFactory() {
        //TODO: need todo a thing where selects optimal section render based on if supports the pipeline and geometry data type
        return MDICSectionRenderer.FACTORY;
    }

    public VoxyRenderSystem(WorldEngine world, ServiceManager sm) {
        //Keep the world loaded, NOTE: this is done FIRST, to keep and ensure that even if the rest of loading takes more
        // than timeout, we keep the world acquired
        world.acquireRef();
        if (FORCE_GC_ON_RENDERER_CREATE) {
            System.gc();
        }

        if (Minecraft.getInstance().options.getEffectiveRenderDistance()<3) {
            Logger.warn("Having a vanilla render distance of 2 can cause rare culling near the edge of your screen issues, please use 3 or more");
        }

        //Fking HATE EVERYTHING AAAAAAAAAAAAAAAA
        int[] oldBufferBindings = new int[16];
        for (int i = 0; i < oldBufferBindings.length; i++) {
            oldBufferBindings[i] = glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, i);
        }

        try {
            // Optional hard sync path for debugging driver/lifetime issues.
            if (FORCE_SYNC_ON_RENDERER_CREATE) {
                glFinish();
                glFinish();
            }

            this.worldIn = world;

            long geometryCapacity = getGeometryBufferSize();
            var backendFactory = getRenderBackendFactory();

            {
                this.modelService = new ModelBakerySubsystem(world.getMapper());
                this.renderGen = new RenderGenerationService(world, this.modelService, sm, IUsesMeshlets.class.isAssignableFrom(backendFactory.clz()));

                this.geometryData = new BasicSectionGeometryData(1 << 20, geometryCapacity);

                this.nodeManager = new AsyncNodeManager(1 << 21, this.geometryData, this.renderGen);
                this.nodeCleaner = new NodeCleaner(this.nodeManager);
                this.traversal = new HierarchicalOcclusionTraverser(this.nodeManager, this.nodeCleaner, this.renderGen);

                world.setDirtyCallback(this.nodeManager::worldEvent);

                Arrays.stream(world.getMapper().getBiomeEntries()).forEach(this.modelService::addBiome);
                world.getMapper().setBiomeCallback(this.modelService::addBiome);

                this.nodeManager.start();
            }

            this.pipeline = RenderPipelineFactory.createPipeline(this.nodeManager, this.nodeCleaner, this.traversal, this::frexStillHasWork);
            this.pipeline.setupExtraModelBakeryData(this.modelService);//Configure the model service
            var sectionRenderer = backendFactory.create(this.pipeline, this.modelService.getStore(), this.geometryData);
            this.pipeline.setSectionRenderer(sectionRenderer);
            this.viewportSelector = new ViewportSelector<>(sectionRenderer::createViewport);

            {
                // MC 1.21.1: Use getMinSection()/getMaxSection() instead of getMinSectionY()/getMaxSectionY()
                var level = (net.minecraft.world.level.Level)Minecraft.getInstance().level;
                int minSec = level.getMinSection() >> 5;
                int maxSec = (level.getMaxSection() - 1) >> 5;

                //Do some very cheeky stuff for MiB
                if (VoxyCommon.IS_MINE_IN_ABYSS) {//TODO: make this somehow configurable
                    minSec = -8;
                    maxSec = 7;
                }

                int trackerOpsPerFrame = Math.max(1,
                        Integer.getInteger("voxy.renderDistanceTrackerOpsPerFrame", 64));
                this.renderDistanceTracker = new RenderDistanceTracker(trackerOpsPerFrame,
                        minSec,
                        maxSec,
                        this.nodeManager::addTopLevel,
                        this.nodeManager::removeTopLevel);

                this.setRenderDistance(VoxyConfig.CONFIG.getSectionRenderDistance());
            }

            this.chunkBoundRenderer = new ChunkBoundRenderer(this.pipeline);

            Logger.info("Voxy render system created with " + geometryCapacity + " geometry capacity, using pipeline '" + this.pipeline.getClass().getSimpleName() + "' with renderer '" + sectionRenderer.getClass().getSimpleName() + "'");
        } catch (RuntimeException e) {
            world.releaseRef();//If something goes wrong, we must release the world first
            throw e;
        }

        for (int i = 0; i < oldBufferBindings.length; i++) {
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, i, oldBufferBindings[i]);
        }

        for (int i = 0; i < 16; i++) {
            GlStateManager._activeTexture(GlConst.GL_TEXTURE0+i);
            GlStateManager._bindTexture(0);
            glBindSampler(i, 0);
        }
    }


    // Embeddium compatibility: FogParameters integration not wired
    public Viewport<?> setupViewport(Matrix4fc projection, Matrix4fc modelView, double cameraX, double cameraY, double cameraZ) {
        if (this.shuttingDown) {
            return null;
        }
        var viewport = this.getViewport();
        if (viewport == null) {
            return null;
        }

        //Do some very cheeky stuff for MiB
        if (VoxyCommon.IS_MINE_IN_ABYSS) {
            int sector = (((int)Math.floor(cameraX)>>4)+512)>>10;
            cameraX -= sector<<14;//10+4
            cameraY += (16+(256-32-sector*30))*16;
        }

        //cameraY += 100;
        var projectionMat = computeProjectionMat(projection);//RenderSystem.getProjectionMatrix();
        //var projection = ShadowMatrices.createOrthoMatrix(160, -16*300, 16*300);
        //var projection = new Matrix4f(matrices.projection());

        // Query GL state once here; results are cached so renderOpaque() can skip the GL round-trips.
        this.cachedFramebufferId = GL11.glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        if (QUERY_VIEWPORT_EACH_FRAME || IrisCompatManager.isShaderPackEnabled()) {
            glGetIntegerv(GL_VIEWPORT, this.viewportDimsScratch);
            this.cachedViewportX = this.viewportDimsScratch[0];
            this.cachedViewportY = this.viewportDimsScratch[1];
            this.cachedViewportW = this.viewportDimsScratch[2];
            this.cachedViewportH = this.viewportDimsScratch[3];
        } else {
            var target = Minecraft.getInstance().getMainRenderTarget();
            this.cachedViewportX = 0;
            this.cachedViewportY = 0;
            this.cachedViewportW = target.width;
            this.cachedViewportH = target.height;
        }

        int width = this.cachedViewportW;
        int height = this.cachedViewportH;

        if ((width == 0 || height == 0) && setupViewportWarnCount++ < 3) {
            Logger.warn("[DIAG] setupViewport: viewport returned 0x0 (x=" + this.cachedViewportX + ", y=" + this.cachedViewportY + ", w=" + width + ", h=" + height + ") - LODs will not render this frame");
        }

        {//Apply render scaling factor
            var factor = this.pipeline.getRenderScalingFactor();
            if (factor != null) {
                width = (int) (width*factor[0]);
                height = (int) (height*factor[1]);
            }
        }

        viewport
                .setVanillaProjection(projection)
                .setProjection(projectionMat)
                .setModelView(new Matrix4f(modelView))
                .setCamera(cameraX, cameraY, cameraZ)
                .setScreenSize(width, height)
                // Disabled for Embeddium compatibility - FogParameters not wired
                // .setFogParameters(fogParameters)
                .update();

        if (VoxyClient.getOcclusionDebugState()==0) {
            viewport.frameId++;
        }

        return viewport;
    }

    private record ViewportSnapshot(
            int width,
            int height,
            int frameId,
            Matrix4f vanillaProjection,
            Matrix4f projection,
            Matrix4f modelView,
            double cameraX,
            double cameraY,
            double cameraZ) {
    }

    private static ViewportSnapshot snapshotViewport(Viewport<?> viewport) {
        return new ViewportSnapshot(
                viewport.width,
                viewport.height,
                viewport.frameId,
                new Matrix4f(viewport.vanillaProjection),
                new Matrix4f(viewport.projection),
                new Matrix4f(viewport.modelView),
                viewport.cameraX,
                viewport.cameraY,
                viewport.cameraZ
        );
    }

    private static void restoreViewport(Viewport<?> viewport, ViewportSnapshot snapshot) {
        viewport
                .setVanillaProjection(snapshot.vanillaProjection)
                .setProjection(new Matrix4f(snapshot.projection))
                .setModelView(new Matrix4f(snapshot.modelView))
                .setCamera(snapshot.cameraX, snapshot.cameraY, snapshot.cameraZ)
                .setScreenSize(snapshot.width, snapshot.height);
        viewport.frameId = snapshot.frameId;
        viewport.update(snapshot.width > 0 && snapshot.height > 0);
    }

    public void renderShadowPass(Matrix4fc projection, Matrix4fc modelView, double cameraX, double cameraY, double cameraZ) {
        if (this.shuttingDown) {
            return;
        }
        var viewport = this.viewportSelector.getViewport();
        if (viewport == null) {
            return;
        }

        //Do some very cheeky stuff for MiB
        if (VoxyCommon.IS_MINE_IN_ABYSS) {
            int sector = (((int)Math.floor(cameraX)>>4)+512)>>10;
            cameraX -= sector<<14;//10+4
            cameraY += (16+(256-32-sector*30))*16;
        }

        var snapshot = snapshotViewport(viewport);
        try {
            // Do not resize per-viewport buffers during Iris shadow rendering: Iris swaps FBOs and viewports,
            // and our depthBoundingBuffer isn't needed for shadow rendering.
            viewport
                    .setVanillaProjection(projection)
                    .setProjection(new Matrix4f(projection))
                    .setModelView(new Matrix4f(modelView))
                    .setCamera(cameraX, cameraY, cameraZ)
                    .setScreenSize(snapshot.width, snapshot.height)
                    .update(false);

            this.renderShadow(viewport);
        } finally {
            restoreViewport(viewport, snapshot);
        }
    }

    public void renderShadow(Viewport<?> viewport) {
        if (this.shuttingDown) {
            return;
        }
        if (viewport == null) {
            return;
        }

        var sectionRenderer = (AbstractSectionRenderer) this.pipeline.sectionRenderer;

        int oldProgram = glGetInteger(GL_CURRENT_PROGRAM);
        int oldVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        int oldElementArray = glGetInteger(GL_ELEMENT_ARRAY_BUFFER_BINDING);
        int oldDrawIndirect = glGetInteger(GL_DRAW_INDIRECT_BUFFER_BINDING);
        int oldParameterBuffer = glGetInteger(GL_PARAMETER_BUFFER_BINDING_ARB);

        boolean oldDepthTest = glIsEnabled(GL_DEPTH_TEST);
        boolean oldCullFace = glIsEnabled(GL_CULL_FACE);
        boolean oldBlend = glIsEnabled(GL_BLEND);

        int oldTex0 = glGetIntegeri(GL_TEXTURE_BINDING_2D, 0);
        int oldSampler0 = glGetIntegeri(GL_SAMPLER_BINDING, 0);

        // Avoid glGetIntegeri for SSBO/UBO bindings — synchronous GPU stall.
        // Shadow rendering is called every Iris shadow pass; saving 16+ round-trips matters.
        // Iris manages its own GL state before/after shadow rendering, so zeroing SSBOs
        // and UBO slot 0 after our shadow pass is safe.

        try {
            sectionRenderer.renderShadow(viewport);
        } finally {
            glUseProgram(oldProgram);
            glBindVertexArray(oldVao);
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, oldElementArray);
            glBindBuffer(GL_DRAW_INDIRECT_BUFFER, oldDrawIndirect);
            glBindBuffer(GL_PARAMETER_BUFFER_ARB, oldParameterBuffer);

            glBindTextureUnit(0, oldTex0);
            glBindSampler(0, oldSampler0);

            glBindBufferBase(GL_UNIFORM_BUFFER, 0, 0);
            for (int i = 0; i < 16; i++) {
                glBindBufferBase(GL_SHADER_STORAGE_BUFFER, i, 0);
            }

            if (oldDepthTest) glEnable(GL_DEPTH_TEST); else glDisable(GL_DEPTH_TEST);
            if (oldCullFace) glEnable(GL_CULL_FACE); else glDisable(GL_CULL_FACE);
            if (oldBlend) glEnable(GL_BLEND); else glDisable(GL_BLEND);
        }
    }

    // Cached GL state from setupViewport() so renderOpaque() avoids synchronous GL queries.
    private int cachedFramebufferId = 0;
    private int cachedViewportX = 0, cachedViewportY = 0, cachedViewportW = 0, cachedViewportH = 0;
    private final int[] viewportDimsScratch = new int[4];

    private boolean renderOpaqueFirstCall = true;
    private int renderOpaqueNullViewportWarmupFrames = 8;
    private int setupViewportWarnCount = 0;
    private int renderOpaqueFrameCount = 0;
    private int lastLoggedSectionCount = -1;
    private long lastDynamicModelBudgetNs = MODEL_BUDGET_NS_BASE;
    private int scissorStateWarnCount = 0;

    public void renderOpaque(Viewport<?> viewport) {
        var mc = Minecraft.getInstance();
        IGetVoxyRenderSystem getter = null;
        if (mc != null && mc.levelRenderer instanceof IGetVoxyRenderSystem levelRendererGetter) {
            getter = levelRendererGetter;
        }
        if (applyScheduledRendererRecreate(getter, "renderOpaque")) {
            return;
        }
        if (this.shouldSkipOpaquePass(viewport)) return;
        this.logOpaqueDiagnostics(viewport);

        // MC 1.21.1 NeoForge: Fog is handled by VoxyClientEvents.onRenderFog()
        // which listens to ViewportEvent.RenderFog and pushes fog to infinity
        // BEFORE terrain renders. This ensures no fog wall at vanilla render distance.

        TimingStatistics.resetSamplers();
        long startTime = this.beginOpaqueFrame();

        // We do NOT query existing SSBO bindings here — glGetIntegeri is a synchronous
        // GPU→CPU round-trip (expensive stall). Vanilla Minecraft and Embeddium never use
        // SSBO slots 0-15 for terrain rendering, so it is safe to zero them on exit
        // rather than saving/restoring. If a future mod conflict arises, restore this.
        // Was: int[] oldBufferBindings = new int[10]; glGetIntegeri(...) × 10 per frame.


        // Use cached GL state from setupViewport() — avoids two synchronous GPU→CPU round-trips per frame.
        // cachedFramebufferId / cachedViewport* are populated in setupViewport() which is called just before.
        int oldFB = this.cachedFramebufferId;
        int boundFB = oldFB;

        //var target = DefaultTerrainRenderPasses.CUTOUT.getTarget();
        //boundFB = ((net.minecraft.client.texture.GlTexture) target.getColorAttachment()).getOrCreateFramebuffer(((GlBackend) RenderSystem.getDevice()).getFramebufferManager(), target.getDepthAttachment());
        if (boundFB == 0) {
            // Default framebuffer bound — Iris or MC hasn't set up its FBO yet this frame.
            // This is a transient condition during world join / shader reload; skip silently.
            return;
        }
        OpaqueGlStateSnapshot glState = OpaqueGlStateSnapshot.capture();
        if (glState.scissorEnabled() && this.scissorStateWarnCount++ < 3) {
            Logger.warn("[DIAG] renderOpaque entry with GL_SCISSOR_TEST enabled: box="
                    + glState.scissorX() + "," + glState.scissorY() + ","
                    + glState.scissorW() + "x" + glState.scissorH()
                    + " pipeline=" + this.pipeline.getClass().getSimpleName());
        }
        // Voxy expects full-viewport rasterization into its internal targets.
        glDisable(GL_SCISSOR_TEST);
        glViewport(0,0, viewport.width, viewport.height);

        //this.autoBalanceSubDivSize();
        try {
            this.pipeline.preSetup(viewport);
            this.runChunkBoundPass(viewport);


            GPUTiming.INSTANCE.marker();
            //The entire rendering pipeline (excluding the chunkbound thing)
            this.pipeline.runPipeline(viewport, boundFB, this.cachedViewportW, this.cachedViewportH);
            GPUTiming.INSTANCE.marker();

            this.runPostDynamicWork(viewport, startTime, oldFB);
            GPUTiming.INSTANCE.marker();
            TimingStatistics.postDynamic.stop();

            GPUTiming.INSTANCE.tick();
        } finally {
            this.restoreOpaqueGlState(oldFB, glState);
            TimingStatistics.all.stop();
        }

        //TimingStatistics.I.start();
        //glFlush();
        //TimingStatistics.I.stop();

        /*
        TimingStatistics.F.start();
        this.postProcessing.setup(viewport.width, viewport.height, boundFB);
        TimingStatistics.F.stop();

        this.renderer.renderFarAwayOpaque(viewport, this.chunkBoundRenderer.getDepthBoundTexture());


        TimingStatistics.F.start();
        //Compute the SSAO of the rendered terrain, TODO: fix it breaking depth or breaking _something_ am not sure what
        this.postProcessing.computeSSAO(viewport.MVP);
        TimingStatistics.F.stop();

        TimingStatistics.G.start();
        //We can render the translucent directly after as it is the furthest translucent objects
        this.renderer.renderFarAwayTranslucent(viewport, this.chunkBoundRenderer.getDepthBoundTexture());
        TimingStatistics.G.stop();


        TimingStatistics.F.start();
        this.postProcessing.renderPost(viewport, matrices.projection(), boundFB);
        TimingStatistics.F.stop();
         */
    }

    private boolean shouldSkipOpaquePass(Viewport<?> viewport) {
        if (this.shuttingDown || IRIS_RECREATE_QUEUED.get()) return true;
        if (viewport == null) {
            // Renderer/pipeline rebuild windows can produce a few frames without a valid viewport.
            // Suppress diagnostics in this short warmup period to avoid false-positive noise.
            if (this.renderOpaqueNullViewportWarmupFrames > 0) {
                this.renderOpaqueNullViewportWarmupFrames--;
                return true;
            }
            if (renderOpaqueFirstCall) {
                renderOpaqueFirstCall = false;
                Logger.warn("[DIAG] renderOpaque called with null viewport - rendering suppressed");
            }
            return true;
        }
        this.renderOpaqueNullViewportWarmupFrames = 0;
        // Only skip the opaque pass when shadow is active AND we are using the IrisVoxyRenderPipeline,
        // where we intentionally suppress the main pass during Iris shadow rendering.
        // For NormalRenderPipeline, Embeddium's CUTOUT hook is the sole render entry point;
        // blocking it here causes LODs to be completely invisible when Iris is loaded but
        // the shader pack is not instrumented for Voxy (no voxy.json).
        if (IrisCompatManager.isShadowActive() && !RENDER_LODS_IN_IRIS_SHADOW_PASS) {
            if (this.pipeline instanceof IrisVoxyRenderPipeline) {
                return true; // Skip Voxy main pass while Iris shadow pass is active.
            }
            // NormalRenderPipeline has no dedicated shadow path — Embeddium CUTOUT hook is the
            // only render entry point. Log once so we can confirm this code path is reached.
            if (renderOpaqueFirstCall) {
                me.cortex.voxy.common.Logger.info("[DIAG] renderOpaque: shadow active with NormalRenderPipeline — allowing LOD render (no dedicated shadow path)");
            }
        }
        return false;
    }

    private void logOpaqueDiagnostics(Viewport<?> viewport) {
        if (renderOpaqueFirstCall) {
            renderOpaqueFirstCall = false;
            Logger.info("[DIAG] renderOpaque first call: viewport=" + viewport.width + "x" + viewport.height
                    + " GL_VIEWPORT=" + this.cachedViewportW + "x" + this.cachedViewportH
                    + " boundFB=" + this.cachedFramebufferId
                    + " shadowActive=" + IrisCompatManager.isShadowActive()
                    + " pipeline=" + this.pipeline.getClass().getSimpleName());
        }

        // Periodic diagnostic: log section count + pipeline route every ~10s (600 frames)
        renderOpaqueFrameCount++;
        if (renderOpaqueFrameCount == 1 || renderOpaqueFrameCount % 600 == 0) {
            int sc = (this.pipeline instanceof AbstractRenderPipeline arp)
                    ? arp.getSectionCount() : -1;
            if (sc != lastLoggedSectionCount || renderOpaqueFrameCount == 1) {
                lastLoggedSectionCount = sc;
                Logger.info("[VoxyDiag] frame=" + renderOpaqueFrameCount
                        + " pipeline=" + this.pipeline.getClass().getSimpleName()
                        + " sectionCount=" + sc
                        + " meshQueue=" + this.renderGen.getTaskCount()
                        + " meshRetries=" + RenderGenerationService.MESH_RETRY_COUNTER.get()
                        + " modelQueue=" + this.modelService.getProcessingCount()
                        + " modelBudgetNs=" + this.lastDynamicModelBudgetNs
                        + " cam=(" + String.format("%.0f,%.0f,%.0f", viewport.cameraX, viewport.cameraY, viewport.cameraZ) + ")"
                        + " viewport=" + viewport.width + "x" + viewport.height
                        + " fb=" + this.cachedFramebufferId
                        + " shadowActive=" + IrisCompatManager.isShadowActive());
            }
        }
    }

    private long beginOpaqueFrame() {
        long startTime = System.nanoTime();
        TimingStatistics.all.start();
        GPUTiming.INSTANCE.marker();
        TimingStatistics.main.start();
        return startTime;
    }

    private void runChunkBoundPass(Viewport<?> viewport) {
        TimingStatistics.E.start();
        if ((!VoxyClient.disableEmbeddiumChunkRender()) && !IrisCompatManager.isShadowActive()) {
            try {
                this.chunkBoundRenderer.render(viewport);
            } catch (IllegalStateException e) {
                // During mid-frame teardown/recreate a stale chunk-bound pass can race with freed GL objects.
                // Skip this pass for the current frame instead of hard-crashing the client.
                if (e.getMessage() != null && e.getMessage().contains("should not be free")) {
                    Logger.warn("[VoxyRecreate] ChunkBoundRenderer render skipped due to freed GL object");
                    viewport.depthBoundingBuffer.clear(0);
                } else {
                    throw e;
                }
            }
        } else {
            viewport.depthBoundingBuffer.clear(0);
        }
        TimingStatistics.E.stop();
    }

    private void runPostDynamicWork(Viewport<?> viewport, long startTime, int oldFB) {
        TimingStatistics.main.stop();
        TimingStatistics.postDynamic.start();
        PrintfDebugUtil.tick();

        if (this.shuttingDown || IRIS_RECREATE_QUEUED.get()) {
            return;
        }

        UploadStream.INSTANCE.tick();

        long trackerStart = System.nanoTime();
        for (int pass = 0; pass < TRACKER_MAX_PASSES_PER_FRAME; pass++) {
            if (System.nanoTime() - trackerStart >= TRACKER_BUDGET_NS) {
                break;
            }
            if (!this.renderDistanceTracker.setCenterAndProcess(viewport.cameraX, viewport.cameraZ)) {
                break;
            }
        }
        TimingStatistics.H.start();
        int modelQueueCount = this.modelService.getProcessingCount();
        long elapsedBeforeModelNs = System.nanoTime() - startTime;
        long dynamicModelBudgetNs = this.computeDynamicModelBudgetNs(elapsedBeforeModelNs, modelQueueCount);
        this.lastDynamicModelBudgetNs = dynamicModelBudgetNs;

        long modelStart = System.nanoTime();
        for (int pass = 0; pass < MODEL_MAX_PASSES_PER_FRAME && !this.modelService.areQueuesEmpty(); pass++) {
            if (System.nanoTime() - modelStart >= dynamicModelBudgetNs) {
                break;
            }
            this.modelService.tick(Math.min(900_000L, dynamicModelBudgetNs), oldFB);
        }
        TimingStatistics.H.stop();
    }

    private long computeDynamicModelBudgetNs(long elapsedBeforeModelNs, int modelQueueCount) {
        long slackNs = Math.max(0L, TARGET_FRAME_BUDGET_NS - elapsedBeforeModelNs);
        long dynamicModelBudgetNs;
        if (elapsedBeforeModelNs >= TARGET_FRAME_BUDGET_NS) {
            dynamicModelBudgetNs = MODEL_BUDGET_NS_MIN;
        } else {
            long slackLimited = Math.min(slackNs / 2, MODEL_BUDGET_NS_BASE + (slackNs / 6));
            dynamicModelBudgetNs = Math.max(MODEL_BUDGET_NS_MIN, Math.min(MODEL_BUDGET_NS_MAX, slackLimited));
        }
        // Bias toward throughput while there is a large backlog, but never exceed max.
        if (modelQueueCount > 600) {
            dynamicModelBudgetNs = Math.min(MODEL_BUDGET_NS_MAX, dynamicModelBudgetNs + 450_000L);
        } else if (modelQueueCount > 200) {
            dynamicModelBudgetNs = Math.min(MODEL_BUDGET_NS_MAX, dynamicModelBudgetNs + 250_000L);
        }
        // If mesh generation pressure is high, reserve more frame time for terrain generation.
        if (this.renderGen.getTaskCount() > 2000) {
            dynamicModelBudgetNs = Math.max(MODEL_BUDGET_NS_MIN, dynamicModelBudgetNs / 2);
        }
        return dynamicModelBudgetNs;
    }

    private void restoreOpaqueGlState(int oldFB, OpaqueGlStateSnapshot glState) {
        glBindFramebuffer(GlConst.GL_FRAMEBUFFER, oldFB);
        glViewport(this.cachedViewportX, this.cachedViewportY, this.cachedViewportW, this.cachedViewportH);

        //Reset state manager stuffs
        glUseProgram(0);
        glEnable(GL_DEPTH_TEST);
        glDisable(GL_STENCIL_TEST);

        GlStateManager._glBindVertexArray(0);//Clear binding

        GlStateManager._activeTexture(GlConst.GL_TEXTURE1);
        for (int i = 0; i < 16; i++) {
            GlStateManager._activeTexture(GlConst.GL_TEXTURE0+i);
            GlStateManager._bindTexture(0);
            glBindSampler(i, 0);
        }
        GlStateManager._activeTexture(GlConst.GL_TEXTURE0);

        IrisCompatManager.clearSamplers();

        for (int i = 0; i < 16; i++) {
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, i, 0);
        }
        glState.restore();
    }

    private record OpaqueGlStateSnapshot(
            boolean scissorEnabled,
            int scissorX,
            int scissorY,
            int scissorW,
            int scissorH,
            boolean depthWriteMask
    ) {
        private static OpaqueGlStateSnapshot capture() {
            int[] box = new int[4];
            glGetIntegerv(GL_SCISSOR_BOX, box);
            return new OpaqueGlStateSnapshot(
                    glIsEnabled(GL_SCISSOR_TEST),
                    box[0], box[1], box[2], box[3],
                    glGetBoolean(GL_DEPTH_WRITEMASK)
            );
        }

        private void restore() {
            if (this.scissorEnabled) {
                glEnable(GL_SCISSOR_TEST);
                glScissor(this.scissorX, this.scissorY, this.scissorW, this.scissorH);
            } else {
                glDisable(GL_SCISSOR_TEST);
            }
            glDepthMask(this.depthWriteMask);
        }
    }



    private void autoBalanceSubDivSize() {
        //only increase quality while there are very few mesh queues, this stops,
        // e.g. while flying and is rendering alot of low quality chunks
        boolean canDecreaseSize = this.renderGen.getTaskCount() < 300;
        int MIN_FPS = 55;
        int MAX_FPS = 65;
        float INCREASE_PER_SECOND = 60;
        float DECREASE_PER_SECOND = 30;
        //Auto fps targeting
        if (Minecraft.getInstance().getFps() < MIN_FPS) {
            VoxyConfig.CONFIG.setSubDivisionSize(Math.min(VoxyConfig.CONFIG.getSubDivisionSize() + INCREASE_PER_SECOND / Math.max(1f, Minecraft.getInstance().getFps()), 256));
        }

        if (MAX_FPS < Minecraft.getInstance().getFps() && canDecreaseSize) {
            VoxyConfig.CONFIG.setSubDivisionSize(Math.max(VoxyConfig.CONFIG.getSubDivisionSize() - DECREASE_PER_SECOND / Math.max(1f, Minecraft.getInstance().getFps()), 28));
        }
    }

    private static float extractFovFromProjection(Matrix4fc projection) {
        float m11 = projection.m11();
        if (m11 > 0.001f) {
            return (float) (2.0 * Math.atan(1.0 / m11));
        }
        float fovDeg = Minecraft.getInstance().options.fov().get().floatValue();
        return fovDeg * 0.01745329238474369f;
    }

    private static Matrix4f makeProjectionMatrix(Matrix4fc baseProjection, float near, float far) {
        var projection = new Matrix4f();
        var client = Minecraft.getInstance();
        float fovY = extractFovFromProjection(baseProjection);
        projection.setPerspective(fovY,
                (float) client.getWindow().getWidth() / (float)client.getWindow().getHeight(),
                near, far);
        return projection;
    }

    //TODO: Make a reverse z buffer
    private static Matrix4f computeProjectionMat(Matrix4fc base) {
        //THis is a wild and insane problem to have
        // at short render distances the vanilla terrain doesnt end up covering the 16f near plane voxy uses
        // meaning that it explodes (due to near plane clipping).. _badly_ with the rastered culling being wrong in rare cases for the immediate
        // sections rendered after the vanilla render distance
        float nearVoxy = 16.0f;
        float farVoxy = 16 * 3000;

        return base.mulLocal(
                makeProjectionMatrix(base, 0.05f, Minecraft.getInstance().gameRenderer.getDepthFar()).invert(),
                new Matrix4f()
        ).mulLocal(makeProjectionMatrix(base, nearVoxy, farVoxy));
    }

    private boolean frexStillHasWork() {
        if (!VoxyClient.isFrexActive() || this.shuttingDown || IRIS_RECREATE_QUEUED.get()) {
            return false;
        }
        //If frex is running we must tick everything to ensure correctness
        UploadStream.INSTANCE.tick();
        //Done here as is allows less gl state resetup
        this.modelService.tick(100_000_000L, -1);
        if (FORCE_SYNC_IN_FREX_WORK_LOOP) {
            GL11.glFinish();
        }
        return this.nodeManager.hasWork() || this.renderGen.getTaskCount()!=0 || !this.modelService.areQueuesEmpty();
    }

    public void setRenderDistance(int renderDistance) {
        this.renderDistanceTracker.setRenderDistance(RenderDistancePolicy.getEffectiveSectionRenderDistance(renderDistance));
    }

    public Viewport<?> getViewport() {
        if (IrisCompatManager.isShadowActive()) {
            return null;
        }
        return this.viewportSelector.getViewport();
    }

    public void addDebugInfo(List<String> debug) {
        var mc = Minecraft.getInstance();
        String dim = (mc.level == null) ? "none" : mc.level.dimension().location().toString();
        boolean shaderPackEnabled = IrisCompatManager.isShaderPackEnabled();
        boolean shadowActive = IrisCompatManager.isShadowActive();
        debug.add("Pipeline: " + this.getPipelineSimpleName()
                + " route=" + (this.isUsingIrisPipeline() ? "IrisVoxy" : "Normal")
                + " shaderPack=" + shaderPackEnabled
                + " shadow=" + shadowActive
                + " dim=" + dim);
        debug.add("Buf/Tex [#/Mb]: [" + GlBuffer.getCount() + "/" + (GlBuffer.getTotalSize()/1_000_000) + "],[" + GlTexture.getCount() + "/" + (GlTexture.getEstimatedTotalSize()/1_000_000)+"]");
        {
            this.modelService.addDebugData(debug);
            this.renderGen.addDebugData(debug);
            this.nodeManager.addDebug(debug);
            this.pipeline.addDebug(debug);
        }
        {
            TimingStatistics.update();
            debug.add("Voxy frame runtime (millis): " + TimingStatistics.dynamic.pVal() + ", " + TimingStatistics.main.pVal()+ ", " + TimingStatistics.postDynamic.pVal()+ ", " + TimingStatistics.all.pVal());
            debug.add("Extra time: " + TimingStatistics.A.pVal() + ", " + TimingStatistics.B.pVal() + ", " + TimingStatistics.C.pVal() + ", " + TimingStatistics.D.pVal());
            debug.add("Extra 2 time: " + TimingStatistics.E.pVal() + ", " + TimingStatistics.F.pVal() + ", " + TimingStatistics.G.pVal() + ", " + TimingStatistics.H.pVal() + ", " + TimingStatistics.I.pVal());
        }
        debug.add(GPUTiming.INSTANCE.getDebug());
        PrintfDebugUtil.addToOut(debug);
    }

    public void shutdown() {
        if (this.shuttingDown) {
            return;
        }
        this.shuttingDown = true;
        try {
            Logger.info("Draining download stream (non-blocking)");
            DownloadStream.INSTANCE.tick();
            Logger.info("Flushing download stream before renderer teardown");
            try {
                DownloadStream.INSTANCE.flushWaitClear();
            } catch (Exception e) {
                Logger.error("Error flushing download stream before renderer shutdown", e);
            }

            Logger.info("Shutting down rendering");
            //Cleanup callbacks
            this.worldIn.setDirtyCallback(null);
            this.worldIn.getMapper().setBiomeCallback(null);
            this.worldIn.getMapper().setStateCallback(null);

            this.nodeManager.stop();

            this.modelService.shutdown();
            this.renderGen.shutdown();
            this.traversal.free();
            this.nodeCleaner.free();

            this.geometryData.free();
            this.chunkBoundRenderer.free();

            this.viewportSelector.free();
        } catch (Exception e) {
            Logger.error("Error shutting down renderer components", e);
        }
        Logger.info("Shutting down render pipeline");
        try {
            this.pipeline.free();
        } catch (Exception e) {
            Logger.error("Error releasing render pipeline", e);
        }

        Logger.info("Final download stream flush");
        try {
            DownloadStream.INSTANCE.flushWaitClear();
        } catch (Exception e) {
            Logger.error("Error during final download stream flush", e);
        }

        //Release hold on the world
        this.worldIn.releaseRef();
        Logger.info("Render shutdown completed");
    }

    private static long getGeometryBufferSize() {
        long geometryCapacity = Math.min((1L<<(64-Long.numberOfLeadingZeros(Capabilities.INSTANCE.ssboMaxSize-1)))<<1, 1L<<32)-1024;
        if (Capabilities.INSTANCE.isIntel) {
            geometryCapacity = Math.max(geometryCapacity, 1L<<30);
        }
        if (Capabilities.INSTANCE.isNvidia && Capabilities.INSTANCE.sparseBuffer) {
            geometryCapacity = Math.max(geometryCapacity, SPARSE_GEOMETRY_MIN_BYTES);
        }

        if (Capabilities.INSTANCE.canQueryGpuMemory && !(Capabilities.INSTANCE.isNvidia && Capabilities.INSTANCE.sparseBuffer)) {
            long limit = Capabilities.INSTANCE.getFreeDedicatedGpuMemory() - (long)(1.5*1024*1024*1024);
            limit = Math.max(512*1024*1024, limit);
            geometryCapacity = Math.min(geometryCapacity, limit);
        }
        var override = System.getProperty("voxy.geometryBufferSizeOverrideMB", "");
        if (!override.isEmpty()) {
            geometryCapacity = Long.parseLong(override)*1024L*1024L;
        }
        return geometryCapacity;
    }

    public WorldEngine getEngine() {
        return this.worldIn;
    }
}
