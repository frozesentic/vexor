package com.example;

import net.minecraft.server.network.ServerPlayerEntity;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerTracker {

    // dimension id -> packed chunk key -> visit count
    private final Map<String, Map<Long, Integer>> heatData = new ConcurrentHashMap<>();
    private final Map<String, PlayerStats> playerStats = new ConcurrentHashMap<>();
    private final Map<String, Long> joinTimes = new ConcurrentHashMap<>();

    // Recent position samples for path queries: playerName -> list of (time, x, z, dim)
    private final Map<String, ArrayDeque<long[]>> recentPaths = new ConcurrentHashMap<>();
    private static final int MAX_PATH_ENTRIES = 1000;

    private boolean enabled = true;

    public void trackPosition(ServerPlayerEntity player) {
        if (!enabled) return;

        String dim = getDimensionId(player);
        int chunkX = player.getBlockX() >> 4;
        int chunkZ = player.getBlockZ() >> 4;
        long chunkKey = packChunk(chunkX, chunkZ);

        heatData.computeIfAbsent(dim, k -> new ConcurrentHashMap<>())
                .merge(chunkKey, 1, Integer::sum);

        playerStats.computeIfAbsent(player.getName().getString(), PlayerStats::new)
                .recordVisit(dim);

        // Track recent path
        String name = player.getName().getString();
        ArrayDeque<long[]> path = recentPaths.computeIfAbsent(name, k -> new ArrayDeque<>());
        path.addLast(new long[]{
                System.currentTimeMillis(),
                player.getBlockX(),
                player.getBlockY(),
                player.getBlockZ()
        });
        while (path.size() > MAX_PATH_ENTRIES) path.removeFirst();
    }

    public void onPlayerJoin(ServerPlayerEntity player) {
        String name = player.getName().getString();
        joinTimes.put(name, Instant.now().toEpochMilli());
        playerStats.computeIfAbsent(name, PlayerStats::new).incrementSessions();
    }

    public void onPlayerLeave(ServerPlayerEntity player) {
        String name = player.getName().getString();
        Long joinTime = joinTimes.remove(name);
        if (joinTime != null) {
            long ms = Instant.now().toEpochMilli() - joinTime;
            playerStats.computeIfAbsent(name, PlayerStats::new).addPlayTime(ms);
        }
    }

    public Map<Long, Integer> getHeatData(String dimension) {
        return Collections.unmodifiableMap(heatData.getOrDefault(dimension, Collections.emptyMap()));
    }

    public Map<String, Map<Long, Integer>> getAllHeatData() {
        return Collections.unmodifiableMap(heatData);
    }

    public List<String> getDimensions() {
        return new ArrayList<>(heatData.keySet());
    }

    public Map<String, PlayerStats> getPlayerStats() {
        return Collections.unmodifiableMap(playerStats);
    }

    public PlayerStats getPlayerStats(String name) {
        return playerStats.get(name);
    }

    public List<Map.Entry<Long, Integer>> getTopSpots(String dimension, int count) {
        Map<Long, Integer> data = heatData.getOrDefault(dimension, Collections.emptyMap());
        return data.entrySet().stream()
                .sorted(Map.Entry.<Long, Integer>comparingByValue().reversed())
                .limit(count)
                .toList();
    }

    public List<long[]> getRecentPath(String playerName, int count) {
        ArrayDeque<long[]> path = recentPaths.get(playerName);
        if (path == null) return Collections.emptyList();
        List<long[]> list = new ArrayList<>(path);
        int start = Math.max(0, list.size() - count);
        return list.subList(start, list.size());
    }

    public void clear() {
        heatData.clear();
        playerStats.clear();
        recentPaths.clear();
    }

    public void clear(String dimension) {
        heatData.remove(dimension);
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public void loadData(Map<String, Map<Long, Integer>> heat, Map<String, PlayerStats> stats) {
        heatData.clear();
        heat.forEach((k, v) -> heatData.put(k, new ConcurrentHashMap<>(v)));
        playerStats.clear();
        playerStats.putAll(stats);
    }

    public int getTotalSamples() {
        return heatData.values().stream()
                .flatMap(m -> m.values().stream())
                .mapToInt(Integer::intValue).sum();
    }

    public int getTotalChunks() {
        return heatData.values().stream().mapToInt(Map::size).sum();
    }

    public static String getDimensionId(ServerPlayerEntity player) {
        return player.getEntityWorld().getRegistryKey().getValue().toString();
    }

    public static long packChunk(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public static int unpackX(long key) { return (int) (key >> 32); }
    public static int unpackZ(long key) { return (int) (key & 0xFFFFFFFFL); }
}
