package me.cortex.voxy.client.core;

import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.rendering.post.FullscreenBlit;
import me.cortex.voxy.client.core.rendering.section.backend.AbstractSectionRenderer;
import me.cortex.voxy.client.core.rendering.util.DepthFramebuffer;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL30;

import java.util.List;
import java.util.function.BooleanSupplier;

import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER;
import static org.lwjgl.opengl.GL45C.*;

public class IrisVoxyRenderPipeline extends AbstractRenderPipeline {
    private static final boolean ENABLE_IRIS_TEMPORAL_PASS =
            System.getProperty("voxy.irisTemporalPass", "false").equalsIgnoreCase("true");
    private static final boolean ENABLE_GLSL_COMPAT_FIXES =
            System.getProperty("voxy.irisGlslCompatFixes", "false").equalsIgnoreCase("true");
    // Override: force-enable or force-disable the depth bridge regardless of voxy.json setting.
    // "true" = always copy LOD depth to vanilla FB; "false" = always skip; absent = use voxy.json.
    private static final String COPY_DEPTH_TO_VANILLA_OVERRIDE =
            System.getProperty("voxy.copyDepthToVanilla", "");
    // If depth bridge is enabled, choose which LOD depth buffer to export.
    private static final boolean COPY_TRANSLUCENT_DEPTH_TO_VANILLA =
            System.getProperty("voxy.copyTranslucentDepthToVanilla", "false").equalsIgnoreCase("true");

    final IrisVoxyRenderPipelineData data;
    final FullscreenBlit depthBlit = new FullscreenBlit("voxy:post/blit_texture_depth_cutout.frag");
    public final DepthFramebuffer fbTranslucent = new DepthFramebuffer(this.fb.getFormat());
    private final int fallbackDepthTextureId;

    private final GlBuffer shaderUniforms;

    public IrisVoxyRenderPipeline(IrisVoxyRenderPipelineData data, AsyncNodeManager nodeManager, NodeCleaner nodeCleaner, HierarchicalOcclusionTraverser traversal, BooleanSupplier frexSupplier) {
        super(nodeManager, nodeCleaner, traversal, frexSupplier, data.shouldDeferTranslucency());
        this.data = data;
        if (this.data.thePipeline != null) {
            throw new IllegalStateException("Pipeline data already bound");
        }
        this.data.thePipeline = this;

        //Bind the drawbuffers
        var oDT = this.data.opaqueDrawTargets;
        int[] binding = new int[oDT.length];
        for (int i = 0; i < oDT.length; i++) {
            binding[i] = GL30.GL_COLOR_ATTACHMENT0+i;
            glNamedFramebufferTexture(this.fb.framebuffer.id, GL30.GL_COLOR_ATTACHMENT0+i, oDT[i], 0);
        }
        glNamedFramebufferDrawBuffers(this.fb.framebuffer.id, binding);

        var tDT = this.data.translucentDrawTargets;
        binding = new int[tDT.length];
        for (int i = 0; i < tDT.length; i++) {
            binding[i] = GL30.GL_COLOR_ATTACHMENT0+i;
            glNamedFramebufferTexture(this.fbTranslucent.framebuffer.id, GL30.GL_COLOR_ATTACHMENT0+i, tDT[i], 0);
        }
        glNamedFramebufferDrawBuffers(this.fbTranslucent.framebuffer.id, binding);

        this.fb.framebuffer.verify();
        this.fbTranslucent.framebuffer.verify();
        this.fallbackDepthTextureId = createFallbackDepthTexture();

        if (data.getUniforms() != null) {
            this.shaderUniforms = new GlBuffer(data.getUniforms().size());
        } else {
            this.shaderUniforms = null;
        }
    }

    @Override
    public void setupExtraModelBakeryData(ModelBakerySubsystem modelService) {
        modelService.factory.setCustomBlockStateMapping(WorldRenderingSettings.INSTANCE.getBlockStateIds());
    }

    @Override
    public void free() {
        if (this.data.thePipeline != this) {
            throw new IllegalStateException();
        }
        this.data.thePipeline = null;

        this.depthBlit.delete();
        this.fbTranslucent.free();
        glDeleteTextures(this.fallbackDepthTextureId);

        if (this.shaderUniforms != null) {
            this.shaderUniforms.free();
        }

        super.free0();
    }

    public int getFallbackDepthTextureId() {
        return this.fallbackDepthTextureId;
    }

