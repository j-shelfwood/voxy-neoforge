package me.cortex.voxy.client.compat;

import com.google.common.collect.ImmutableList;
import me.cortex.voxy.client.RenderStatistics;
import me.cortex.voxy.client.VoxyClientInstance;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.config.VoxyNeoForgeConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.embeddedt.embeddium.api.OptionGUIConstructionEvent;
import org.embeddedt.embeddium.api.options.OptionIdentifier;
import org.embeddedt.embeddium.api.options.control.ControlValueFormatter;
import org.embeddedt.embeddium.api.options.control.SliderControl;
import org.embeddedt.embeddium.api.options.control.TickBoxControl;
import org.embeddedt.embeddium.api.options.structure.OptionFlag;
import org.embeddedt.embeddium.api.options.structure.OptionGroup;
import org.embeddedt.embeddium.api.options.structure.OptionImpact;
import org.embeddedt.embeddium.api.options.structure.OptionImpl;
import org.embeddedt.embeddium.api.options.structure.OptionPage;
import org.embeddedt.embeddium.api.options.structure.OptionStorage;

public final class EmbeddiumOptionsCompat {
    private static final OptionStorage<VoxyConfig> STORAGE = new VoxyConfigStorage();

    private EmbeddiumOptionsCompat() {
    }

    public static void register() {
        OptionGUIConstructionEvent.BUS.addListener(EmbeddiumOptionsCompat::onOptionsGuiBuild);
    }

    private static void onOptionsGuiBuild(OptionGUIConstructionEvent event) {
        event.addPage(buildPage());
    }

