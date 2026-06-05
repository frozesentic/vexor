package com.example;

import java.util.HashMap;
import java.util.Map;

public class PlayerStats {
    public final String name;
    public int sessions = 0;
    public long totalPlayTimeMs = 0;
    public int totalSamples = 0;
    public String mostVisitedDimension = "minecraft:overworld";

    private final Map<String, Integer> dimensionCounts = new HashMap<>();

    public PlayerStats(String name) {
        this.name = name;
    }

    public void incrementSessions() { sessions++; }

    public void addPlayTime(long ms) { totalPlayTimeMs += ms; }

    public void recordVisit(String dimension) {
        totalSamples++;
        dimensionCounts.merge(dimension, 1, Integer::sum);
        mostVisitedDimension = dimensionCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(dimension);
    }

    public String getFormattedPlayTime() {
        long secs = totalPlayTimeMs / 1000;
        long hours = secs / 3600;
        long mins = (secs % 3600) / 60;
        return hours + "h " + mins + "m";
    }

    public Map<String, Integer> getDimensionCounts() {
        return dimensionCounts;
    }
}
