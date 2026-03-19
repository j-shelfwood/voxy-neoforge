package me.cortex.voxy.client.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

final class VoxyLoadingIndicatorRenderer {
    // Animation radius is 21px; pad 8px each side → 58×58 box
    private static final int ANIM_RADIUS = 21;
    private static final int PADDING = 8;
    private static final int SIZE = (ANIM_RADIUS + PADDING) * 2;
    private static final int MARGIN = 10;

    private static final int MODEL_COLOR = 0x69BCFF;
    private static final int MESH_COLOR = 0xF2B467;
    private static final int NODE_COLOR = 0x7FE0CA;
    private static final int SWEEP_COLOR = 0xC7F1FF;
    private static final int RESIDENCY_COLOR = 0xAFCBFF;

    public void render(GuiGraphics gui, VoxyLoadingIndicatorModel model) {
        if (!model.visible()) {
            return;
        }

        int x2 = gui.guiWidth() - MARGIN;
        int y2 = gui.guiHeight() - MARGIN;
        int x1 = x2 - SIZE;
        int y1 = y2 - SIZE;

        // No background card — animation only
        int cx = x1 + ANIM_RADIUS + PADDING;
        int cy = y1 + ANIM_RADIUS + PADDING;
        float meshLevel = queueLevel(model.meshQueue, 2200f);
        float modelLevel = queueLevel(model.modelQueue, 950f);
        float nodeLevel = model.nodePending ? 1f : (0.12f + 0.07f * (1.0f - Math.abs(0.5f - model.pulse) * 2.0f));
        float residencyLevel = queueLevel(model.loadedSections, 45000f);
        float sweep = model.pulse * 6.2831855f;

        // Backdrop rings to anchor the scanner.
        drawRing(gui, cx, cy, 20, 1, color(0x1C2A35, 0.60f * model.alpha));
        drawRing(gui, cx, cy, 15, 1, color(0x1C2A35, 0.52f * model.alpha));
        drawRing(gui, cx, cy, 10, 1, color(0x1C2A35, 0.45f * model.alpha));

        // Three distinct compute queues as concentric activity arcs.
        drawWorkArc(gui, cx, cy, 20, 2, sweep + 0.60f, meshLevel, MESH_COLOR, model.alpha);
        drawWorkArc(gui, cx, cy, 15, 2, sweep + 2.35f, modelLevel, MODEL_COLOR, model.alpha);
        drawWorkArc(gui, cx, cy, 10, 2, sweep + 4.25f, nodeLevel, NODE_COLOR, model.alpha);

        // Sweeping beam communicates active traversal/scanning.
        drawSweep(gui, cx, cy, 21, sweep, model.alpha);

        // Text rendering removed - animation only
        // renderModeText(gui, x1 + 8, y1 + 7, model);
        // renderMetricLegend(gui, x1 + 68, y1 + 21, model, meshLevel, modelLevel, nodeLevel, residencyLevel);
        // renderScannerLegend(gui, x1 + 8, y1 + 73, model);
    }

    private static void drawWorkArc(GuiGraphics gui, int cx, int cy, int radius, int thickness, float phase, float level, int rgb, float alpha) {
        float clamped = Mth.clamp(level, 0f, 1f);
        float span = 0.55f + clamped * 5.4f;
        int samples = 120;
        for (int i = 0; i < samples; i++) {
            float t = i / (float) (samples - 1);
            float angle = phase + t * span;
            float amp = 0.25f + 0.75f * t;
            int px = Math.round(cx + (float) Math.cos(angle) * radius);
            int py = Math.round(cy + (float) Math.sin(angle) * radius);
            int c = color(rgb, alpha * (0.18f + amp * 0.78f));
            gui.fill(px, py, px + thickness, py + thickness, c);
        }
    }

    private static void drawSweep(GuiGraphics gui, int cx, int cy, int radius, float angle, float alpha) {
        int beamSteps = 18;
        for (int i = 0; i < beamSteps; i++) {
            float d = i / (float) (beamSteps - 1);
            float r = radius * d;
            int px = Math.round(cx + (float) Math.cos(angle) * r);
            int py = Math.round(cy + (float) Math.sin(angle) * r);
            float trail = (1.0f - d);
            gui.fill(px, py, px + 1, py + 1, color(SWEEP_COLOR, alpha * trail * 0.78f));
        }
    }

