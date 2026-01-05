package me.cortex.voxy.client.config;

import com.google.common.collect.ImmutableList;
import me.cortex.voxy.client.RenderStatistics;
import net.caffeinemc.mods.sodium.client.gui.options.Option;
import net.caffeinemc.mods.sodium.client.gui.options.OptionGroup;
import net.caffeinemc.mods.sodium.client.gui.options.OptionImpl;
import net.caffeinemc.mods.sodium.client.gui.options.OptionPage;
import net.caffeinemc.mods.sodium.client.gui.options.control.ControlValueFormatter;
import net.caffeinemc.mods.sodium.client.gui.options.control.SliderControl;
import net.caffeinemc.mods.sodium.client.gui.options.control.TickBoxControl;
import net.caffeinemc.mods.sodium.client.gui.options.storage.OptionStorage;
import net.minecraft.network.chat.Component;
import toni.sodiumoptionsapi.api.OptionGUIConstruction;

import java.util.List;

/**
 * Sodium Video Settings integration for Voxy.
 * Uses SodiumOptionsAPI to add a Voxy page to Sodium's options menu.
 *
 * This provides a better UX than requiring users to find Voxy in the mod list.
 */
public class VoxySodiumOptions {

    /**
     * Register the Voxy options page with Sodium.
     * Call this during mod initialization.
     */
    public static void register() {
        OptionGUIConstruction.EVENT.register(VoxySodiumOptions::addVoxyPage);
    }

    private static void addVoxyPage(List<OptionPage> pages) {
        VoxyConfigStorage storage = new VoxyConfigStorage();

        pages.add(new OptionPage(
                Component.translatable("voxy.sodium.page.title"),
                ImmutableList.of(
                        createGeneralGroup(storage),
                        createPerformanceGroup(storage),
                        createAdvancedGroup(storage)
                )
        ));
    }

    private static OptionGroup createGeneralGroup(VoxyConfigStorage storage) {
        return OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName(Component.translatable("voxy.sodium.option.enabled"))
                        .setTooltip(Component.translatable("voxy.sodium.option.enabled.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding(
                                (config, value) -> config.enabled = value,
                                config -> config.enabled
                        )
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName(Component.translatable("voxy.sodium.option.enable_rendering"))
                        .setTooltip(Component.translatable("voxy.sodium.option.enable_rendering.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding(
                                (config, value) -> config.enableRendering = value,
                                config -> config.enableRendering
                        )
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName(Component.translatable("voxy.sodium.option.ingest_enabled"))
                        .setTooltip(Component.translatable("voxy.sodium.option.ingest_enabled.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding(
                                (config, value) -> config.ingestEnabled = value,
                                config -> config.ingestEnabled
                        )
                        .build())
                .build();
    }

    private static OptionGroup createPerformanceGroup(VoxyConfigStorage storage) {
        return OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(int.class, storage)
                        .setName(Component.translatable("voxy.sodium.option.render_distance"))
                        .setTooltip(Component.translatable("voxy.sodium.option.render_distance.tooltip"))
                        .setControl(opt -> new SliderControl(opt, 2, 64, 1,
                                v -> Component.literal(v + " (" + (v * 32) + " chunks)")))
                        .setBinding(
                                (config, value) -> config.sectionRenderDistance = value,
                                config -> config.sectionRenderDistance
                        )
                        .build())
                .add(OptionImpl.createBuilder(int.class, storage)
                        .setName(Component.translatable("voxy.sodium.option.service_threads"))
                        .setTooltip(Component.translatable("voxy.sodium.option.service_threads.tooltip"))
                        .setControl(opt -> new SliderControl(opt, 1, Runtime.getRuntime().availableProcessors(), 1,
                                ControlValueFormatter.number()))
                        .setBinding(
                                (config, value) -> config.serviceThreads = value,
                                config -> config.serviceThreads
                        )
                        .build())
                .add(OptionImpl.createBuilder(int.class, storage)
                        .setName(Component.translatable("voxy.sodium.option.subdivision_size"))
                        .setTooltip(Component.translatable("voxy.sodium.option.subdivision_size.tooltip"))
                        .setControl(opt -> new SliderControl(opt, 28, 256, 4,
                                ControlValueFormatter.number()))
                        .setBinding(
                                (config, value) -> config.subDivisionSize = value,
                                config -> (int) config.subDivisionSize
                        )
                        .build())
                .build();
    }

    private static OptionGroup createAdvancedGroup(VoxyConfigStorage storage) {
        return OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName(Component.translatable("voxy.sodium.option.environmental_fog"))
                        .setTooltip(Component.translatable("voxy.sodium.option.environmental_fog.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding(
                                (config, value) -> config.useEnvironmentalFog = value,
                                config -> config.useEnvironmentalFog
                        )
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName(Component.translatable("voxy.sodium.option.dont_use_sodium_threads"))
                        .setTooltip(Component.translatable("voxy.sodium.option.dont_use_sodium_threads.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding(
                                (config, value) -> config.dontUseSodiumBuilderThreads = value,
                                config -> config.dontUseSodiumBuilderThreads
                        )
                        .build())
                .add(OptionImpl.createBuilder(int.class, storage)
                        .setName(Component.translatable("voxy.sodium.option.lod_boundary_buffer"))
                        .setTooltip(Component.translatable("voxy.sodium.option.lod_boundary_buffer.tooltip"))
                        .setControl(opt -> new SliderControl(opt, 0, 4, 1,
                                v -> v == 0 ? Component.literal("Exact") : Component.literal(v + " blocks")))
                        .setBinding(
                                (config, value) -> config.lodBoundaryBuffer = value,
                                config -> config.lodBoundaryBuffer
                        )
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName(Component.translatable("voxy.sodium.option.render_statistics"))
                        .setTooltip(Component.translatable("voxy.sodium.option.render_statistics.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding(
                                (config, value) -> RenderStatistics.enabled = value,
                                config -> RenderStatistics.enabled
                        )
                        .build())
                .add(OptionImpl.createBuilder(int.class, storage)
                        .setName(Component.translatable("voxy.sodium.option.earth_curve_ratio"))
                        .setTooltip(Component.translatable("voxy.sodium.option.earth_curve_ratio.tooltip"))
                        .setControl(opt -> new SliderControl(opt, 0, 500, 10,
                                v -> v == 0 ? Component.literal("Disabled") :
                                     v < 50 ? Component.literal("→ 50 (min)") :
                                     Component.literal(v + "x")))
                        .setBinding(
                                (config, value) -> config.earthCurveRatio = value < 50 && value > 0 ? 50 : value,
                                config -> config.earthCurveRatio
                        )
                        .build())
                .build();
    }

    /**
     * Storage implementation that wraps VoxyConfig.CONFIG
     */
    private static class VoxyConfigStorage implements OptionStorage<VoxyConfig> {
        @Override
        public VoxyConfig getData() {
            return VoxyConfig.CONFIG;
        }

        @Override
        public void save() {
            VoxyConfig.CONFIG.save();
        }
    }
}