    private static OptionPage buildPage() {
        OptionGroup general = OptionGroup.createBuilder()
                .setId(ResourceLocation.fromNamespaceAndPath("voxy", "general"))
                .add(booleanOption(
                        "enabled",
                        "voxy.config.general.enabled",
                        "voxy.config.general.enabled.tooltip",
                        (cfg, value) -> cfg.setEnabled(value),
                        VoxyConfig::isEnabled,
                        OptionImpact.HIGH,
                        OptionFlag.REQUIRES_RENDERER_RELOAD))
                .add(booleanOption(
                        "enable_rendering",
                        "voxy.config.general.rendering",
                        "voxy.config.general.rendering.tooltip",
                        (cfg, value) -> cfg.setRenderingEnabled(value),
                        cfg -> VoxyNeoForgeConfig.isRenderingEnabled(),
                        OptionImpact.HIGH,
                        OptionFlag.REQUIRES_RENDERER_RELOAD))
                .add(booleanOption(
                        "ingest_enabled",
                        "voxy.config.general.ingest",
                        "voxy.config.general.ingest.tooltip",
                        (cfg, value) -> cfg.setIngestEnabled(value),
                        VoxyConfig::isIngestEnabled,
                        OptionImpact.MEDIUM))
                .build();

        OptionGroup performance = OptionGroup.createBuilder()
                .setId(ResourceLocation.fromNamespaceAndPath("voxy", "performance"))
                .add(intSliderOption(
                        "section_render_distance",
                        "voxy.config.general.renderDistance",
                        "voxy.config.general.renderDistance.tooltip",
                        2, 64, 1, ControlValueFormatter.number(),
                        (cfg, value) -> cfg.setSectionRenderDistance(value),
                        VoxyConfig::getSectionRenderDistance,
                        OptionImpact.HIGH,
                        OptionFlag.REQUIRES_RENDERER_UPDATE))
                .add(booleanOption(
                        "camera_distance_culling",
                        "voxy.config.general.camera_distance_culling",
                        "voxy.config.general.camera_distance_culling.tooltip",
                        (cfg, value) -> cfg.setCameraDistanceCullingEnabled(value),
                        VoxyConfig::isCameraDistanceCullingEnabled,
                        OptionImpact.MEDIUM,
                        OptionFlag.REQUIRES_RENDERER_RELOAD))
                .add(booleanOption(
                        "visibility_culling",
                        "voxy.config.general.visibility_culling",
                        "voxy.config.general.visibility_culling.tooltip",
                        (cfg, value) -> cfg.setVisibilityCullingEnabled(value),
                        VoxyConfig::isVisibilityCullingEnabled,
                        OptionImpact.MEDIUM,
                        OptionFlag.REQUIRES_RENDERER_RELOAD))
                .add(intSliderOption(
                        "service_threads",
                        "voxy.config.general.serviceThreads",
                        "voxy.config.general.serviceThreads.tooltip",
                        1, Math.max(1, me.cortex.voxy.common.util.cpu.CpuLayout.getCoreCount()), 1,
                        ControlValueFormatter.number(),
                        (cfg, value) -> cfg.setServiceThreads(value),
                        VoxyConfig::getServiceThreads,
                        OptionImpact.MEDIUM))
                .add(booleanOption(
                        "use_embeddium_builder_threads",
                        "voxy.config.general.useEmbeddiumBuilder",
                        "voxy.config.general.useEmbeddiumBuilder.tooltip",
                        (cfg, value) -> cfg.setDontUseEmbeddiumBuilderThreads(!value),
                        cfg -> !cfg.dontUseEmbeddiumBuilderThreads(),
                        OptionImpact.MEDIUM))
                .add(intSliderOption(
                        "subdivision_size",
                        "voxy.config.general.subDivisionSize",
                        "voxy.config.general.subDivisionSize.tooltip",
                        28, 256, 1, ControlValueFormatter.number(),
                        (cfg, value) -> cfg.setSubDivisionSize(value),
                        cfg -> Math.round(cfg.getSubDivisionSize()),
                        OptionImpact.MEDIUM))
                .build();

        OptionGroup rendering = OptionGroup.createBuilder()
                .setId(ResourceLocation.fromNamespaceAndPath("voxy", "rendering"))
                .add(booleanOption(
                        "use_environmental_fog",
                        "voxy.config.general.environmental_fog",
                        "voxy.config.general.environmental_fog.tooltip",
                        (cfg, value) -> cfg.setUseEnvironmentalFog(value),
                        VoxyConfig::useEnvironmentalFog,
                        OptionImpact.LOW,
                        OptionFlag.REQUIRES_RENDERER_RELOAD))
                .add(booleanOption(
                        "shader_pack_fog_override",
                        "voxy.config.general.shader_pack_fog_override",
                        "voxy.config.general.shader_pack_fog_override.tooltip",
                        (cfg, value) -> cfg.setShaderPackFogOverride(value),
                        VoxyConfig::enableShaderPackFogOverride,
                        OptionImpact.LOW))
                .add(intSliderOption(
                        "earth_curve_ratio",
                        "voxy.config.general.earth_curve_ratio",
                        "voxy.config.general.earth_curve_ratio.tooltip",
                        0, 250, 5, ControlValueFormatter.number(),
                        (cfg, value) -> cfg.setEarthCurveRatio(value),
                        VoxyConfig::getEarthCurveRatio,
                        OptionImpact.LOW))
                .build();

        OptionGroup debug = OptionGroup.createBuilder()
                .setId(ResourceLocation.fromNamespaceAndPath("voxy", "debug"))
                .add(booleanOption(
                        "loading_indicator",
                        "voxy.config.general.loading_indicator",
                        "voxy.config.general.loading_indicator.tooltip",
                        (cfg, value) -> cfg.setLoadingIndicatorEnabled(value),
                        VoxyConfig::isLoadingIndicatorEnabled,
                        OptionImpact.LOW))
                .add(booleanOption(
                        "render_statistics",
                        "voxy.config.general.render_statistics",
                        "voxy.config.general.render_statistics.tooltip",
                        (cfg, value) -> VoxyNeoForgeConfig.setRenderStatistics(value),
                        cfg -> VoxyNeoForgeConfig.isRenderStatisticsEnabled(),
                        OptionImpact.LOW))
                .build();

        return new OptionPage(
                OptionIdentifier.create(ResourceLocation.fromNamespaceAndPath("voxy", "voxy")),
                Component.translatable("voxy.config.title"),
                ImmutableList.of(general, performance, rendering, debug));
    }

    private static OptionImpl<VoxyConfig, Boolean> booleanOption(
            String idPath,
            String nameKey,
            String tooltipKey,
            java.util.function.BiConsumer<VoxyConfig, Boolean> setter,
            java.util.function.Function<VoxyConfig, Boolean> getter,
            OptionImpact impact,
            OptionFlag... flags) {
        return OptionImpl.createBuilder(Boolean.class, STORAGE)
                .setId(ResourceLocation.fromNamespaceAndPath("voxy", idPath))
                .setName(Component.translatable(nameKey))
                .setTooltip(Component.translatable(tooltipKey))
                .setBinding(setter, getter)
                .setImpact(impact)
                .setFlags(flags)
                .setControl(TickBoxControl::new)
                .build();
    }

