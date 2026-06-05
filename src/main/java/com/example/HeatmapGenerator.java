package com.example;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;

public class HeatmapGenerator {

    static {
        System.setProperty("java.awt.headless", "true");
    }

    private static final int IMAGE_SIZE = 2048;

    /**
     * Auto-bounds: fits the image to all tracked data in the dimension.
     * This is the primary generation mode — no center or radius needed.
     */
    public static File generateAuto(Map<Long, Integer> heatData, String dimension,
                                    File outputDir) throws IOException {
        if (heatData.isEmpty()) return null;

        // Find data extent
        int minCX = Integer.MAX_VALUE, maxCX = Integer.MIN_VALUE;
        int minCZ = Integer.MAX_VALUE, maxCZ = Integer.MIN_VALUE;
        for (long key : heatData.keySet()) {
            int cx = PlayerTracker.unpackX(key);
            int cz = PlayerTracker.unpackZ(key);
            if (cx < minCX) minCX = cx;
            if (cx > maxCX) maxCX = cx;
            if (cz < minCZ) minCZ = cz;
            if (cz > maxCZ) maxCZ = cz;
        }

        // Symmetric padding: 8% on each side, at least 20 cells
        int padX = Math.max(20, (maxCX - minCX) / 12);
        int padZ = Math.max(20, (maxCZ - minCZ) / 12);
        minCX -= padX; maxCX += padX;
        minCZ -= padZ; maxCZ += padZ;

        return render(heatData, dimension, minCX, maxCX, minCZ, maxCZ, IMAGE_SIZE, outputDir, true);
    }

    /**
     * Fixed-bounds: centered on a world coordinate with a block radius.
     */
    public static File generateFixed(Map<Long, Integer> heatData, String dimension,
                                     int centerBlockX, int centerBlockZ, int blockRadius,
                                     File outputDir) throws IOException {
        if (heatData.isEmpty()) return null;

        int cellRadius = Math.max(1, blockRadius >> PlayerTracker.CELL_BITS);
        int centerCX = centerBlockX >> PlayerTracker.CELL_BITS;
        int centerCZ = centerBlockZ >> PlayerTracker.CELL_BITS;

        int minCX = centerCX - cellRadius;
        int maxCX = centerCX + cellRadius;
        int minCZ = centerCZ - cellRadius;
        int maxCZ = centerCZ + cellRadius;

        return render(heatData, dimension, minCX, maxCX, minCZ, maxCZ, 1024, outputDir, false);
    }

    private static File render(Map<Long, Integer> heatData, String dimension,
                                int minCX, int maxCX, int minCZ, int maxCZ,
                                int imageSize, File outputDir, boolean autoMode) throws IOException {
        int gridW = maxCX - minCX + 1;
        int gridH = maxCZ - minCZ + 1;

        // Scatter cells into a float pixel accumulation buffer
        float[] pixels = new float[imageSize * imageSize];

        float scaleX = (float) imageSize / gridW;
        float scaleZ = (float) imageSize / gridH;

        for (Map.Entry<Long, Integer> entry : heatData.entrySet()) {
            int cx = PlayerTracker.unpackX(entry.getKey());
            int cz = PlayerTracker.unpackZ(entry.getKey());
            if (cx < minCX || cx > maxCX || cz < minCZ || cz > maxCZ) continue;

            int px = (int) ((cx - minCX) * scaleX);
            int pz = (int) ((cz - minCZ) * scaleZ);

            // Clamp to image bounds
            px = Math.max(0, Math.min(imageSize - 1, px));
            pz = Math.max(0, Math.min(imageSize - 1, pz));

            // Use log of count so even rare visits are visible
            pixels[pz * imageSize + px] += (float) Math.log1p(entry.getValue());
        }

        // Tight Gaussian blur — radius 1 keeps paths crisp
        if (autoMode) {
            pixels = gaussianBlur(pixels, imageSize, imageSize, 1);
        } else {
            pixels = gaussianBlur(pixels, imageSize, imageSize, 2);
        }

        // Find max for normalization
        float maxVal = 0;
        for (float v : pixels) if (v > maxVal) maxVal = v;
        if (maxVal == 0) return null;

        // Render
        BufferedImage img = new BufferedImage(imageSize, imageSize, BufferedImage.TYPE_INT_ARGB);

        // Pure black background
        Graphics2D bg = img.createGraphics();
        bg.setColor(Color.BLACK);
        bg.fillRect(0, 0, imageSize, imageSize);
        bg.dispose();

        for (int pz = 0; pz < imageSize; pz++) {
            for (int px = 0; px < imageSize; px++) {
                float v = pixels[pz * imageSize + px];
                if (v < 0.001f) continue;
                float t = v / maxVal;
                img.setRGB(px, pz, thermalColor(t));
            }
        }

        // Overlays
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Axis lines through origin (0,0) if visible
        int originCX = -(minCX);
        int originCZ = -(minCZ);
        if (originCX >= 0 && originCX < gridW && originCZ >= 0 && originCZ < gridH) {
            int opx = (int) (originCX * scaleX);
            int opz = (int) (originCZ * scaleZ);
            g.setColor(new Color(255, 255, 255, 35));
            g.drawLine(opx, 0, opx, imageSize);
            g.drawLine(0, opz, imageSize, opz);
        }

        // Corner coordinates in block space
        int blockScale = PlayerTracker.CELL_SIZE;
        int blMinX = minCX * blockScale, blMinZ = minCZ * blockScale;
        int blMaxX = maxCX * blockScale, blMaxZ = maxCZ * blockScale;

        // HUD bar
        g.setColor(new Color(0, 0, 0, 180));
        g.fillRect(0, 0, imageSize, 28);
        g.fillRect(0, imageSize - 22, imageSize, 22);

        g.setFont(new Font("Monospaced", Font.BOLD, 15));
        g.setColor(new Color(255, 80, 80));
        g.drawString("VEXOR", 8, 19);
        g.setColor(Color.WHITE);
        String dimShort = dimension.replace("minecraft:", "").toUpperCase();
        String header = "  |  " + dimShort + "  |  " + (autoMode ? "FULL MAP" : "FIXED VIEW");
        g.drawString(header, 65, 19);
        g.setFont(new Font("Monospaced", Font.PLAIN, 12));
        g.setColor(new Color(180, 180, 180));
        g.drawString("[" + blMinX + ", " + blMinZ + "]  →  [" + blMaxX + ", " + blMaxZ + "]"
                + "    " + new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date())
                + "    " + heatData.size() + " cells", 6, imageSize - 6);

