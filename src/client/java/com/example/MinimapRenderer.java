package com.example;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

@Environment(EnvType.CLIENT)
public class MinimapRenderer {

    // Zoom levels: blocks visible across the full map width
    private static final int[] ZOOM_LEVELS = {64, 128, 256, 512, 1024};
    private int zoomIndex = 2; // default: 256 blocks across

    private static final int MAP_SIZE = 200;
    private static final int MARGIN = 10;
    private static final int SAMPLE_INTERVAL = 20; // ticks
    private static final int MAX_HISTORY = 3000;
    private static final long FADE_MS = 600_000; // 10 minutes

    private boolean visible = true;
    private int ticksSinceSample = 0;

    private final ArrayDeque<long[]> posHistory = new ArrayDeque<>(); // [timeMs, x, z]

    public void toggleVisible() { visible = !visible; }

    public void adjustZoom(boolean in) {
        zoomIndex = Math.max(0, Math.min(ZOOM_LEVELS.length - 1, zoomIndex + (in ? -1 : 1)));
    }

    public void tick(ClientPlayerEntity player) {
        if (++ticksSinceSample < SAMPLE_INTERVAL) return;
        ticksSinceSample = 0;
        posHistory.addLast(new long[]{
                System.currentTimeMillis(),
                player.getBlockX(),
                player.getBlockZ()
        });
        while (posHistory.size() > MAX_HISTORY) posHistory.removeFirst();
    }

