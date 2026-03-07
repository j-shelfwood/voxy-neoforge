package me.cortex.voxy.client.hud;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

final class VoxyLoadingIndicatorRenderer {
    private static final int WIDTH = 136;
    private static final int HEIGHT = 18;
    private static final int MARGIN = 10;
    private static final int BLOCK_COUNT = 20;

    private static final int MODEL_COLOR = 0x62BEFF;
    private static final int MESH_COLOR = 0xE2B66B;
    private static final int NODE_COLOR = 0x7FD8C5;
    private static final int MAP_COLOR = 0xB1D4FF;

    public void render(GuiGraphics gui, VoxyLoadingIndicatorModel model) {
        if (!model.visible()) {
            return;
        }

        int x2 = gui.guiWidth() - MARGIN;
        int y2 = gui.guiHeight() - MARGIN;
        int x1 = x2 - WIDTH;
        int y1 = y2 - HEIGHT;

        int bg = color(0x0E1115, 0.62f * model.alpha);
        gui.fill(x1, y1, x2, y2, bg);
        gui.fill(x1 + 1, y1 + 1, x2 - 1, y2 - 1, color(0x131A22, 0.56f * model.alpha));

        int laneX = x1 + 8;
        int laneY = y1 + 7;
        int laneW = 84;
        renderLane(gui, laneX, laneY, laneW, MODEL_COLOR, queueLevel(model.modelQueue, 950f), model);
        renderLane(gui, laneX, laneY + 4, laneW, MESH_COLOR, queueLevel(model.meshQueue, 2200f), model);
        float nodeLevel = model.nodePending ? 1f : 0.08f + 0.06f * (1.0f - Math.abs(0.5f - model.pulse) * 2.0f);
        renderLane(gui, laneX, laneY + 8, laneW, NODE_COLOR, nodeLevel, model);

        renderMapCells(gui, x2 - 40, y1 + 3, model);
        renderLegendPins(gui, x1 + 3, laneY, model);

        if (model.nodePending) {
            gui.fill(x1 - 3, y1 + 2, x1 - 1, y2 - 2, color(NODE_COLOR, model.alpha));
        }
    }

    private static void renderLane(GuiGraphics gui, int x, int y, int width, int rgb, float level, VoxyLoadingIndicatorModel model) {
        gui.fill(x, y, x + width, y + 3, color(rgb, 0.16f * model.alpha));

        float clampedLevel = Mth.clamp(level, 0f, 1f);
        float highlightedBlocks = clampedLevel * BLOCK_COUNT;
        int blockWidth = Math.max(1, (width - (BLOCK_COUNT - 1)) / BLOCK_COUNT);
        int streamHead = (int) (model.pulse * BLOCK_COUNT);

        for (int i = 0; i < BLOCK_COUNT; i++) {
            int bx = x + i * (blockWidth + 1);
            boolean active = i < highlightedBlocks;
            float intensity = active ? 0.82f : 0.12f;
            if (model.mode == VoxyLoadingIndicatorModel.Mode.STREAMING && Math.abs(i - streamHead) <= 1) {
                intensity = Math.max(intensity, 0.95f);
            }
            if (model.mode == VoxyLoadingIndicatorModel.Mode.INITIAL_LOAD && i < model.progress * BLOCK_COUNT) {
                intensity = Math.max(intensity, 0.9f);
            }
            gui.fill(bx, y + 1, bx + blockWidth, y + 2, color(rgb, intensity * model.alpha));
        }
    }

    private static void renderMapCells(GuiGraphics gui, int x, int y, VoxyLoadingIndicatorModel model) {
        int cols = 9;
        int rows = 2;
        float sectionSignal = queueLevel(model.loadedSections, 1600f);
        float mapLevel = model.mode == VoxyLoadingIndicatorModel.Mode.INITIAL_LOAD
                ? (model.progress * 0.7f + sectionSignal * 0.3f)
                : sectionSignal;
        int activeCells = Math.round(Mth.clamp(mapLevel, 0f, 1f) * cols * rows);
        int pulseCell = (int) (model.pulse * cols) % cols;

        int cell = 3;
        int gap = 1;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                int idx = row * cols + col;
                int cx = x + col * (cell + gap);
                int cy = y + row * (cell + gap);
                boolean active = idx < activeCells;
                float alpha = active ? 0.85f : 0.12f;
                if (col == pulseCell && model.mode == VoxyLoadingIndicatorModel.Mode.STREAMING) {
                    alpha = Math.max(alpha, 0.95f);
                }
                gui.fill(cx, cy, cx + cell, cy + cell, color(MAP_COLOR, alpha * model.alpha));
            }
        }
    }

    private static void renderLegendPins(GuiGraphics gui, int x, int y, VoxyLoadingIndicatorModel model) {
        gui.fill(x, y + 1, x + 2, y + 2, color(MODEL_COLOR, model.alpha));
        gui.fill(x, y + 5, x + 2, y + 6, color(MESH_COLOR, model.alpha));
        gui.fill(x, y + 9, x + 2, y + 10, color(NODE_COLOR, model.alpha));
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