    private static void renderMetricLegend(GuiGraphics gui, int x, int y, VoxyLoadingIndicatorModel model, float mesh, float baked, float node, float residency) {
        drawMetricLane(gui, x, y, "MESH BUILD", MESH_COLOR, mesh, model.meshQueue, model.alpha);
        drawMetricLane(gui, x, y + 12, "MODEL BAKE", MODEL_COLOR, baked, model.modelQueue, model.alpha);
        drawMetricLane(gui, x, y + 24, "NODE FETCH", NODE_COLOR, node, model.nodePending ? 1 : 0, model.alpha);
        drawMetricLane(gui, x, y + 36, "RESIDENCY", RESIDENCY_COLOR, residency, model.loadedSections, model.alpha);
    }

    private static void drawMetricLane(GuiGraphics gui, int x, int y, String label, int rgb, float level, int value, float alpha) {
        int textColor = color(0xA9BFCE, alpha * 0.9f);
        drawScaledText(gui, label, x, y, textColor, 0.78f);

        int barX = x + 54;
        int barY = y + 2;
        int barW = 28;
        int barH = 5;
        gui.fill(barX, barY, barX + barW, barY + barH, color(0x1A2733, alpha * 0.66f));
        int lit = Math.round(Mth.clamp(level, 0f, 1f) * barW);
        if (lit > 0) {
            gui.fill(barX, barY, barX + lit, barY + barH, color(rgb, alpha * 0.92f));
        }
        drawScaledText(gui, Integer.toString(value), x + 86, y, color(0xD9E7F7, alpha * 0.9f), 0.74f);
    }

    private static void drawRing(GuiGraphics gui, int cx, int cy, int radius, int thickness, int color) {
        int samples = 192;
        for (int i = 0; i < samples; i++) {
            float angle = i * (6.2831855f / samples);
            int px = Math.round(cx + (float) Math.cos(angle) * radius);
            int py = Math.round(cy + (float) Math.sin(angle) * radius);
            gui.fill(px, py, px + thickness, py + thickness, color);
        }
    }

    private static void renderModeText(GuiGraphics gui, int x, int y, VoxyLoadingIndicatorModel model) {
        String state = model.mode == VoxyLoadingIndicatorModel.Mode.INITIAL_LOAD ? "VOXY BOOTSTRAP" :
                (model.mode == VoxyLoadingIndicatorModel.Mode.STREAMING ? "VOXY STREAMING" : "VOXY READY");
        int textColor = color(0xD2E5F9, model.alpha * 0.88f);
        drawScaledText(gui, state, x, y, textColor, 0.84f);

        if (model.mode == VoxyLoadingIndicatorModel.Mode.INITIAL_LOAD) {
            int p = Math.round(model.progress * 100f);
            drawScaledText(gui, p + "%", x + 1, y + 9, color(0x8FC7FF, model.alpha * 0.86f), 0.78f);
        }
    }

    private static void renderScannerLegend(GuiGraphics gui, int x, int y, VoxyLoadingIndicatorModel model) {
        drawScaledText(gui, "OUTER=MESH  MID=MODEL  INNER=NODE", x, y, color(0x91A8BA, model.alpha * 0.80f), 0.68f);
    }

    private static void drawScaledText(GuiGraphics gui, String text, int x, int y, int color, float scale) {
        gui.pose().pushPose();
        gui.pose().scale(scale, scale, 1f);
        int sx = Math.round(x / scale);
        int sy = Math.round(y / scale);
        gui.drawString(Minecraft.getInstance().font, text, sx, sy, color, false);
        gui.pose().popPose();
    }

    private static float queueLevel(int queueSize, float halfSaturation) {
        if (queueSize <= 0) {
            return 0f;
        }
        return (float) (1.0 - Math.exp(-queueSize / halfSaturation));
    }

    private static int color(int rgb, float alpha) {
        int a = Mth.clamp(Math.round(alpha * 255f), 0, 255);
        return (a << 24) | (rgb & 0xFFFFFF);
    }
}
