package com.example;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VexorMod implements ModInitializer {
    public static final String MOD_ID = "vexor";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static PlayerTracker tracker;

    @Override
    public void onInitialize() {
        tracker = new PlayerTracker();

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            DataPersistence.load(server, tracker);
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            DataPersistence.save(server, tracker);
            LOGGER.info("Vexor data saved on shutdown");
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            int ticks = server.getTicks();
            if (ticks % 20 == 0) {
                server.getPlayerManager().getPlayerList().forEach(tracker::trackPosition);
            }
            if (ticks % 6000 == 0 && ticks > 0) {
                DataPersistence.save(server, tracker);
                LOGGER.info("[Vexor] Auto-saved: {} chunks tracked across {} dimensions",
                        tracker.getTotalChunks(), tracker.getDimensions().size());
            }
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                tracker.onPlayerJoin(handler.player));

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                tracker.onPlayerLeave(handler.player));

        VexorCommands.register();

        LOGGER.info("Vexor initialized — tracking all player positions");
    }
}
