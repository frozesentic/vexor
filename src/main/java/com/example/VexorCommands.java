package com.example;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.File;
import java.util.List;
import java.util.Map;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class VexorCommands {

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(literal("vexor")
                .requires(src -> src.hasPermissionLevel(2))
                .then(literal("help")
                    .executes(ctx -> showHelp(ctx.getSource())))
                .then(literal("heatmap")
                    .executes(ctx -> generateHeatmap(ctx.getSource(), null, null, 1000))
                    .then(argument("radius", IntegerArgumentType.integer(100, 30_000_000))
                        .executes(ctx -> generateHeatmap(ctx.getSource(), null, null,
                            IntegerArgumentType.getInteger(ctx, "radius"))))
                    .then(argument("x", IntegerArgumentType.integer(-30_000_000, 30_000_000))
                        .then(argument("z", IntegerArgumentType.integer(-30_000_000, 30_000_000))
                            .executes(ctx -> generateHeatmap(ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "x"),
                                IntegerArgumentType.getInteger(ctx, "z"), 1000))
                            .then(argument("radius", IntegerArgumentType.integer(100, 30_000_000))
                                .executes(ctx -> generateHeatmap(ctx.getSource(),
                                    IntegerArgumentType.getInteger(ctx, "x"),
                                    IntegerArgumentType.getInteger(ctx, "z"),
                                    IntegerArgumentType.getInteger(ctx, "radius")))))))
                .then(literal("stats")
                    .executes(ctx -> showStats(ctx.getSource(), null))
                    .then(argument("player", StringArgumentType.string())
                        .executes(ctx -> showStats(ctx.getSource(),
                            StringArgumentType.getString(ctx, "player")))))
                .then(literal("top")
                    .executes(ctx -> showTop(ctx.getSource(), 10))
                    .then(argument("count", IntegerArgumentType.integer(1, 50))
                        .executes(ctx -> showTop(ctx.getSource(),
                            IntegerArgumentType.getInteger(ctx, "count")))))
                .then(literal("path")
                    .then(argument("player", StringArgumentType.string())
                        .executes(ctx -> showPath(ctx.getSource(),
                            StringArgumentType.getString(ctx, "player"), 10))
                        .then(argument("count", IntegerArgumentType.integer(1, 50))
                            .executes(ctx -> showPath(ctx.getSource(),
                                StringArgumentType.getString(ctx, "player"),
                                IntegerArgumentType.getInteger(ctx, "count"))))))
                .then(literal("session")
                    .executes(ctx -> showSessions(ctx.getSource())))
                .then(literal("track")
                    .then(literal("on").executes(ctx -> setTracking(ctx.getSource(), true)))
                    .then(literal("off").executes(ctx -> setTracking(ctx.getSource(), false))))
                .then(literal("clear")
                    .executes(ctx -> clearData(ctx.getSource(), null))
                    .then(argument("dimension", StringArgumentType.string())
                        .executes(ctx -> clearData(ctx.getSource(),
                            StringArgumentType.getString(ctx, "dimension")))))
                .then(literal("save")
                    .executes(ctx -> saveData(ctx.getSource())))
                .then(literal("reload")
                    .executes(ctx -> reloadData(ctx.getSource())))
                .then(literal("export")
                    .executes(ctx -> exportCsv(ctx.getSource())))
            )
        );
    }

    private static int generateHeatmap(ServerCommandSource src, Integer x, Integer z, int radius) {
        PlayerTracker tracker = VexorMod.tracker;

        String dim;
        int cx, cz;

        if (src.getEntity() instanceof ServerPlayerEntity player) {
            dim = PlayerTracker.getDimensionId(player);
            cx = x != null ? x : player.getBlockX();
            cz = z != null ? z : player.getBlockZ();
        } else {
            dim = tracker.getDimensions().isEmpty() ? "minecraft:overworld" : tracker.getDimensions().get(0);
            cx = x != null ? x : 0;
            cz = z != null ? z : 0;
        }

        Map<Long, Integer> heat = tracker.getHeatData(dim);
        if (heat.isEmpty()) {
            src.sendFeedback(() -> Text.literal("[Vexor] No data for dimension: " + dim).formatted(Formatting.RED), false);
            return 0;
        }

        src.sendFeedback(() -> Text.literal("[Vexor] Generating heatmap...").formatted(Formatting.YELLOW), false);

        final String finalDim = dim;
        final int finalCx = cx, finalCz = cz;
        new Thread(() -> {
            try {
                File outputDir = new File(src.getServer().getRunDirectory().toFile(), "vexor/heatmaps");
                File file = HeatmapGenerator.generate(heat, finalDim, finalCx, finalCz, radius, outputDir);
                if (file != null) {
                    String path = file.getAbsolutePath();
                    src.getServer().execute(() ->
                        src.sendFeedback(() -> Text.literal("[Vexor] Heatmap saved: " + path).formatted(Formatting.GREEN), true)
                    );
                } else {
                    src.getServer().execute(() ->
                        src.sendFeedback(() -> Text.literal("[Vexor] No tracked data in that range").formatted(Formatting.RED), false)
                    );
                }
            } catch (Exception e) {
                VexorMod.LOGGER.error("Heatmap generation failed", e);
                src.getServer().execute(() ->
                    src.sendFeedback(() -> Text.literal("[Vexor] Error: " + e.getMessage()).formatted(Formatting.RED), false)
                );
            }
        }, "vexor-heatmap").start();

        return 1;
    }

    private static int showStats(ServerCommandSource src, String playerName) {
        PlayerTracker tracker = VexorMod.tracker;

        if (playerName != null) {
            PlayerStats stats = tracker.getPlayerStats(playerName);
            if (stats == null) {
                src.sendFeedback(() -> Text.literal("[Vexor] No data for player: " + playerName).formatted(Formatting.RED), false);
                return 0;
            }
            src.sendFeedback(() -> Text.literal("=== Vexor: " + playerName + " ===").formatted(Formatting.GOLD), false);
            src.sendFeedback(() -> Text.literal("  Sessions:     " + stats.sessions).formatted(Formatting.WHITE), false);
            src.sendFeedback(() -> Text.literal("  Play Time:    " + stats.getFormattedPlayTime()).formatted(Formatting.WHITE), false);
            src.sendFeedback(() -> Text.literal("  Samples:      " + stats.totalSamples).formatted(Formatting.WHITE), false);
            src.sendFeedback(() -> Text.literal("  Main Dim:     " + stats.mostVisitedDimension.replace("minecraft:", "")).formatted(Formatting.WHITE), false);
        } else {
            boolean on = tracker.isEnabled();
            src.sendFeedback(() -> Text.literal("=== Vexor Tracking Stats ===").formatted(Formatting.GOLD), false);
            src.sendFeedback(() -> Text.literal("  Tracking:     " + (on ? "ON" : "OFF")).formatted(on ? Formatting.GREEN : Formatting.RED), false);
            src.sendFeedback(() -> Text.literal("  Players:      " + tracker.getPlayerStats().size()).formatted(Formatting.WHITE), false);
            src.sendFeedback(() -> Text.literal("  Chunks:       " + tracker.getTotalChunks()).formatted(Formatting.WHITE), false);
            src.sendFeedback(() -> Text.literal("  Samples:      " + tracker.getTotalSamples()).formatted(Formatting.WHITE), false);
            String dims = String.join(", ", tracker.getDimensions().stream()
                    .map(d -> d.replace("minecraft:", "")).toList());
            src.sendFeedback(() -> Text.literal("  Dimensions:   " + dims).formatted(Formatting.WHITE), false);
        }
        return 1;
    }

    private static int showTop(ServerCommandSource src, int count) {
        PlayerTracker tracker = VexorMod.tracker;

        String dim;
        if (src.getEntity() instanceof ServerPlayerEntity player) {
            dim = PlayerTracker.getDimensionId(player);
        } else {
            dim = tracker.getDimensions().isEmpty() ? "minecraft:overworld" : tracker.getDimensions().get(0);
        }

        List<Map.Entry<Long, Integer>> top = tracker.getTopSpots(dim, count);
        if (top.isEmpty()) {
            src.sendFeedback(() -> Text.literal("[Vexor] No data for " + dim).formatted(Formatting.RED), false);
            return 0;
        }

        String dimShort = dim.replace("minecraft:", "");
        src.sendFeedback(() -> Text.literal("=== Top " + count + " Chunks (" + dimShort + ") ===").formatted(Formatting.GOLD), false);
        for (int i = 0; i < top.size(); i++) {
            Map.Entry<Long, Integer> e = top.get(i);
            int bx = PlayerTracker.unpackX(e.getKey()) * 16;
            int bz = PlayerTracker.unpackZ(e.getKey()) * 16;
            int rank = i + 1;
            src.sendFeedback(() -> Text.literal(
                String.format("  #%-3d [%7d, %7d]  %d samples", rank, bx, bz, e.getValue())
            ).formatted(Formatting.WHITE), false);
        }
        return 1;
    }

    private static int showPath(ServerCommandSource src, String playerName, int count) {
        List<long[]> path = VexorMod.tracker.getRecentPath(playerName, count);
        if (path.isEmpty()) {
            src.sendFeedback(() -> Text.literal("[Vexor] No recent path data for: " + playerName).formatted(Formatting.RED), false);
            return 0;
        }
        src.sendFeedback(() -> Text.literal("=== Recent Path: " + playerName + " (last " + path.size() + ") ===").formatted(Formatting.GOLD), false);
        for (long[] entry : path) {
            long time = entry[0];
            int x = (int) entry[1], y = (int) entry[2], z = (int) entry[3];
            String timeStr = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date(time));
            src.sendFeedback(() -> Text.literal(
                String.format("  %s  [%d, %d, %d]", timeStr, x, y, z)
            ).formatted(Formatting.WHITE), false);
        }
        return 1;
    }

    private static int showSessions(ServerCommandSource src) {
        var players = src.getServer().getPlayerManager().getPlayerList();
        if (players.isEmpty()) {
            src.sendFeedback(() -> Text.literal("[Vexor] No players online").formatted(Formatting.YELLOW), false);
            return 1;
        }
        src.sendFeedback(() -> Text.literal("=== Active Sessions ===").formatted(Formatting.GOLD), false);
        for (ServerPlayerEntity p : players) {
            String name = p.getName().getString();
            PlayerStats stats = VexorMod.tracker.getPlayerStats(name);
            String dim = PlayerTracker.getDimensionId(p).replace("minecraft:", "");
            String coords = p.getBlockX() + ", " + p.getBlockY() + ", " + p.getBlockZ();
            String totalTime = stats != null ? stats.getFormattedPlayTime() : "?";
            src.sendFeedback(() -> Text.literal(
                String.format("  %-16s  %s  [%s]  total: %s", name, dim, coords, totalTime)
            ).formatted(Formatting.WHITE), false);
        }
        return 1;
    }

    private static int setTracking(ServerCommandSource src, boolean enabled) {
        VexorMod.tracker.setEnabled(enabled);
        src.sendFeedback(() -> Text.literal("[Vexor] Tracking " + (enabled ? "ENABLED" : "DISABLED"))
                .formatted(enabled ? Formatting.GREEN : Formatting.RED), true);
        return 1;
    }

    private static int clearData(ServerCommandSource src, String dimension) {
        if (dimension != null) {
            VexorMod.tracker.clear(dimension);
            src.sendFeedback(() -> Text.literal("[Vexor] Cleared data for: " + dimension).formatted(Formatting.YELLOW), true);
        } else {
            VexorMod.tracker.clear();
            src.sendFeedback(() -> Text.literal("[Vexor] All tracking data cleared").formatted(Formatting.YELLOW), true);
        }
        return 1;
    }

    private static int saveData(ServerCommandSource src) {
        DataPersistence.save(src.getServer(), VexorMod.tracker);
        src.sendFeedback(() -> Text.literal("[Vexor] Data saved to disk").formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int reloadData(ServerCommandSource src) {
        DataPersistence.load(src.getServer(), VexorMod.tracker);
        src.sendFeedback(() -> Text.literal("[Vexor] Data reloaded from disk").formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int exportCsv(ServerCommandSource src) {
        try {
            File outputDir = new File(src.getServer().getRunDirectory().toFile(), "vexor");
            File file = DataPersistence.exportCsv(VexorMod.tracker, outputDir);
            src.sendFeedback(() -> Text.literal("[Vexor] CSV exported: " + file.getAbsolutePath()).formatted(Formatting.GREEN), false);
        } catch (Exception e) {
            src.sendFeedback(() -> Text.literal("[Vexor] Export failed: " + e.getMessage()).formatted(Formatting.RED), false);
        }
        return 1;
    }

    private static int showHelp(ServerCommandSource src) {
        src.sendFeedback(() -> Text.literal("=== Vexor Commands (op level 2) ===").formatted(Formatting.GOLD), false);
        String[][] cmds = {
            {"/vexor heatmap [radius]", "Generate PNG heatmap at your position"},
            {"/vexor heatmap <x> <z> [radius]", "Generate heatmap at specific coords"},
            {"/vexor stats", "Show overall tracking statistics"},
            {"/vexor stats <player>", "Show stats for a specific player"},
            {"/vexor top [count]", "Top N most-visited chunk locations"},
            {"/vexor path <player> [count]", "Show recent position history of a player"},
            {"/vexor session", "Show all active player sessions"},
            {"/vexor track on|off", "Enable or disable tracking"},
            {"/vexor clear [dimension]", "Clear tracking data"},
            {"/vexor save", "Save data to disk now"},
            {"/vexor reload", "Reload data from disk"},
            {"/vexor export", "Export all data as CSV"},
        };
        for (String[] cmd : cmds) {
            src.sendFeedback(() -> Text.literal("  " + cmd[0]).formatted(Formatting.AQUA)
                .append(Text.literal(" - " + cmd[1]).formatted(Formatting.GRAY)), false);
        }
        return 1;
    }
}
