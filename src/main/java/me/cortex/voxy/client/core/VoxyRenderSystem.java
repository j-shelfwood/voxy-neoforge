package me.cortex.voxy.client.core;

// MC 1.21.1: These classes are in .platform package (moved to .opengl in 1.21.8+)
import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.voxy.client.TimingStatistics;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.config.VoxyConfig;
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
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
// TODO: FogParameters removed in Sodium 0.6.x - fog rendering disabled for now
// import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL11;

import java.util.Arrays;
import java.util.List;

import static org.lwjgl.opengl.GL11.GL_VIEWPORT;
import static org.lwjgl.opengl.GL11.glGetIntegerv;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL33.glBindSampler;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER_BINDING;

public class VoxyRenderSystem {
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
    private int shadowDebugLogCount;
    private boolean shadowGuardLogged;
    private boolean shadowCasterDisabled;

    private static AbstractSectionRenderer.Factory<?,? extends IGeometryData> getRenderBackendFactory() {
        //TODO: need todo a thing where selects optimal section render based on if supports the pipeline and geometry data type
        return MDICSectionRenderer.FACTORY;
    }

    public VoxyRenderSystem(WorldEngine world, ServiceManager sm) {
        //Keep the world loaded, NOTE: this is done FIRST, to keep and ensure that even if the rest of loading takes more
        // than timeout, we keep the world acquired
        world.acquireRef();
        System.gc();

        if (Minecraft.getInstance().options.getEffectiveRenderDistance()<3) {
            Logger.warn("Having a vanilla render distance of 2 can cause rare culling near the edge of your screen issues, please use 3 or more");
        }

        //Fking HATE EVERYTHING AAAAAAAAAAAAAAAA
        int[] oldBufferBindings = new int[10];
        for (int i = 0; i < oldBufferBindings.length; i++) {
            oldBufferBindings[i] = glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, i);
        }

        try {
            //wait for opengl to be finished, this should hopefully ensure all memory allocations are free
            glFinish();
            glFinish();

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

                this.renderDistanceTracker = new RenderDistanceTracker(20,
                        minSec,
                        maxSec,
                        this.nodeManager::addTopLevel,
                        this.nodeManager::removeTopLevel);

                this.setRenderDistance(VoxyConfig.CONFIG.sectionRenderDistance);
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

        for (int i = 0; i < 12; i++) {
            GlStateManager._activeTexture(GlConst.GL_TEXTURE0+i);
            GlStateManager._bindTexture(0);
            glBindSampler(i, 0);
        }
    }


    // Sodium 0.6.x compatibility: FogParameters parameter removed
    public Viewport<?> setupViewport(ChunkRenderMatrices matrices, double cameraX, double cameraY, double cameraZ) {
        int[] dims = new int[4];
        glGetIntegerv(GL_VIEWPORT, dims);
        return this.setupViewport(matrices, cameraX, cameraY, cameraZ, dims[2], dims[3]);
    }

    public Viewport<?> setupViewport(ChunkRenderMatrices matrices, double cameraX, double cameraY, double cameraZ, int width, int height) {
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
        var projection = computeProjectionMat(matrices.projection());//RenderSystem.getProjectionMatrix();
        //var projection = ShadowMatrices.createOrthoMatrix(160, -16*300, 16*300);
        //var projection = new Matrix4f(matrices.projection());

        {//Apply render scaling factor
            var factor = this.pipeline.getRenderScalingFactor();
            if (factor != null) {
                width = (int) (width*factor[0]);
                height = (int) (height*factor[1]);
            }
        }

        viewport
                .setVanillaProjection(matrices.projection())
                .setProjection(projection)
                .setModelView(new Matrix4f(matrices.modelView()))
                .setCamera(cameraX, cameraY, cameraZ)
                .setScreenSize(width, height)
                // Disabled for Sodium 0.6.x compatibility - FogParameters no longer exists
                // .setFogParameters(fogParameters)
                .update();

        if (VoxyClient.getOcclusionDebugState()==0) {
            viewport.frameId++;
        }

        return viewport;
    }

