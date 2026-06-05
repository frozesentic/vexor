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

    public static File generate(Map<Long, Integer> heatData, String dimension,
                                int centerX, int centerZ, int radius,
                                File outputDir) throws IOException {
        if (heatData.isEmpty()) return null;

        int imageSize = 1024;
        int chunkRadius = Math.max(1, radius / 16);

        int minCX = (centerX >> 4) - chunkRadius;
        int maxCX = (centerX >> 4) + chunkRadius;
        int minCZ = (centerZ >> 4) - chunkRadius;
        int maxCZ = (centerZ >> 4) + chunkRadius;

        int width = maxCX - minCX + 1;
        int height = maxCZ - minCZ + 1;

        float[] grid = new float[width * height];
        int maxVal = 0;

        for (Map.Entry<Long, Integer> entry : heatData.entrySet()) {
            int cx = PlayerTracker.unpackX(entry.getKey());
            int cz = PlayerTracker.unpackZ(entry.getKey());
            if (cx >= minCX && cx <= maxCX && cz >= minCZ && cz <= maxCZ) {
                int idx = (cz - minCZ) * width + (cx - minCX);
                grid[idx] = entry.getValue();
                if (entry.getValue() > maxVal) maxVal = entry.getValue();
            }
        }

        if (maxVal == 0) return null;

        grid = gaussianBlur(grid, width, height, 3);

        float max = 0;
        for (float v : grid) if (v > max) max = v;

        BufferedImage img = new BufferedImage(imageSize, imageSize, BufferedImage.TYPE_INT_ARGB);

        // Black background
        Graphics2D bg = img.createGraphics();
        bg.setColor(Color.BLACK);
        bg.fillRect(0, 0, imageSize, imageSize);
        bg.dispose();

        float scaleX = (float) imageSize / width;
        float scaleY = (float) imageSize / height;

        for (int gz = 0; gz < height; gz++) {
            for (int gx = 0; gx < width; gx++) {
                float intensity = max > 0 ? grid[gz * width + gx] / max : 0;
                if (intensity < 0.01f) continue;

                int color = heatColor(intensity);
                int px = (int) (gx * scaleX);
                int pz = (int) (gz * scaleY);
                int pw = Math.max(1, (int) Math.ceil(scaleX));
                int ph = Math.max(1, (int) Math.ceil(scaleY));

                for (int dy = 0; dy < ph && pz + dy < imageSize; dy++) {
                    for (int dx = 0; dx < pw && px + dx < imageSize; dx++) {
                        int ex = px + dx;
                        int ez = pz + dy;
                        // Blend over black background
                        int existing = img.getRGB(ex, ez);
                        img.setRGB(ex, ez, blendOver(existing, color));
                    }
                }
            }
        }

        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Chunk grid overlay (only when not too zoomed out)
        if (scaleX > 3) {
            g.setColor(new Color(255, 255, 255, 18));
            for (int gx = 0; gx <= width; gx++) {
                int px = (int) (gx * scaleX);
                g.drawLine(px, 0, px, imageSize);
            }
            for (int gz = 0; gz <= height; gz++) {
                int pz = (int) (gz * scaleY);
                g.drawLine(0, pz, imageSize, pz);
            }
        }

        // Cardinal axis lines through center
        int cpx = (int) (((centerX >> 4) - minCX) * scaleX);
        int cpz = (int) (((centerZ >> 4) - minCZ) * scaleY);
        g.setColor(new Color(255, 255, 255, 60));
        g.drawLine(cpx, 0, cpx, imageSize);
        g.drawLine(0, cpz, imageSize, cpz);

        // Center crosshair
        g.setColor(new Color(255, 255, 255, 200));
        g.drawLine(cpx - 12, cpz, cpx + 12, cpz);
        g.drawLine(cpx, cpz - 12, cpx, cpz + 12);

        // Legend gradient bar
        int barX = imageSize - 30;
        int barY = 50;
        int barH = imageSize - 100;
        for (int i = 0; i < barH; i++) {
            float t = 1.0f - (float) i / barH;
            g.setColor(new Color(heatColor(t), true));
            g.fillRect(barX, barY + i, 15, 1);
        }
        g.setColor(Color.WHITE);
        g.setFont(new Font("Monospaced", Font.BOLD, 11));
        g.drawString("MAX", barX - 5, barY - 3);
        g.drawString("MIN", barX - 5, barY + barH + 12);

        // Info overlay
        g.setFont(new Font("Monospaced", Font.BOLD, 13));
        String dimShort = dimension.replace("minecraft:", "").toUpperCase();
        String dateStr = new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date());
        g.setColor(new Color(0, 0, 0, 160));
        g.fillRect(0, 0, imageSize, 22);
        g.fillRect(0, imageSize - 22, imageSize, 22);
        g.setColor(Color.WHITE);
        g.drawString("VEXOR  |  " + dimShort + "  |  Center: " + centerX + ", " + centerZ + "  |  Radius: " + radius + "b", 6, 15);
        g.drawString(dateStr + "  |  " + heatData.size() + " tracked chunks", 6, imageSize - 7);

        // Compass
        g.setFont(new Font("Monospaced", Font.BOLD, 12));
        g.setColor(new Color(255, 80, 80));
        g.drawString("N", imageSize / 2 - 4, 35);
        g.setColor(Color.WHITE);
        g.drawString("S", imageSize / 2 - 4, imageSize - 25);
        g.drawString("E", imageSize - 50, imageSize / 2 + 4);
        g.drawString("W", 38, imageSize / 2 + 4);

        g.dispose();

        outputDir.mkdirs();
        String dimTag = dimension.replace("minecraft:", "").replace(":", "_");
        String fileName = "vexor_" + dimTag + "_" + centerX + "_" + centerZ + "_"
                + System.currentTimeMillis() + ".png";
        File out = new File(outputDir, fileName);
        ImageIO.write(img, "PNG", out);
        return out;
    }

    private static int blendOver(int dst, int src) {
        int sA = (src >> 24) & 0xFF;
        if (sA == 0) return dst;
        if (sA == 255) return src;
        float alpha = sA / 255.0f;
        int sR = (src >> 16) & 0xFF;
        int sG = (src >> 8) & 0xFF;
        int sB = src & 0xFF;
        int dR = (dst >> 16) & 0xFF;
        int dG = (dst >> 8) & 0xFF;
        int dB = dst & 0xFF;
        int r = (int) (sR * alpha + dR * (1 - alpha));
        int g = (int) (sG * alpha + dG * (1 - alpha));
        int b = (int) (sB * alpha + dB * (1 - alpha));
        return (0xFF << 24) | (r << 16) | (g << 8) | b;
    }

    private static float[] gaussianBlur(float[] data, int width, int height, int radius) {
        float[] temp = new float[data.length];
        float[] result = new float[data.length];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float sum = 0, weight = 0;
                for (int dx = -radius; dx <= radius; dx++) {
                    int nx = x + dx;
                    if (nx >= 0 && nx < width) {
                        float w = (float) Math.exp(-(dx * dx) / (2.0 * radius * radius));
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
                        float w = (float) Math.exp(-(dy * dy) / (2.0 * radius * radius));
                        sum += temp[ny * width + x] * w;
                        weight += w;
                    }
                }
                result[y * width + x] = weight > 0 ? sum / weight : 0;
            }
        }
        return result;
    }

    // Blue -> cyan -> green -> yellow -> red heat gradient
    public static int heatColor(float t) {
        t = Math.max(0, Math.min(1, t));
        float alpha = 0.4f + t * 0.6f;
        int a = (int) (alpha * 255);
        int r, g, b;
        if (t < 0.25f) {
            float s = t / 0.25f;
            r = 0; g = (int) (s * 255); b = 255;
        } else if (t < 0.5f) {
            float s = (t - 0.25f) / 0.25f;
            r = 0; g = 255; b = (int) ((1 - s) * 255);
        } else if (t < 0.75f) {
            float s = (t - 0.5f) / 0.25f;
            r = (int) (s * 255); g = 255; b = 0;
        } else {
            float s = (t - 0.75f) / 0.25f;
            r = 255; g = (int) ((1 - s) * 255); b = 0;
        }
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
