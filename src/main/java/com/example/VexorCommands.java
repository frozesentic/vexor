package com.example;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionCheck;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.command.permission.PermissionSourcePredicate;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class VexorCommands {

    // Server owner only (op level 4 equivalent in the new 1.21.11 permission system)
    private static final Predicate<ServerCommandSource> OWNER_ONLY =
            new PermissionSourcePredicate<>(new PermissionCheck.Require(new Permission.Level(PermissionLevel.OWNERS)));

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(literal("vexor")
                .requires(OWNER_ONLY)
                .then(literal("help")
                    .executes(ctx -> showHelp(ctx.getSource())))
                .then(literal("heatmap")
                    // No args → auto-bounds (fits all tracked data)
                    .executes(ctx -> generateHeatmap(ctx.getSource(), null, null, -1))
                    .then(argument("radius", IntegerArgumentType.integer(100, 30_000_000))
                        .executes(ctx -> generateHeatmap(ctx.getSource(), null, null,
                            IntegerArgumentType.getInteger(ctx, "radius"))))
                    .then(argument("x", IntegerArgumentType.integer(-30_000_000, 30_000_000))
                        .then(argument("z", IntegerArgumentType.integer(-30_000_000, 30_000_000))
                            .executes(ctx -> generateHeatmap(ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "x"),
                                IntegerArgumentType.getInteger(ctx, "z"), 5000))
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

    // radius = -1 means auto-bounds
    private static int generateHeatmap(ServerCommandSource src, Integer x, Integer z, int radius) {
        PlayerTracker tracker = VexorMod.tracker;

        String dim;
        int cx = 0, cz = 0;
        boolean hasCenter = (x != null);

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
            src.sendFeedback(() -> Text.literal("[Vexor] No tracking data for: " + dim).formatted(Formatting.RED), false);
            return 0;
        }

        boolean autoMode = (radius == -1 && !hasCenter);
        String modeDesc = autoMode ? "full map (auto-bounds)" : "radius " + radius + "b";
        src.sendFeedback(() -> Text.literal("[Vexor] Generating heatmap (" + modeDesc + ")…").formatted(Formatting.YELLOW), false);

        final String finalDim = dim;
        final int finalCx = cx, finalCz = cz, finalRadius = radius;

        new Thread(() -> {
            try {
                File outputDir = new File(src.getServer().getRunDirectory().toFile(), "vexor/heatmaps");
                File file;
                if (autoMode) {
                    file = HeatmapGenerator.generateAuto(heat, finalDim, outputDir);
                } else {
                    file = HeatmapGenerator.generateFixed(heat, finalDim, finalCx, finalCz, finalRadius, outputDir);
                }
                if (file != null) {
                    String path = file.getAbsolutePath();
                    src.getServer().execute(() ->
                        src.sendFeedback(() -> Text.literal("[Vexor] Saved: " + path).formatted(Formatting.GREEN), true)
                    );
                } else {
                    src.getServer().execute(() ->
                        src.sendFeedback(() -> Text.literal("[Vexor] No data in range").formatted(Formatting.RED), false)
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
                src.sendFeedback(() -> Text.literal("[Vexor] No data for: " + playerName).formatted(Formatting.RED), false);
                return 0;
            }
            src.sendFeedback(() -> Text.literal("=== Vexor: " + playerName + " ===").formatted(Formatting.GOLD), false);
            src.sendFeedback(() -> Text.literal("  Sessions:   " + stats.sessions).formatted(Formatting.WHITE), false);
            src.sendFeedback(() -> Text.literal("  Play Time:  " + stats.getFormattedPlayTime()).formatted(Formatting.WHITE), false);
            src.sendFeedback(() -> Text.literal("  Samples:    " + stats.totalSamples).formatted(Formatting.WHITE), false);
            src.sendFeedback(() -> Text.literal("  Main Dim:   " + stats.mostVisitedDimension.replace("minecraft:", "")).formatted(Formatting.WHITE), false);
        } else {
            boolean on = tracker.isEnabled();
            src.sendFeedback(() -> Text.literal("=== Vexor Tracking Stats ===").formatted(Formatting.GOLD), false);
            src.sendFeedback(() -> Text.literal("  Tracking:   " + (on ? "ON" : "OFF")).formatted(on ? Formatting.GREEN : Formatting.RED), false);
            src.sendFeedback(() -> Text.literal("  Players:    " + tracker.getPlayerStats().size()).formatted(Formatting.WHITE), false);
            src.sendFeedback(() -> Text.literal("  Cells:      " + tracker.getTotalChunks() + "  (" + PlayerTracker.CELL_SIZE + "×" + PlayerTracker.CELL_SIZE + " blocks each)").formatted(Formatting.WHITE), false);
            src.sendFeedback(() -> Text.literal("  Samples:    " + tracker.getTotalSamples()).formatted(Formatting.WHITE), false);
            String dims = String.join(", ", tracker.getDimensions().stream()
                    .map(d -> d.replace("minecraft:", "")).toList());
            src.sendFeedback(() -> Text.literal("  Dims:       " + dims).formatted(Formatting.WHITE), false);
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
        src.sendFeedback(() -> Text.literal("=== Top " + count + " Hotspots (" + dimShort + ") ===").formatted(Formatting.GOLD), false);
        int cellSize = PlayerTracker.CELL_SIZE;
        for (int i = 0; i < top.size(); i++) {
            Map.Entry<Long, Integer> e = top.get(i);
            int bx = PlayerTracker.unpackX(e.getKey()) * cellSize;
            int bz = PlayerTracker.unpackZ(e.getKey()) * cellSize;
            int rank = i + 1;
            src.sendFeedback(() -> Text.literal(
                String.format("  #%-3d [%8d, %8d]  %d visits", rank, bx, bz, e.getValue())
            ).formatted(Formatting.WHITE), false);
        }
        return 1;
    }

    private static int showPath(ServerCommandSource src, String playerName, int count) {
        List<long[]> path = VexorMod.tracker.getRecentPath(playerName, count);
        if (path.isEmpty()) {
            src.sendFeedback(() -> Text.literal("[Vexor] No path data for: " + playerName).formatted(Formatting.RED), false);
            return 0;
        }
        src.sendFeedback(() -> Text.literal("=== Recent Path: " + playerName + " ===").formatted(Formatting.GOLD), false);
        for (long[] entry : path) {
            String ts = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date(entry[0]));
            src.sendFeedback(() -> Text.literal(
                String.format("  %s  [%d, %d, %d]", ts, (int)entry[1], (int)entry[2], (int)entry[3])
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
            String total = stats != null ? stats.getFormattedPlayTime() : "?";
            src.sendFeedback(() -> Text.literal(
                String.format("  %-16s  %-10s  [%s]  total: %s", name, dim, coords, total)
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
            src.sendFeedback(() -> Text.literal("[Vexor] Cleared: " + dimension).formatted(Formatting.YELLOW), true);
        } else {
            VexorMod.tracker.clear();
            src.sendFeedback(() -> Text.literal("[Vexor] All tracking data cleared").formatted(Formatting.YELLOW), true);
        }
        return 1;
    }

    private static int saveData(ServerCommandSource src) {
        DataPersistence.save(src.getServer(), VexorMod.tracker);
        src.sendFeedback(() -> Text.literal("[Vexor] Data saved").formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int reloadData(ServerCommandSource src) {
        DataPersistence.load(src.getServer(), VexorMod.tracker);
        src.sendFeedback(() -> Text.literal("[Vexor] Data reloaded").formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int exportCsv(ServerCommandSource src) {
        try {
            File dir = new File(src.getServer().getRunDirectory().toFile(), "vexor");
            File file = DataPersistence.exportCsv(VexorMod.tracker, dir);
            src.sendFeedback(() -> Text.literal("[Vexor] CSV: " + file.getAbsolutePath()).formatted(Formatting.GREEN), false);
        } catch (Exception e) {
            src.sendFeedback(() -> Text.literal("[Vexor] Export failed: " + e.getMessage()).formatted(Formatting.RED), false);
        }
        return 1;
    }

    private static int showHelp(ServerCommandSource src) {
        src.sendFeedback(() -> Text.literal("=== Vexor  (owner only) ===").formatted(Formatting.GOLD), false);
        String[][] cmds = {
            {"/vexor heatmap", "Full-map PNG auto-fitted to all tracked data"},
            {"/vexor heatmap <radius>", "PNG centered at your position, given radius (blocks)"},
            {"/vexor heatmap <x> <z> <radius>", "PNG centered on specific coordinates"},
            {"/vexor stats", "Overall: players, cells, samples, dimensions"},
            {"/vexor stats <player>", "Per-player stats"},
            {"/vexor top [count]", "Top N most-visited locations"},
            {"/vexor path <player> [count]", "Recent position history with timestamps"},
            {"/vexor session", "All currently online players with live coords"},
            {"/vexor track on|off", "Enable or disable position tracking"},
            {"/vexor clear [dim]", "Wipe tracking data (optionally per-dimension)"},
            {"/vexor save", "Force-save data to disk now"},
            {"/vexor reload", "Reload data from disk"},
            {"/vexor export", "Export all data as CSV"},
        };
        for (String[] cmd : cmds) {
            src.sendFeedback(() -> Text.literal("  " + cmd[0]).formatted(Formatting.AQUA)
                .append(Text.literal(" — " + cmd[1]).formatted(Formatting.GRAY)), false);
        }
        return 1;
    }
}