    public void renderShadow(Matrix4fc projection, Matrix4fc modelView, double cameraX, double cameraY, double cameraZ,
                             int resolution, float shadowDistance) {
        if (!(this.pipeline instanceof IrisVoxyRenderPipeline irisPipeline)) {
            this.logShadowGuard("pipeline=" + this.pipeline.getClass().getSimpleName());
            return;
        }
        if (!irisPipeline.supportsShadowCaster()) {
            this.logShadowGuard("shaderpack compatibility check failed");
            return;
        }
        if (this.shadowCasterDisabled) {
            this.logShadowGuard("caster disabled after an earlier render failure");
            return;
        }
        if (resolution <= 0) {
            this.logShadowGuard("invalid resolution=" + resolution);
            return;
        }

        var viewport = this.viewportSelector.getShadowViewport();
        viewport
                .setVanillaProjection(projection)
                .setProjection(new Matrix4f(projection))
                .setModelView(new Matrix4f(modelView))
                .setCamera(cameraX, cameraY, cameraZ)
                .setScreenSize(resolution, resolution);
        viewport.shadowMapBias = 1.0f - 25.6f / Math.max(25.7f, shadowDistance);
        viewport.updateShadow();

        if (this.shadowDebugLogCount++ < 3) {
            Logger.info("Voxy Iris shadow caster active: " + resolution + "x" + resolution
                    + ", distance=" + shadowDistance);
        }

        var state = new ShadowGlState();
        try {
            this.pipeline.renderShadow(viewport);
        } catch (RuntimeException e) {
            this.shadowCasterDisabled = true;
            Logger.error("Disabling Voxy shadow caster after a render failure", e);
        } finally {
            state.restore();
        }
    }

    private void logShadowGuard(String reason) {
        if (!this.shadowGuardLogged) {
            this.shadowGuardLogged = true;
            Logger.warn("Voxy Iris shadow caster skipped: " + reason);
        }
    }

    private static final class ShadowGlState {
        private static final int TEXTURE_UNIT_COUNT = 3;
        private static final int BUFFER_BINDING_COUNT = 12;

        private final int drawFramebuffer = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        private final int readFramebuffer = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        private final int program = glGetInteger(org.lwjgl.opengl.GL20C.GL_CURRENT_PROGRAM);
        private final int vertexArray = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        private final int activeTexture = glGetInteger(org.lwjgl.opengl.GL13C.GL_ACTIVE_TEXTURE);
        private final int drawIndirectBuffer = glGetInteger(org.lwjgl.opengl.GL40C.GL_DRAW_INDIRECT_BUFFER_BINDING);
        private final int dispatchIndirectBuffer = glGetInteger(org.lwjgl.opengl.GL43C.GL_DISPATCH_INDIRECT_BUFFER_BINDING);
        private final int parameterBuffer = glGetInteger(org.lwjgl.opengl.ARBIndirectParameters.GL_PARAMETER_BUFFER_BINDING_ARB);
        private final int depthFunction = glGetInteger(GL_DEPTH_FUNC);
        private final int cullFaceMode = glGetInteger(GL_CULL_FACE_MODE);
        private final int frontFace = glGetInteger(GL_FRONT_FACE);
        private final int provokingVertex = glGetInteger(org.lwjgl.opengl.GL32C.GL_PROVOKING_VERTEX);
        private final boolean depthTest = glIsEnabled(GL_DEPTH_TEST);
        private final boolean cullFace = glIsEnabled(GL_CULL_FACE);
        private final boolean blend = glIsEnabled(GL_BLEND);
        private final boolean depthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
        private final int[] viewport = new int[4];
        private final int[] colorMask = new int[4];
        private final int[] textures = new int[TEXTURE_UNIT_COUNT];
        private final int[] samplers = new int[TEXTURE_UNIT_COUNT];
        private final int uniformBuffer;
        private final int[] shaderStorageBuffers = new int[BUFFER_BINDING_COUNT];

