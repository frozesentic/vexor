package com.example;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Map;

public class HeatmapGenerator {

    static {
        System.setProperty("java.awt.headless", "true");
    }

    private static final int IMAGE_SIZE = 2048;

    public static File generateAuto(Map<Long, Integer> heatData, String dimension,
                                    File outputDir) throws IOException {
        if (heatData.isEmpty()) return null;

        int n = heatData.size();
        int[] cxArr = new int[n];
        int[] czArr = new int[n];
        int i = 0;
        for (long key : heatData.keySet()) {
            cxArr[i] = PlayerTracker.unpackX(key);
            czArr[i] = PlayerTracker.unpackZ(key);
            i++;
        }
        Arrays.sort(cxArr);
        Arrays.sort(czArr);

        // Clip 2% from each tail so outlier cells far from the main cluster
        // don't inflate the bounding box. render() filters them out anyway.
        int clip = n / 50;
        int minCX = cxArr[clip], maxCX = cxArr[n - 1 - clip];
        int minCZ = czArr[clip], maxCZ = czArr[n - 1 - clip];

        int padX = Math.max(20, (maxCX - minCX) / 12);
        int padZ = Math.max(20, (maxCZ - minCZ) / 12);
        minCX -= padX; maxCX += padX;
        minCZ -= padZ; maxCZ += padZ;

        return render(heatData, dimension, minCX, maxCX, minCZ, maxCZ, IMAGE_SIZE, outputDir, true, null);
    }

    public static File generateAutoForPlayer(Map<Long, Integer> heatData, String playerName,
                                             String dimension, File outputDir) throws IOException {
        if (heatData.isEmpty()) return null;

        int n = heatData.size();
        int[] cxArr = new int[n];
        int[] czArr = new int[n];
        int i = 0;
        for (long key : heatData.keySet()) {
            cxArr[i] = PlayerTracker.unpackX(key);
            czArr[i] = PlayerTracker.unpackZ(key);
            i++;
        }
        Arrays.sort(cxArr);
        Arrays.sort(czArr);

        int clip = n / 50;
        int minCX = cxArr[clip], maxCX = cxArr[n - 1 - clip];
        int minCZ = czArr[clip], maxCZ = czArr[n - 1 - clip];

        int padX = Math.max(20, (maxCX - minCX) / 12);
        int padZ = Math.max(20, (maxCZ - minCZ) / 12);
        minCX -= padX; maxCX += padX;
        minCZ -= padZ; maxCZ += padZ;

        return render(heatData, dimension, minCX, maxCX, minCZ, maxCZ, IMAGE_SIZE, outputDir, true, playerName);
    }

    public static File generateFixed(Map<Long, Integer> heatData, String dimension,
                                     int centerBlockX, int centerBlockZ, int blockRadius,
                                     File outputDir) throws IOException {
        if (heatData.isEmpty()) return null;

        int cellRadius = Math.max(1, blockRadius >> PlayerTracker.CELL_BITS);
        int centerCX = centerBlockX >> PlayerTracker.CELL_BITS;
        int centerCZ = centerBlockZ >> PlayerTracker.CELL_BITS;

        return render(heatData, dimension,
                centerCX - cellRadius, centerCX + cellRadius,
                centerCZ - cellRadius, centerCZ + cellRadius,
                1024, outputDir, false, null);
    }

    public static File generateFixedForPlayer(Map<Long, Integer> heatData, String playerName,
                                              String dimension, int centerBlockX, int centerBlockZ,
                                              int blockRadius, File outputDir) throws IOException {
        if (heatData.isEmpty()) return null;

        int cellRadius = Math.max(1, blockRadius >> PlayerTracker.CELL_BITS);
        int centerCX = centerBlockX >> PlayerTracker.CELL_BITS;
        int centerCZ = centerBlockZ >> PlayerTracker.CELL_BITS;

        return render(heatData, dimension,
                centerCX - cellRadius, centerCX + cellRadius,
                centerCZ - cellRadius, centerCZ + cellRadius,
                1024, outputDir, false, playerName);
    }