    private static int createFallbackDepthTexture() {
        // Must match AbstractRenderPipeline.fb format (GL_DEPTH24_STENCIL8) so the sampler
        // type is consistent when the pipeline switches from fallback to the real depth texture.
        int texture = glCreateTextures(GL_TEXTURE_2D);
        glTextureStorage2D(texture, 1, GL_DEPTH24_STENCIL8, 1, 1);
        glTextureParameteri(texture, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTextureParameteri(texture, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTextureParameteri(texture, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTextureParameteri(texture, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        // Clear via a temporary FBO — GL_DEPTH24_STENCIL8 requires glClearNamedFramebufferfi,
        // matching the same clear path used by DepthFramebuffer for the real depth buffers.
        int fbo = glCreateFramebuffers();
        glNamedFramebufferTexture(fbo, GL_DEPTH_STENCIL_ATTACHMENT, texture, 0);
        glClearNamedFramebufferfi(fbo, GL_DEPTH_STENCIL, 0, 1.0f, 0);
        glDeleteFramebuffers(fbo);
        return texture;
    }

    @Override
    public void preSetup(Viewport<?> viewport) {
        super.preSetup(viewport);
        if (this.shaderUniforms != null) {
            //Update the uniforms
            long ptr = UploadStream.INSTANCE.uploadTo(this.shaderUniforms);
            this.data.getUniforms().updater().accept(ptr);
            UploadStream.INSTANCE.commit();
        }
    }

    @Override
    protected int setup(Viewport<?> viewport, int sourceFramebuffer, int srcWidth, int srcHeight) {

        this.fb.resize(viewport.width, viewport.height);
        this.fbTranslucent.resize(viewport.width, viewport.height);

        if (false) {//TODO: only do this if shader specifies
            //Clear the colour component
            glBindFramebuffer(GL_FRAMEBUFFER, this.fb.framebuffer.id);
            glClearColor(0, 0, 0, 0);
            glClear(GL_COLOR_BUFFER_BIT);
        }

        if (!this.data.useViewportDims) {
            srcWidth = viewport.width;
            srcHeight = viewport.height;
        }
        this.initDepthStencil(sourceFramebuffer, this.fb.framebuffer.id, srcWidth, srcHeight, viewport.width, viewport.height);
        return this.fb.getDepthTex().id;
    }

    @Override
    protected void postOpaquePreTranslucent(Viewport<?> viewport) {
        int msk = GL_DEPTH_BUFFER_BIT|GL_STENCIL_BUFFER_BIT;
        if (true) {//TODO: make shader specified
            if (false) {//TODO: only do this if shader specifies
                glBindFramebuffer(GL_FRAMEBUFFER, this.fbTranslucent.framebuffer.id);
                glClearColor(0, 0, 0, 0);
                glClear(GL_COLOR_BUFFER_BIT);
            }
        } else {
            msk |= GL_COLOR_BUFFER_BIT;
        }
        glBlitNamedFramebuffer(this.fb.framebuffer.id, this.fbTranslucent.framebuffer.id, 0,0, viewport.width, viewport.height, 0,0, viewport.width, viewport.height, msk, GL_NEAREST);
    }

    @Override
    protected boolean shouldRenderTemporal(Viewport<?> viewport) {
        // The temporal bridge can create persistent overlay artifacts in shader-pack pipelines.
        // Keep it opt-in for Iris until a dedicated DH-native temporal path is implemented.
        return ENABLE_IRIS_TEMPORAL_PASS;
    }

    @Override
    protected void finish(Viewport<?> viewport, int sourceFrameBuffer, int srcWidth, int srcHeight) {
        // Default: copy depth to vanilla FB when voxy.json says so (emitToVanillaDepth = !excludeLodsFromVanillaDepth).
        // System property "voxy.copyDepthToVanilla=true/false" overrides the per-pack setting.
        boolean copyDepth = COPY_DEPTH_TO_VANILLA_OVERRIDE.equalsIgnoreCase("true") ? true
                : COPY_DEPTH_TO_VANILLA_OVERRIDE.equalsIgnoreCase("false") ? false
                : this.data.renderToVanillaDepth;
        if (copyDepth
                && srcWidth == viewport.width  && srcHeight == viewport.height) {//We can only depthblit out if destination size is the same
            glColorMask(false, false, false, false);
            int depthSourceTex = COPY_TRANSLUCENT_DEPTH_TO_VANILLA
                    ? this.fbTranslucent.getDepthTex().id
                    : this.fb.getDepthTex().id;
            AbstractRenderPipeline.transformBlitDepth(this.depthBlit,
                    depthSourceTex, sourceFrameBuffer,
                    viewport, new Matrix4f(viewport.vanillaProjection).mul(viewport.modelView));
            glColorMask(true, true, true, true);
        } else {
            // normally disabled by AbstractRenderPipeline but since we are skipping it we do it here
            glDisable(GL_STENCIL_TEST);
            glDisable(GL_DEPTH_TEST);
        }
    }


    @Override
    public void bindUniforms() {
        this.bindUniforms(UNIFORM_BINDING_POINT);
    }

    @Override
    public void bindUniforms(int bindingPoint) {
        if (this.shaderUniforms != null) {
            GL30.glBindBufferBase(GL_UNIFORM_BUFFER, bindingPoint, this.shaderUniforms.id);// todo: dont randomly select this to 5
        }
    }

    /**
     * Returns true if bindings succeeded; false if Iris RenderTargets were already destroyed
     * (happens during a mid-render shader-pack reload — caller should skip rendering this frame).
     */
    private boolean doBindings() {
        this.bindUniforms();
        try {
            if (this.data.getSsboSet() != null) {
                this.data.getSsboSet().bindingFunction().accept(10);
            }
            if (this.data.getImageSet() != null) {
                this.data.getImageSet().bindingFunction().accept(6);
            }
        } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().contains("destroyed RenderTargets")) {
                // Iris destroyed its RenderTargets during a pipeline rebuild while Voxy was mid-render.
                // Schedule a deferred renderer recreate so Voxy can bind against the new Iris pipeline data.
                me.cortex.voxy.common.Logger.warn("[IrisVoxyRenderPipeline] Iris RenderTargets destroyed mid-render — skipping frame");
                VoxyRenderSystem.scheduleRendererRecreate("destroyed RenderTargets during bind");
                return false;
            }
            throw e;
        }
        return true;
    }

    @Override
    public void setupAndBindOpaque(Viewport<?> viewport) {
        this.fb.bind();
        if (!this.doBindings()) return; // RenderTargets destroyed — skip this frame
    }

    @Override
    public void setupAndBindTranslucent(Viewport<?> viewport) {
        this.fbTranslucent.bind();
        if (!this.doBindings()) return; // RenderTargets destroyed — skip this frame
        if (this.data.getBlender() != null) {
            this.data.getBlender().run();
        }
    }

    @Override
    public void addDebug(List<String> debug) {
        debug.add("Using: " + this.getClass().getSimpleName());
        debug.add("Iris temporal pass: " + (ENABLE_IRIS_TEMPORAL_PASS ? "enabled" : "disabled"));
        debug.add("Iris GLSL compat fixes: " + (ENABLE_GLSL_COMPAT_FIXES ? "enabled" : "disabled"));
        boolean copyDepth = COPY_DEPTH_TO_VANILLA_OVERRIDE.equalsIgnoreCase("true") ? true
                : COPY_DEPTH_TO_VANILLA_OVERRIDE.equalsIgnoreCase("false") ? false
                : this.data.renderToVanillaDepth;
        debug.add("Iris depth bridge: " + (copyDepth
                ? (COPY_TRANSLUCENT_DEPTH_TO_VANILLA ? "translucent" : "opaque-only")
                : "disabled"));
        super.addDebug(debug);
    }

    private static final int UNIFORM_BINDING_POINT = 5;//TODO make ths binding point... not randomly 5
    private boolean headerLogged = false;

    private StringBuilder buildGenericShaderHeader(AbstractSectionRenderer<?, ?> renderer, String input) {
        StringBuilder builder = new StringBuilder(input).append("\n\n\n");

        if (this.data.getUniforms() != null) {
            builder.append("layout(binding = "+UNIFORM_BINDING_POINT+", std140) uniform ShaderUniformBindings ")
                    .append(this.data.getUniforms().layout())
                    .append(";\n\n");
        }

        if (this.data.getSsboSet() != null) {
            // Note: SSBO layout uses literal binding indices (not macro) to avoid NVIDIA C1154.
            builder.append(this.data.getSsboSet().layout()).append("\n\n");
        }

        if (this.data.getImageSet() != null) {
            // Note: sampler layout uses literal binding indices (not macro) to avoid NVIDIA C1154.
            builder.append(this.data.getImageSet().layout()).append("\n\n");
        }

        var result = builder.append("\n\n");
        if (!this.headerLogged) {
            this.headerLogged = true;
            // Extract just the appended header portion (everything after the base input)
            String header = result.toString().substring(input.length());
            me.cortex.voxy.common.Logger.info("[IrisVoxyRenderPipeline] shader header injected (first call):\n" + header.trim());
        }
        return result;
    }



    /**
     * Apply compatibility fixups to the combined shader source string.
     * Called after all patch sources are appended so included-file content is also covered.
     */
    private static String applyGlslCompatFixes(String source) {
        if (source == null) return null;
        if (!ENABLE_GLSL_COMPAT_FIXES) return source;
        // shadow2D() was removed in GLSL 1.40; replace with texture() which is the modern equivalent.
        // This handles packs like BSL whose included lighting libs still use the deprecated form.
        source = source.replace("shadow2D(", "texture(");
        source = source.replace("shadow2DLod(", "textureLod(");
        return source;
    }

    private boolean opaquePatchLogged = false;
    private boolean translucentPatchLogged = false;
    private boolean glslCompatLogPrinted = false;

    @Override
    public String patchOpaqueShader(AbstractSectionRenderer<?, ?> renderer, String input) {
        var builder = this.buildGenericShaderHeader(renderer, input);

        String opaquePatch = this.data.opaqueFragPatch();
        if (this.data.getImageSet() != null) {
            // Inject layout(binding=N) into sampler declarations already in the patch source
            // to avoid conflicting re-declarations (NVIDIA: "declaration conflicts with previous declaration").
            opaquePatch = this.data.getImageSet().applyBindingsToSource(opaquePatch);
        }
        builder.append(opaquePatch);

        String result = applyGlslCompatFixes(builder.toString());
        if (ENABLE_GLSL_COMPAT_FIXES && !this.glslCompatLogPrinted) {
            this.glslCompatLogPrinted = true;
            me.cortex.voxy.common.Logger.warn("[IrisVoxyRenderPipeline] GLSL compatibility rewrites enabled via -Dvoxy.irisGlslCompatFixes=true (shadow2D/shadow2DLod replacement)");
        }
        if (!this.opaquePatchLogged) {
            this.opaquePatchLogged = true;
            String snippet = result != null && result.length() > 3000 ? result.substring(0, 3000) + "\n...[truncated, total len=" + result.length() + "]" : result;
            me.cortex.voxy.common.Logger.info("[IrisVoxyRenderPipeline] patchOpaqueShader assembled (first call, len=" + (result == null ? "null" : result.length()) + "):\n" + snippet);
        }
        return result;
    }

    @Override
    public String patchTranslucentShader(AbstractSectionRenderer<?, ?> renderer, String input) {
        if (this.data.translucentFragPatch() == null) return null;

        var builder = this.buildGenericShaderHeader(renderer, input);
        String translucentPatch = this.data.translucentFragPatch();
        if (this.data.getImageSet() != null) {
            translucentPatch = this.data.getImageSet().applyBindingsToSource(translucentPatch);
        }
        builder.append(translucentPatch);
        String result = applyGlslCompatFixes(builder.toString());
        if (!this.translucentPatchLogged) {
            this.translucentPatchLogged = true;
            String snippet = result != null && result.length() > 2000 ? result.substring(0, 2000) + "\n...[truncated, total len=" + result.length() + "]" : result;
            me.cortex.voxy.common.Logger.info("[IrisVoxyRenderPipeline] patchTranslucentShader assembled (first call, len=" + (result == null ? "null" : result.length()) + "):\n" + snippet);
        }
        return result;
    }

    @Override
    public String taaFunction(String functionName) {
        return this.taaFunction(UNIFORM_BINDING_POINT, functionName);
    }

    @Override
    public String taaFunction(int uboBindingPoint, String functionName) {
        var builder = new StringBuilder();

        if (this.data.getUniforms() != null) {
            builder.append("layout(binding = "+uboBindingPoint+", std140) uniform ShaderUniformBindings ")
                    .append(this.data.getUniforms().layout())
                    .append(";\n\n");
        }

        builder.append("vec2 ").append(functionName).append("()\n");
        builder.append(this.data.TAA);
        builder.append("\n");
        return builder.toString();
    }

    @Override
    public float[] getRenderScalingFactor() {
        return this.data.resolutionScale;
    }
}