        // Legend bar (right edge)
        int barH = imageSize / 3;
        int barY = imageSize / 2 - barH / 2;
        int barX = imageSize - 22;
        for (int i = 0; i < barH; i++) {
            float t = 1.0f - (float) i / barH;
            g.setColor(new Color(thermalColor(t), true));
            g.fillRect(barX, barY + i, 12, 1);
        }
        g.setColor(Color.WHITE);
        g.setFont(new Font("Monospaced", Font.PLAIN, 11));
        g.drawString("▲", barX, barY - 2);
        g.drawString("▼", barX, barY + barH + 12);

        // Compass
        int mid = imageSize / 2;
        g.setFont(new Font("Monospaced", Font.BOLD, 13));
        g.setColor(new Color(255, 80, 80, 180));
        g.drawString("N", mid - 5, 46);
        g.setColor(new Color(200, 200, 200, 120));
        g.drawString("S", mid - 5, imageSize - 28);
        g.drawString("E", imageSize - 44, mid + 5);
        g.drawString("W", 30, mid + 5);

        g.dispose();

        outputDir.mkdirs();
        String dimTag = dimension.replace("minecraft:", "").replace(":", "_");
        String mode = autoMode ? "full" : "view";
        String name = "vexor_" + dimTag + "_" + mode + "_" + System.currentTimeMillis() + ".png";
        File out = new File(outputDir, name);
        ImageIO.write(img, "PNG", out);
        return out;
    }

    private static float[] gaussianBlur(float[] data, int width, int height, int radius) {
        if (radius < 1) return data;
        float[] temp = new float[data.length];
        float[] result = new float[data.length];
        double sigma2 = 2.0 * radius * radius;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float sum = 0, weight = 0;
                for (int dx = -radius; dx <= radius; dx++) {
                    int nx = x + dx;
                    if (nx >= 0 && nx < width) {
                        float w = (float) Math.exp(-(dx * dx) / sigma2);
                        sum += data[y * width + nx] * w;
                        weight += w;
                    }
                }
                temp[y * width + x] = weight > 0 ? sum / weight : 0;
            }
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float sum = 0, weight = 0;
                for (int dy = -radius; dy <= radius; dy++) {
                    int ny = y + dy;
                    if (ny >= 0 && ny < height) {
                        float w = (float) Math.exp(-(dy * dy) / sigma2);
                        sum += temp[ny * width + x] * w;
                        weight += w;
                    }
                }
                result[y * width + x] = weight > 0 ? sum / weight : 0;
            }
        }
        return result;
    }

    /**
     * Thermal / "nocom-style" colormap:
     *   0.00 → black
     *   0.20 → deep purple
     *   0.40 → bright pink / magenta
     *   0.60 → red-orange
     *   0.80 → orange-yellow
     *   1.00 → white
     */
    public static int thermalColor(float t) {
        if (t <= 0) return 0xFF000000;
        t = Math.min(1, t);

        // Alpha: ramp up quickly so even dim traces are visible
        int a = Math.min(255, (int) (t * 340));

        float[] stops = {0f, 0.22f, 0.42f, 0.62f, 0.80f, 1.0f};
        int[][] colors = {
            {0,   0,   0  },  // black
            {55,  0,   90 },  // deep purple
            {210, 10,  130},  // magenta/pink
            {255, 55,  0  },  // red-orange
            {255, 200, 0  },  // orange-yellow
            {255, 255, 255},  // white
        };

        for (int i = 0; i < stops.length - 1; i++) {
            if (t <= stops[i + 1]) {
                float s = (t - stops[i]) / (stops[i + 1] - stops[i]);
                int r = (int) (colors[i][0] + s * (colors[i + 1][0] - colors[i][0]));
                int g = (int) (colors[i][1] + s * (colors[i + 1][1] - colors[i][1]));
                int b = (int) (colors[i][2] + s * (colors[i + 1][2] - colors[i][2]));
                return (a << 24) | (r << 16) | (g << 8) | b;
            }
        }
        return (a << 24) | (255 << 16) | (255 << 8) | 255;
    }
}