    public void render(DrawContext ctx, MinecraftClient client) {
        if (!visible || client.player == null) return;

        int sw = client.getWindow().getScaledWidth();
        int ox = sw - MAP_SIZE - MARGIN;
        int oy = MARGIN;
        int cx = ox + MAP_SIZE / 2;
        int cy = oy + MAP_SIZE / 2;

        double px = client.player.getX();
        double pz = client.player.getZ();
        int viewRange = ZOOM_LEVELS[zoomIndex]; // blocks visible across map
        float scale = (float) MAP_SIZE / viewRange; // pixels per block

        // Background
        ctx.fill(ox - 2, oy - 2, ox + MAP_SIZE + 2, oy + MAP_SIZE + 2, 0xFF111111);
        ctx.fill(ox, oy, ox + MAP_SIZE, oy + MAP_SIZE, 0xC0050510);

        // Chunk grid
        int halfView = viewRange / 2;
        int chunkMin = ((int)(px - halfView)) >> 4;
        int chunkMax = ((int)(px + halfView)) >> 4;
        for (int chx = chunkMin; chx <= chunkMax; chx++) {
            int spx = cx + (int) ((chx * 16 - px) * scale);
            if (spx >= ox && spx <= ox + MAP_SIZE) {
                ctx.drawVerticalLine(spx, oy, oy + MAP_SIZE, 0x22FFFFFF);
            }
        }
        int chunkMinZ = ((int)(pz - halfView)) >> 4;
        int chunkMaxZ = ((int)(pz + halfView)) >> 4;
        for (int chz = chunkMinZ; chz <= chunkMaxZ; chz++) {
            int spz = cy + (int) ((chz * 16 - pz) * scale);
            if (spz >= oy && spz <= oy + MAP_SIZE) {
                ctx.drawHorizontalLine(ox, ox + MAP_SIZE, spz, 0x22FFFFFF);
            }
        }

        // Position history heat dots
        long now = System.currentTimeMillis();
        List<long[]> history = new ArrayList<>(posHistory);
        for (long[] sample : history) {
            int spx = cx + (int) ((sample[1] - px) * scale);
            int spz = cy + (int) ((sample[2] - pz) * scale);
            if (spx < ox || spx >= ox + MAP_SIZE || spz < oy || spz >= oy + MAP_SIZE) continue;

            float age = Math.min(1.0f, (float)(now - sample[0]) / FADE_MS);
            float intensity = 1.0f - age * 0.8f;
            if (intensity < 0.05f) continue;

            int color = heatColorClient(intensity);
            ctx.fill(spx - 1, spz - 1, spx + 1, spz + 1, color);
        }

        // Player dot (white) with direction arrow
        ctx.fill(cx - 2, cy - 2, cx + 2, cy + 2, 0xFFFFFFFF);

        float yaw = client.player.getYaw();
        double yawRad = Math.toRadians(yaw);
        int arrowLen = 6;
        int ax = (int) (-Math.sin(yawRad) * arrowLen);
        int az = (int) (Math.cos(yawRad) * arrowLen);
        ctx.fill(cx + ax - 1, cy + az - 1, cx + ax + 1, cy + az + 1, 0xFFFF4040);

        // Other online players
        if (client.world != null) {
            for (var entity : client.world.getPlayers()) {
                if (entity == client.player) continue;
                int epx = cx + (int) ((entity.getX() - px) * scale);
                int epz = cy + (int) ((entity.getZ() - pz) * scale);
                if (epx < ox || epx >= ox + MAP_SIZE || epz < oy || epz >= oy + MAP_SIZE) continue;
                ctx.fill(epx - 2, epz - 2, epx + 2, epz + 2, 0xFFFFFF00);
                ctx.drawText(client.textRenderer, entity.getName().getString().substring(0, Math.min(3, entity.getName().getString().length())),
                        epx + 3, epz - 3, 0xFFFFFF00, true);
            }
        }

        // Compass labels
        ctx.drawText(client.textRenderer, "N", ox + MAP_SIZE / 2 - 3, oy + 4, 0xFFFF5555, true);
        ctx.drawText(client.textRenderer, "S", ox + MAP_SIZE / 2 - 3, oy + MAP_SIZE - 12, 0xFFFFFFFF, true);
        ctx.drawText(client.textRenderer, "E", ox + MAP_SIZE - 10, oy + MAP_SIZE / 2 - 4, 0xFFFFFFFF, true);
        ctx.drawText(client.textRenderer, "W", ox + 3, oy + MAP_SIZE / 2 - 4, 0xFFFFFFFF, true);

        // Border (green)
        ctx.drawHorizontalLine(ox - 2, ox + MAP_SIZE + 1, oy - 2, 0xFF33FF33);
        ctx.drawHorizontalLine(ox - 2, ox + MAP_SIZE + 1, oy + MAP_SIZE + 1, 0xFF33FF33);
        ctx.drawVerticalLine(ox - 2, oy - 2, oy + MAP_SIZE + 1, 0xFF33FF33);
        ctx.drawVerticalLine(ox + MAP_SIZE + 1, oy - 2, oy + MAP_SIZE + 1, 0xFF33FF33);

        // Title
        ctx.drawText(client.textRenderer, "VEXOR", ox + 4, oy + 4, 0xFF33FF33, true);

        // Zoom indicator
        String zoomLabel = viewRange + "b";
        ctx.drawText(client.textRenderer, zoomLabel, ox + MAP_SIZE - 4 - client.textRenderer.getWidth(zoomLabel), oy + 4, 0xFF888888, true);

        // Scale bar
        int scaleBlocks = viewRange / 4;
        int scalePx = (int) (scaleBlocks * scale);
        int sbx = ox + 5;
        int sby = oy + MAP_SIZE - 14;
        ctx.drawHorizontalLine(sbx, sbx + scalePx, sby, 0xFFFFFFFF);
        ctx.drawVerticalLine(sbx, sby - 2, sby + 2, 0xFFFFFFFF);
        ctx.drawVerticalLine(sbx + scalePx, sby - 2, sby + 2, 0xFFFFFFFF);
        ctx.drawText(client.textRenderer, scaleBlocks + "b", sbx + scalePx / 2 - 8, sby - 10, 0xFFCCCCCC, false);

        // Coordinates and dimension below map
        String dim = client.world != null ? client.world.getRegistryKey().getValue().getPath() : "overworld";
        String coords = String.format("X:%d  Y:%d  Z:%d", client.player.getBlockX(), client.player.getBlockY(), client.player.getBlockZ());
        ctx.drawText(client.textRenderer, coords, ox, oy + MAP_SIZE + 4, 0xFFFFFFFF, true);
        ctx.drawText(client.textRenderer, dim, ox, oy + MAP_SIZE + 14, 0xFF888888, true);
        ctx.drawText(client.textRenderer, "[H] toggle  [+/-] zoom", ox, oy + MAP_SIZE + 24, 0xFF444444, false);
    }

    private static int heatColorClient(float t) {
        t = Math.max(0, Math.min(1, t));
        int a = (int) ((0.35f + t * 0.65f) * 255);
        int r, g, b;
        if (t < 0.25f) {
            float s = t / 0.25f;
            r = 0; g = (int)(s * 255); b = 255;
        } else if (t < 0.5f) {
            float s = (t - 0.25f) / 0.25f;
            r = 0; g = 255; b = (int)((1 - s) * 255);
        } else if (t < 0.75f) {
            float s = (t - 0.5f) / 0.25f;
            r = (int)(s * 255); g = 255; b = 0;
        } else {
            float s = (t - 0.75f) / 0.25f;
            r = 255; g = (int)((1 - s) * 255); b = 0;
        }
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
