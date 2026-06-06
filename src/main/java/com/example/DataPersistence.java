package com.example;

import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

import java.io.*;
import java.util.HashMap;
import java.util.Map;

public class DataPersistence {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static void save(MinecraftServer server, PlayerTracker tracker) {
        File dir = getDataDir(server);
        dir.mkdirs();

        JsonObject root = new JsonObject();

        // Heat data: dimension -> packed key (string) -> count
        JsonObject dimsJson = new JsonObject();
        for (Map.Entry<String, Map<Long, Integer>> dimEntry : tracker.getAllHeatData().entrySet()) {
            JsonObject chunksJson = new JsonObject();
            for (Map.Entry<Long, Integer> chunk : dimEntry.getValue().entrySet()) {
                chunksJson.addProperty(String.valueOf(chunk.getKey()), chunk.getValue());
            }
            dimsJson.add(dimEntry.getKey(), chunksJson);
        }
        root.add("heat", dimsJson);

        // Player stats
        JsonObject playersJson = new JsonObject();
        for (Map.Entry<String, PlayerStats> entry : tracker.getPlayerStats().entrySet()) {
            PlayerStats s = entry.getValue();
            JsonObject ps = new JsonObject();
            ps.addProperty("sessions", s.sessions);
            ps.addProperty("playTimeMs", s.totalPlayTimeMs);
            ps.addProperty("totalSamples", s.totalSamples);
            ps.addProperty("mostVisitedDimension", s.mostVisitedDimension);
            JsonObject dimCounts = new JsonObject();
            s.getDimensionCounts().forEach(dimCounts::addProperty);
            ps.add("dimensionCounts", dimCounts);
            playersJson.add(entry.getKey(), ps);
        }
        root.add("players", playersJson);

        File file = new File(dir, "vexor_data.json");
        try (FileWriter w = new FileWriter(file)) {
            GSON.toJson(root, w);
        } catch (IOException e) {
            VexorMod.LOGGER.error("Failed to save Vexor data", e);
        }
    }

    public static void load(MinecraftServer server, PlayerTracker tracker) {
        File file = new File(getDataDir(server), "vexor_data.json");
        if (!file.exists()) {
            VexorMod.LOGGER.info("[Vexor] No existing data file found, starting fresh");
            return;
        }

        try (FileReader r = new FileReader(file)) {
            JsonObject root = GSON.fromJson(r, JsonObject.class);

            Map<String, Map<Long, Integer>> heat = new HashMap<>();
            if (root.has("heat")) {
                for (Map.Entry<String, JsonElement> dimEntry : root.getAsJsonObject("heat").entrySet()) {
                    Map<Long, Integer> chunks = new HashMap<>();
                    for (Map.Entry<String, JsonElement> chunk : dimEntry.getValue().getAsJsonObject().entrySet()) {
                        chunks.put(Long.parseLong(chunk.getKey()), chunk.getValue().getAsInt());
                    }
                    heat.put(dimEntry.getKey(), chunks);
                }
            }

            Map<String, PlayerStats> stats = new HashMap<>();
            if (root.has("players")) {
                for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("players").entrySet()) {
                    PlayerStats s = new PlayerStats(entry.getKey());
                    JsonObject ps = entry.getValue().getAsJsonObject();
                    s.sessions = ps.get("sessions").getAsInt();
                    s.totalPlayTimeMs = ps.get("playTimeMs").getAsLong();
                    s.totalSamples = ps.get("totalSamples").getAsInt();
                    s.mostVisitedDimension = ps.get("mostVisitedDimension").getAsString();
                    if (ps.has("dimensionCounts")) {
                        for (Map.Entry<String, JsonElement> dc : ps.getAsJsonObject("dimensionCounts").entrySet()) {
                            s.getDimensionCounts().put(dc.getKey(), dc.getValue().getAsInt());
                        }
                    }
                    stats.put(entry.getKey(), s);
                }
            }

            tracker.loadData(heat, stats);
            VexorMod.LOGGER.info("[Vexor] Loaded {} dimensions, {} players from disk",
                    heat.size(), stats.size());

        } catch (IOException | JsonParseException e) {
            VexorMod.LOGGER.error("Failed to load Vexor data", e);
        }
    }

    public static File exportCsv(PlayerTracker tracker, File outputDir) throws IOException {
        outputDir.mkdirs();
        String ts = String.valueOf(System.currentTimeMillis());
        File file = new File(outputDir, "vexor_export_" + ts + ".csv");

        try (PrintWriter w = new PrintWriter(new FileWriter(file))) {
            w.println("dimension,chunkX,chunkZ,blockX,blockZ,visits");
            for (Map.Entry<String, Map<Long, Integer>> dimEntry : tracker.getAllHeatData().entrySet()) {
                for (Map.Entry<Long, Integer> entry : dimEntry.getValue().entrySet()) {
                    int cx = PlayerTracker.unpackX(entry.getKey());
                    int cz = PlayerTracker.unpackZ(entry.getKey());
                    w.printf("%s,%d,%d,%d,%d,%d%n",
                            dimEntry.getKey(), cx, cz, cx * 16, cz * 16, entry.getValue());
                }
            }
        }
        return file;
    }

    private static File getDataDir(MinecraftServer server) {
        return FabricLoader.getInstance().getGameDir().resolve("vexor").toFile();
    }
}