    private static OptionImpl<VoxyConfig, Integer> intSliderOption(
            String idPath,
            String nameKey,
            String tooltipKey,
            int min,
            int max,
            int step,
            ControlValueFormatter formatter,
            java.util.function.BiConsumer<VoxyConfig, Integer> setter,
            java.util.function.Function<VoxyConfig, Integer> getter,
            OptionImpact impact,
            OptionFlag... flags) {
        return OptionImpl.createBuilder(Integer.class, STORAGE)
                .setId(ResourceLocation.fromNamespaceAndPath("voxy", idPath))
                .setName(Component.translatable(nameKey))
                .setTooltip(Component.translatable(tooltipKey))
                .setBinding(setter, getter)
                .setImpact(impact)
                .setFlags(flags)
                .setControl(option -> new SliderControl(option, min, max, step, formatter))
                .build();
    }

    private static final class VoxyConfigStorage implements OptionStorage<VoxyConfig> {
        private static boolean lastEnabled = VoxyConfig.CONFIG.isEnabled();
        private static boolean lastRenderingEnabled = VoxyNeoForgeConfig.isRenderingEnabled();
        private static boolean lastCameraDistanceCulling = VoxyConfig.CONFIG.isCameraDistanceCullingEnabled();
        private static boolean lastVisibilityCulling = VoxyConfig.CONFIG.isVisibilityCullingEnabled();

        @Override
        public VoxyConfig getData() {
            return VoxyConfig.CONFIG;
        }

        @Override
        public void save() {
            VoxyConfig.CONFIG.save();

            boolean enabled = VoxyConfig.CONFIG.isEnabled();
            boolean renderingEnabled = VoxyNeoForgeConfig.isRenderingEnabled();
            boolean cameraDistanceCulling = VoxyConfig.CONFIG.isCameraDistanceCullingEnabled();
            boolean visibilityCulling = VoxyConfig.CONFIG.isVisibilityCullingEnabled();
            boolean rendererModeChanged = (enabled != lastEnabled) || (renderingEnabled != lastRenderingEnabled);
            boolean distanceCullingModeChanged = cameraDistanceCulling != lastCameraDistanceCulling;
            boolean visibilityCullingModeChanged = visibilityCulling != lastVisibilityCulling;

            if (enabled && VoxyCommon.isAvailable() && VoxyCommon.getInstance() == null && VoxyClientInstance.isInGame) {
                VoxyCommon.createInstance();
            } else if (!enabled && VoxyCommon.getInstance() != null) {
                VoxyCommon.shutdownInstance();
            }

            var instance = (VoxyClientInstance) VoxyCommon.getInstance();
            if (instance != null) {
                instance.updateDedicatedThreads();
            }

            var renderer = IGetVoxyRenderSystem.getNullable();
            if (renderer != null) {
                renderer.setRenderDistance(VoxyConfig.CONFIG.getSectionRenderDistance());
            }

            if (rendererModeChanged || distanceCullingModeChanged || visibilityCullingModeChanged) {
                Logger.info("[VoxyConfig] Renderer mode changed: enabled " + lastEnabled + " -> " + enabled
                        + ", rendering " + lastRenderingEnabled + " -> " + renderingEnabled
                        + ", cameraDistanceCulling " + lastCameraDistanceCulling + " -> " + cameraDistanceCulling
                        + ", visibilityCulling " + lastVisibilityCulling + " -> " + visibilityCulling);
                if (IrisCompatManager.isShaderPackEnabled()) {
                    IrisCompatManager.reloadShaders();
                }
                VoxyRenderSystem.scheduleRendererRecreate("config toggle enabled/rendering/cameraDistanceCulling/visibilityCulling changed");
            }

            lastEnabled = enabled;
            lastRenderingEnabled = renderingEnabled;
            lastCameraDistanceCulling = cameraDistanceCulling;
            lastVisibilityCulling = visibilityCulling;
        }
    }
}