    private static File render(Map<Long, Integer> heatData, String dimension,
                                int minCX, int maxCX, int minCZ, int maxCZ,
                                int imageSize, File outputDir, boolean autoMode,
                                String playerName) throws IOException {
        long gridWL = (long) maxCX - minCX + 1;
        long gridHL = (long) maxCZ - minCZ + 1;

        // Subsample when the explored extent is larger than the image resolution:
        // more cells than pixels add no detail, and avoids int overflow in index math.
        int step = (int) Math.max(1L, Math.max(gridWL / imageSize, gridHL / imageSize));
        int gridW = (int) ((gridWL + step - 1) / step);
        int gridH = (int) ((gridHL + step - 1) / step);

        // Step 1: scatter visit counts into a cell-resolution float buffer.
        // Working at cell resolution means adjacent Bresenham cells are exactly
        // 1 unit apart here, so a blur radius of 1 connects them properly —
        // unlike scattering into the full image where adjacent cells are
        // (imageSize/gridW) pixels apart and a radius-1 blur can't bridge them.
        float[] cells = new float[gridW * gridH];
        for (Map.Entry<Long, Integer> entry : heatData.entrySet()) {
            int cx = PlayerTracker.unpackX(entry.getKey());
            int cz = PlayerTracker.unpackZ(entry.getKey());
            if (cx < minCX || cx > maxCX || cz < minCZ || cz > maxCZ) continue;
            int gx = (int) (((long) cx - minCX) / step);
            int gz = (int) (((long) cz - minCZ) / step);
            cells[gz * gridW + gx] += (float) Math.log1p(entry.getValue());
        }

        // Step 2: Gaussian blur at cell resolution.
        // Radius 1 at cell level = thin crisp paths; radius 2 = slightly softer.
        cells = gaussianBlur(cells, gridW, gridH, autoMode ? 1 : 2);

        // Step 3: normalize to [0, 1]
        float maxVal = 0;
        for (float v : cells) if (v > maxVal) maxVal = v;
        if (maxVal == 0) return null;
        for (int i = 0; i < cells.length; i++) cells[i] /= maxVal;

        // Step 4: bilinear upscale to full image size + apply thermal colormap.
        // Bilinear interpolation smoothly connects adjacent cells so paths
        // appear as continuous lines rather than disconnected dots.
        BufferedImage img = new BufferedImage(imageSize, imageSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D bg = img.createGraphics();
        bg.setColor(Color.BLACK);
        bg.fillRect(0, 0, imageSize, imageSize);
        bg.dispose();

        for (int py = 0; py < imageSize; py++) {
            float fz = (py + 0.5f) * gridH / imageSize;
            int z0 = Math.max(0, Math.min(gridH - 1, (int) fz));
            int z1 = Math.min(gridH - 1, z0 + 1);
            float wz = fz - z0;
            for (int px = 0; px < imageSize; px++) {
                float fx = (px + 0.5f) * gridW / imageSize;
                int x0 = Math.max(0, Math.min(gridW - 1, (int) fx));
                int x1 = Math.min(gridW - 1, x0 + 1);
                float wx = fx - x0;
                float v = cells[z0 * gridW + x0] * (1 - wx) * (1 - wz)
                        + cells[z0 * gridW + x1] * wx       * (1 - wz)
                        + cells[z1 * gridW + x0] * (1 - wx) * wz
                        + cells[z1 * gridW + x1] * wx       * wz;
                if (v < 0.003f) continue;
                img.setRGB(px, py, thermalColor(v));
            }
        }

        // Overlays
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Faint origin axis lines
        int originOffX = -minCX;
        int originOffZ = -minCZ;
        if (originOffX >= 0 && originOffX < gridW && originOffZ >= 0 && originOffZ < gridH) {
            int opx = (int) (originOffX * (float) imageSize / gridW);
            int opz = (int) (originOffZ * (float) imageSize / gridH);
            g.setColor(new Color(255, 255, 255, 35));
            g.drawLine(opx, 0, opx, imageSize);
            g.drawLine(0, opz, imageSize, opz);
        }

        int blockScale = PlayerTracker.CELL_SIZE;
        int blMinX = minCX * blockScale, blMinZ = minCZ * blockScale;
        int blMaxX = maxCX * blockScale, blMaxZ = maxCZ * blockScale;

        // HUD bars
        g.setColor(new Color(0, 0, 0, 180));
        g.fillRect(0, 0, imageSize, 28);
        g.fillRect(0, imageSize - 22, imageSize, 22);

        g.setFont(new Font("Monospaced", Font.BOLD, 15));
        g.setColor(new Color(255, 80, 80));
        g.drawString("VEXOR", 8, 19);
        g.setColor(Color.WHITE);
        String dimShort = dimension.replace("minecraft:", "").toUpperCase();
        String playerTag = playerName != null ? "  |  " + playerName : "";
        g.drawString("  |  " + dimShort + playerTag + "  |  " + (autoMode ? "FULL MAP" : "FIXED VIEW"), 65, 19);
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
        String playerSuffix = playerName != null ? "_" + playerName.toLowerCase() : "";
        String name = "vexor_" + dimTag + playerSuffix + "_" + mode + "_" + System.currentTimeMillis() + ".png";
        File out = new File(outputDir, name);
        ImageIO.write(img, "PNG", out);
        return out;
    }

    private static float[] gaussianBlur(float[] data, int width, int height, int radius) {
        if (radius < 1) return data;
        float[] temp = new float[data.length];
        float[] result = new float[data.length];
        double sigma2 = 2.0 * radius * radius;

        // Horizontal pass
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
        // Vertical pass
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

    public static int thermalColor(float t) {
        if (t <= 0) return 0xFF000000;
        t = Math.min(1, t);

        float[] stops  = {0f,   0.22f, 0.42f, 0.62f, 0.80f, 1.0f};
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
                return 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        return 0xFFFFFFFF;
    }
}
