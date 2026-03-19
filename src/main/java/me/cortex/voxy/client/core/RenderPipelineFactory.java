package me.cortex.voxy.client.core;

import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.compat.IrisCompatManager;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.client.iris.IGetIrisVoxyPipelineData;
import me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData;
import me.cortex.voxy.common.Logger;
import net.irisshaders.iris.Iris;

import java.util.function.BooleanSupplier;

public class RenderPipelineFactory {
    public static AbstractRenderPipeline createPipeline(AsyncNodeManager nodeManager, NodeCleaner nodeCleaner, HierarchicalOcclusionTraverser traversal, BooleanSupplier frexSupplier) {
        //Note this is where will choose/create e.g. IrisRenderPipeline or normal pipeline
        AbstractRenderPipeline pipeline = null;
        if (IrisUtil.IRIS_INSTALLED && IrisUtil.SHADER_SUPPORT) {
            pipeline = createIrisPipeline(nodeManager, nodeCleaner, traversal, frexSupplier);
        }
        if (pipeline == null) {
            Logger.info("[RenderPipelineFactory] Falling back to NormalRenderPipeline");
            // Only notify if a shader pack is actually active — silent with no pack loaded.
            if (IrisUtil.IRIS_INSTALLED && IrisUtil.SHADER_SUPPORT && IrisCompatManager.isShaderPackEnabled()) {
                var irisPipe = Iris.getPipelineManager().getPipelineNullable();
                if (irisPipe != null) {
                    // Pack loaded but not Voxy-compatible (no voxy.json/DH programs, or Iris compile error).
                    String packName = Iris.getCurrentPackName();
                    VoxyChatNotifier.notifyIrisFallback(packName != null ? packName : "unknown");
                }
                // If irisPipe is null here, a recreate is already scheduled by MixinIrisRenderingPipeline
                // so we stay silent — the correct message will fire after the deferred rebuild.
            }
            pipeline = new NormalRenderPipeline(nodeManager, nodeCleaner, traversal, frexSupplier);
        }
        return pipeline;
    }

    private static AbstractRenderPipeline createIrisPipeline(AsyncNodeManager nodeManager, NodeCleaner nodeCleaner, HierarchicalOcclusionTraverser traversal, BooleanSupplier frexSupplier) {
        var irisPipe = Iris.getPipelineManager().getPipelineNullable();
        if (irisPipe == null) {
            Logger.info("[RenderPipelineFactory] Iris pipeline is null; cannot create IrisVoxyRenderPipeline yet");
            return null;
        }
        if (irisPipe instanceof IGetIrisVoxyPipelineData getVoxyPipeData) {
            var pipeData = getVoxyPipeData.voxy$getPipelineData();
            if (pipeData == null) {
                Logger.warn("[RenderPipelineFactory] Iris pipeline has no Voxy pipeline data (patch not ready/instrumented)");
                return null;
            }
            Logger.info("Creating voxy iris render pipeline");
            try {
                var pipeline = new IrisVoxyRenderPipeline(pipeData, nodeManager, nodeCleaner, traversal, frexSupplier);
                // Notify user which integration mode activated.
                String packName = Iris.getCurrentPackName();
                if (packName == null) packName = "unknown";
                notifySuccess(pipeData, packName);
                return pipeline;
            } catch (Exception e) {
                Logger.error("Failed to create iris render pipeline", e);
                String packName = Iris.getCurrentPackName();
                VoxyChatNotifier.notifyIrisPipelineError(
                        packName != null ? packName : "unknown",
                        e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                IrisUtil.disableIrisShaders();
                return null;
            }
        }
        Logger.warn("[RenderPipelineFactory] Iris pipeline class is not Voxy-instrumented: " + irisPipe.getClass().getName());
        return null;
    }

    private static void notifySuccess(IrisVoxyRenderPipelineData pipeData, String packName) {
        if (pipeData.isDhNativeCandidate) {
            VoxyChatNotifier.notifyDhNative(packName);
        } else {
            VoxyChatNotifier.notifyVoxyPatch(packName);
        }
    }
}
