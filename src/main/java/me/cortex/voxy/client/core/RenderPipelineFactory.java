package me.cortex.voxy.client.core;

import java.util.function.BooleanSupplier;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.client.iris.IGetIrisVoxyPipelineData;
import me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData;
import me.cortex.voxy.common.Logger;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;

public class RenderPipelineFactory {
   public static AbstractRenderPipeline createPipeline(
      AsyncNodeManager nodeManager, NodeCleaner nodeCleaner, HierarchicalOcclusionTraverser traversal, BooleanSupplier frexSupplier
   ) {
      AbstractRenderPipeline pipeline = null;
      if (IrisUtil.IRIS_INSTALLED && IrisUtil.irisShaderPackEnabled()) {
         pipeline = createIrisPipeline(nodeManager, nodeCleaner, traversal, frexSupplier);
      }

      if (pipeline == null) {
         pipeline = new NormalRenderPipeline(nodeManager, nodeCleaner, traversal, frexSupplier);
      }

      return pipeline;
   }

   private static AbstractRenderPipeline createIrisPipeline(
      AsyncNodeManager nodeManager, NodeCleaner nodeCleaner, HierarchicalOcclusionTraverser traversal, BooleanSupplier frexSupplier
   ) {
      WorldRenderingPipeline irisPipe = Iris.getPipelineManager().getPipelineNullable();
      if (irisPipe == null) {
         Logger.warn("Iris shaderpack is active, but its rendering pipeline is not ready");
         return null;
      } else if (irisPipe instanceof IGetIrisVoxyPipelineData getVoxyPipeData) {
         IrisVoxyRenderPipelineData pipeData = getVoxyPipeData.voxy$getPipelineData();
         if (pipeData == null) {
            Logger.warn("Iris shaderpack did not provide Voxy pipeline data");
            return null;
         } else {
            Logger.info("Creating voxy iris render pipeline");

            try {
               return new IrisVoxyRenderPipeline(pipeData, nodeManager, nodeCleaner, traversal, frexSupplier);
            } catch (Exception var8) {
               Logger.error("Failed to create iris render pipeline", var8);
               IrisUtil.disableIrisShaders();
               return null;
            }
         }
      } else {
         Logger.error("Iris rendering pipeline is missing Voxy mixin integration");
         return null;
      }
   }
}
