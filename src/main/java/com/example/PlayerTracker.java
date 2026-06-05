package com.example;

import net.minecraft.server.network.ServerPlayerEntity;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerTracker {

    // 4-block cell resolution — fine enough to show individual paths
    public static final int CELL_BITS = 2;
    public static final int CELL_SIZE = 1 << CELL_BITS; // 4 blocks

    // dimension id -> packed cell key -> visit count
    private final Map<String, Map<Long, Integer>> heatData = new ConcurrentHashMap<>();
    private final Map<String, PlayerStats> playerStats = new ConcurrentHashMap<>();
    private final Map<String, Long> joinTimes = new ConcurrentHashMap<>();

    // For Bresenham path interpolation between samples
    private final Map<String, int[]> lastCell = new ConcurrentHashMap<>();  // [cellX, cellZ]
    private final Map<String, String> lastDim = new ConcurrentHashMap<>();

    // Recent position log for /vexor path command
    private final Map<String, ArrayDeque<long[]>> recentPaths = new ConcurrentHashMap<>();
    private static final int MAX_PATH_ENTRIES = 1000;
    // Max cells between samples before we assume teleport (200 blocks)
    private static final int MAX_INTERPOLATE_CELLS = 200 / CELL_SIZE;

    private boolean enabled = true;

    public void trackPosition(ServerPlayerEntity player) {
        if (!enabled) return;

        String name = player.getName().getString();
        String dim = getDimensionId(player);
        int cx = player.getBlockX() >> CELL_BITS;
        int cz = player.getBlockZ() >> CELL_BITS;

        Map<Long, Integer> dimData = heatData.computeIfAbsent(dim, k -> new ConcurrentHashMap<>());

        String prevDim = lastDim.get(name);
        int[] prev = lastCell.get(name);

        if (prev != null && dim.equals(prevDim)) {
            int dx = Math.abs(cx - prev[0]);
            int dz = Math.abs(cz - prev[1]);
            if (Math.max(dx, dz) <= MAX_INTERPOLATE_CELLS) {
                // Draw a continuous line between the two sampled positions
                bresenhamLine(dimData, prev[0], prev[1], cx, cz);
            } else {
                // Teleport — just mark the landing cell
                dimData.merge(pack(cx, cz), 1, Integer::sum);
            }
        } else {
            dimData.merge(pack(cx, cz), 1, Integer::sum);
        }

        lastCell.put(name, new int[]{cx, cz});
        lastDim.put(name, dim);

        playerStats.computeIfAbsent(name, PlayerStats::new).recordVisit(dim);

        ArrayDeque<long[]> path = recentPaths.computeIfAbsent(name, k -> new ArrayDeque<>());
        path.addLast(new long[]{
                System.currentTimeMillis(),
                player.getBlockX(), player.getBlockY(), player.getBlockZ()
        });
        while (path.size() > MAX_PATH_ENTRIES) path.removeFirst();
    }

    private void bresenhamLine(Map<Long, Integer> data, int x0, int z0, int x1, int z1) {
        int dx = Math.abs(x1 - x0);
        int dz = Math.abs(z1 - z0);
        int sx = x0 < x1 ? 1 : -1;
        int sz = z0 < z1 ? 1 : -1;
        int err = dx - dz;

        while (true) {
            data.merge(pack(x0, z0), 1, Integer::sum);
            if (x0 == x1 && z0 == z1) break;
            int e2 = 2 * err;
            if (e2 > -dz) { err -= dz; x0 += sx; }
            if (e2 < dx)  { err += dx; z0 += sz; }
        }
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
            playerStats.computeIfAbsent(name, PlayerStats::new)
                    .addPlayTime(Instant.now().toEpochMilli() - joinTime);
        }
        lastCell.remove(name);
        lastDim.remove(name);
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
        return heatData.getOrDefault(dimension, Collections.emptyMap()).entrySet().stream()
                .sorted(Map.Entry.<Long, Integer>comparingByValue().reversed())
                .limit(count)
                .toList();
    }

    public List<long[]> getRecentPath(String playerName, int count) {
        ArrayDeque<long[]> path = recentPaths.get(playerName);
        if (path == null) return Collections.emptyList();
        List<long[]> list = new ArrayList<>(path);
        return list.subList(Math.max(0, list.size() - count), list.size());
    }

    public void clear() {
        heatData.clear();
        playerStats.clear();
        recentPaths.clear();
        lastCell.clear();
        lastDim.clear();
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
        return heatData.values().stream().flatMap(m -> m.values().stream()).mapToInt(Integer::intValue).sum();
    }

    public int getTotalChunks() {
        return heatData.values().stream().mapToInt(Map::size).sum();
    }

    public static String getDimensionId(ServerPlayerEntity player) {
        return player.getEntityWorld().getRegistryKey().getValue().toString();
    }

    public static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    // Returns cell coordinate — multiply by CELL_SIZE for block coords
    public static int unpackX(long key) { return (int) (key >> 32); }
    public static int unpackZ(long key) { return (int) (key & 0xFFFFFFFFL); }
}
