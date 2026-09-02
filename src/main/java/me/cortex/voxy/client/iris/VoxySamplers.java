package me.cortex.voxy.client.iris;

import me.cortex.voxy.client.core.gl.GlTexture;
import net.irisshaders.iris.gl.sampler.GlSampler;
import net.irisshaders.iris.gl.sampler.SamplerHolder;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;

public class VoxySamplers {
   public static void addSamplers(IrisRenderingPipeline pipeline, SamplerHolder samplers) {
      IrisShaderPatch patchData = ((IGetVoxyPatchData)pipeline).voxy$getPatchData();
      if (patchData != null) {
         String[] opaqueNames = new String[]{"vxDepthTexOpaque"};
         String[] translucentNames = new String[]{"vxDepthTexTrans"};
         if (IrisShaderPatch.IMPERSONATE_DISTANT_HORIZONS) {
            opaqueNames = new String[]{"vxDepthTexOpaque", "dhDepthTex1"};
            translucentNames = new String[]{"vxDepthTexTrans", "dhDepthTex", "dhDepthTex0"};
         }

         samplers.addDynamicSampler(TextureType.TEXTURE_2D, () -> {
            IrisVoxyRenderPipelineData pipeData = ((IGetIrisVoxyPipelineData)pipeline).voxy$getPipelineData();
            if (pipeData == null) {
               return 0;
            } else if (pipeData.thePipeline == null) {
               return 0;
            } else {
               GlTexture dt = pipeData.thePipeline.fb.getDepthTex();
               return dt == null ? 0 : dt.id;
            }
         }, new GlSampler(false, true, false, false), opaqueNames);
         samplers.addDynamicSampler(TextureType.TEXTURE_2D, () -> {
            IrisVoxyRenderPipelineData pipeData = ((IGetIrisVoxyPipelineData)pipeline).voxy$getPipelineData();
            if (pipeData == null) {
               return 0;
            } else if (pipeData.thePipeline == null) {
               return 0;
            } else {
               GlTexture dt = pipeData.thePipeline.fbTranslucent.getDepthTex();
               return dt == null ? 0 : dt.id;
            }
         }, new GlSampler(false, true, false, false), translucentNames);
      }
   }
}