        private ShadowGlState() {
            glGetIntegerv(GL_VIEWPORT, this.viewport);
            glGetIntegerv(GL_COLOR_WRITEMASK, this.colorMask);

            for (int i = 0; i < TEXTURE_UNIT_COUNT; i++) {
                org.lwjgl.opengl.GL13C.glActiveTexture(org.lwjgl.opengl.GL13C.GL_TEXTURE0 + i);
                this.textures[i] = glGetInteger(GL_TEXTURE_BINDING_2D);
                this.samplers[i] = glGetIntegeri(org.lwjgl.opengl.GL33C.GL_SAMPLER_BINDING, i);
            }
            org.lwjgl.opengl.GL13C.glActiveTexture(this.activeTexture);

            this.uniformBuffer = glGetIntegeri(org.lwjgl.opengl.GL31C.GL_UNIFORM_BUFFER_BINDING, 0);
            for (int i = 0; i < BUFFER_BINDING_COUNT; i++) {
                this.shaderStorageBuffers[i] = glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, i);
            }
        }

        private void restore() {
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, this.drawFramebuffer);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, this.readFramebuffer);
            glViewport(this.viewport[0], this.viewport[1], this.viewport[2], this.viewport[3]);
            glUseProgram(this.program);
            glBindVertexArray(this.vertexArray);

            org.lwjgl.opengl.GL15C.glBindBuffer(org.lwjgl.opengl.GL40C.GL_DRAW_INDIRECT_BUFFER, this.drawIndirectBuffer);
            org.lwjgl.opengl.GL15C.glBindBuffer(org.lwjgl.opengl.GL43C.GL_DISPATCH_INDIRECT_BUFFER, this.dispatchIndirectBuffer);
            org.lwjgl.opengl.GL15C.glBindBuffer(org.lwjgl.opengl.ARBIndirectParameters.GL_PARAMETER_BUFFER_ARB, this.parameterBuffer);
            org.lwjgl.opengl.GL30C.glBindBufferBase(org.lwjgl.opengl.GL31C.GL_UNIFORM_BUFFER, 0, this.uniformBuffer);
            for (int i = 0; i < BUFFER_BINDING_COUNT; i++) {
                org.lwjgl.opengl.GL30C.glBindBufferBase(GL_SHADER_STORAGE_BUFFER, i, this.shaderStorageBuffers[i]);
            }

            for (int i = 0; i < TEXTURE_UNIT_COUNT; i++) {
                org.lwjgl.opengl.GL45C.glBindTextureUnit(i, this.textures[i]);
                org.lwjgl.opengl.GL33C.glBindSampler(i, this.samplers[i]);
            }
            org.lwjgl.opengl.GL13C.glActiveTexture(this.activeTexture);

            glDepthFunc(this.depthFunction);
            glCullFace(this.cullFaceMode);
            glFrontFace(this.frontFace);
            org.lwjgl.opengl.GL32C.glProvokingVertex(this.provokingVertex);
            glDepthMask(this.depthMask);
            glColorMask(this.colorMask[0] != 0, this.colorMask[1] != 0, this.colorMask[2] != 0, this.colorMask[3] != 0);
            setEnabled(GL_DEPTH_TEST, this.depthTest);
            setEnabled(GL_CULL_FACE, this.cullFace);
            setEnabled(GL_BLEND, this.blend);
        }

        private static void setEnabled(int capability, boolean enabled) {
            if (enabled) {
                glEnable(capability);
            } else {
                glDisable(capability);
            }
        }
    }

    public void renderOpaque(Viewport<?> viewport) {
        if (viewport == null) {
            return;
        }

        // MC 1.21.1 NeoForge: Fog is handled by VoxyClientEvents.onRenderFog()
        // which listens to ViewportEvent.RenderFog and pushes fog to infinity
        // BEFORE terrain renders. This ensures no fog wall at vanilla render distance.

        TimingStatistics.resetSamplers();

        long startTime = System.nanoTime();
        TimingStatistics.all.start();
        GPUTiming.INSTANCE.marker();//Start marker
        TimingStatistics.main.start();

        //TODO: optimize
        int[] oldBufferBindings = new int[10];
        for (int i = 0; i < oldBufferBindings.length; i++) {
            oldBufferBindings[i] = glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, i);
        }


        int oldFB = GL11.glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int boundFB = oldFB;

        int[] dims = new int[4];
        glGetIntegerv(GL_VIEWPORT, dims);

        glViewport(0,0, viewport.width, viewport.height);

        //var target = DefaultTerrainRenderPasses.CUTOUT.getTarget();
        //boundFB = ((net.minecraft.client.texture.GlTexture) target.getColorAttachment()).getOrCreateFramebuffer(((GlBackend) RenderSystem.getDevice()).getFramebufferManager(), target.getDepthAttachment());
        if (boundFB == 0) {
            throw new IllegalStateException("Cannot use the default framebuffer as cannot source from it");
        }

        //this.autoBalanceSubDivSize();

        this.pipeline.preSetup(viewport);

        TimingStatistics.E.start();
        // MC 1.21.1 NeoForge: Iris shader integration excluded - irisShadowActive() returns false (no Iris shadows)
        if ((!VoxyClient.disableSodiumChunkRender())&&!false) {
            this.chunkBoundRenderer.render(viewport);
        } else {
            viewport.depthBoundingBuffer.clear(0);
        }
        TimingStatistics.E.stop();


        GPUTiming.INSTANCE.marker();
        //The entire rendering pipeline (excluding the chunkbound thing)
        this.pipeline.runPipeline(viewport, boundFB, dims[2], dims[3]);
        GPUTiming.INSTANCE.marker();

        TimingStatistics.main.stop();
        TimingStatistics.postDynamic.start();

        PrintfDebugUtil.tick();

        //As much dynamic runtime stuff here
        {
            //Tick upload stream (this is ok to do here as upload ticking is just memory management)
            UploadStream.INSTANCE.tick();

            while (this.renderDistanceTracker.setCenterAndProcess(viewport.cameraX, viewport.cameraZ) && VoxyClient.isFrexActive());//While FF is active, run until everything is processed
            TimingStatistics.H.start();
            //Done here as is allows less gl state resetup
            do { this.modelService.tick(900_000); } while (VoxyClient.isFrexActive() && !this.modelService.areQueuesEmpty());
            TimingStatistics.H.stop();
        }
        GPUTiming.INSTANCE.marker();
        TimingStatistics.postDynamic.stop();

        GPUTiming.INSTANCE.tick();

        glBindFramebuffer(GlConst.GL_FRAMEBUFFER, oldFB);
        glViewport(dims[0], dims[1], dims[2], dims[3]);

        {//Reset state manager stuffs
            glUseProgram(0);
            glEnable(GL_DEPTH_TEST);

            GlStateManager._glBindVertexArray(0);//Clear binding

            GlStateManager._activeTexture(GlConst.GL_TEXTURE1);
            for (int i = 0; i < 12; i++) {
                GlStateManager._activeTexture(GlConst.GL_TEXTURE0+i);
                GlStateManager._bindTexture(0);
                glBindSampler(i, 0);
            }

            // MC 1.21.1 NeoForge: Iris shader integration excluded - clearIrisSamplers() is a no-op
            // IrisUtil.clearIrisSamplers();//Thanks iris (sigh)

            //TODO: should/needto actually restore all of these, not just clear them
            //Clear all the bindings
            for (int i = 0; i < oldBufferBindings.length; i++) {
                glBindBufferBase(GL_SHADER_STORAGE_BUFFER, i, oldBufferBindings[i]);
            }

            //((SodiumShader) Iris.getPipelineManager().getPipelineNullable().getSodiumPrograms().getProgram(DefaultTerrainRenderPasses.CUTOUT).getInterface()).setupState(DefaultTerrainRenderPasses.CUTOUT, fogParameters);
        }

        TimingStatistics.all.stop();

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
            VoxyConfig.CONFIG.subDivisionSize = Math.min(VoxyConfig.CONFIG.subDivisionSize + INCREASE_PER_SECOND / Math.max(1f, Minecraft.getInstance().getFps()), 256);
        }

        if (MAX_FPS < Minecraft.getInstance().getFps() && canDecreaseSize) {
            VoxyConfig.CONFIG.subDivisionSize = Math.max(VoxyConfig.CONFIG.subDivisionSize - DECREASE_PER_SECOND / Math.max(1f, Minecraft.getInstance().getFps()), 28);
        }
    }

    private static Matrix4f makeProjectionMatrix(float near, float far) {
        //TODO: use the existing projection matrix use mulLocal by the inverse of the projection and then mulLocal our projection

        var projection = new Matrix4f();
        var client = Minecraft.getInstance();
        var gameRenderer = client.gameRenderer;//tickCounter.getTickDelta(true);

        // MC 1.21.1: getFov() became private, using options FOV value as fallback
        // TODO: Consider using ViewportEvent.ComputeFov for accurate FOV with modifiers
        float fov = client.options.fov().get().floatValue();

        projection.setPerspective(fov * 0.01745329238474369f,
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
        float nearVoxy = Minecraft.getInstance().gameRenderer.getRenderDistance()<=32.0f?8f:16f;
        nearVoxy = VoxyClient.disableSodiumChunkRender()?0.1f:nearVoxy;

        return base.mulLocal(
                makeProjectionMatrix(0.05f, Minecraft.getInstance().gameRenderer.getDepthFar()).invert(),
                new Matrix4f()
        ).mulLocal(makeProjectionMatrix(nearVoxy, 16*3000));
    }

    private boolean frexStillHasWork() {
        if (!VoxyClient.isFrexActive()) {
            return false;
        }
        //If frex is running we must tick everything to ensure correctness
        UploadStream.INSTANCE.tick();
        //Done here as is allows less gl state resetup
        this.modelService.tick(100_000_000);
        GL11.glFinish();
        return this.nodeManager.hasWork() || this.renderGen.getTaskCount()!=0 || !this.modelService.areQueuesEmpty();
    }

    public void setRenderDistance(int renderDistance) {
        this.renderDistanceTracker.setRenderDistance(renderDistance);
    }

    public Viewport<?> getViewport() {
        // MC 1.21.1 NeoForge: Iris shadow integration disabled - Oculus (NeoForge Iris port) not yet supported
        // TODO: Add Oculus shadow detection when available
        return this.viewportSelector.getViewport();
    }

    public boolean isUsingIrisPipeline() {
        return this.pipeline instanceof IrisVoxyRenderPipeline;
    }





    public void addDebugInfo(List<String> debug) {
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
        Logger.info("Flushing download stream");
        DownloadStream.INSTANCE.flushWaitClear();
        Logger.info("Shutting down rendering");
        try {
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
        } catch (Exception e) {Logger.error("Error shutting down renderer components", e);}
        Logger.info("Shutting down render pipeline");
        try {this.pipeline.free();} catch (Exception e){Logger.error("Error releasing render pipeline", e);}



        Logger.info("Flushing download stream");
        DownloadStream.INSTANCE.flushWaitClear();

        //Release hold on the world
        this.worldIn.releaseRef();
        Logger.info("Render shutdown completed");
    }

    private static long getGeometryBufferSize() {
        long geometryCapacity = Math.min((1L<<(64-Long.numberOfLeadingZeros(Capabilities.INSTANCE.ssboMaxSize-1)))<<1, 1L<<32)-1024/*(1L<<32)-1024*/;
        if (Capabilities.INSTANCE.isIntel) {
            geometryCapacity = Math.max(geometryCapacity, 1L<<30);//intel moment, force min 1gb
        }

        //Limit to available dedicated memory if possible
        if (Capabilities.INSTANCE.canQueryGpuMemory) {
            //512mb less than avalible,
            long limit = Capabilities.INSTANCE.getFreeDedicatedGpuMemory() - (long)(1.5*1024*1024*1024);//1.5gb vram buffer
            // Give a minimum of 512 mb requirement
            limit = Math.max(512*1024*1024, limit);

            geometryCapacity = Math.min(geometryCapacity, limit);
        }
        //geometryCapacity = 1<<28;
        //geometryCapacity = 1<<30;//1GB test
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
