package com.example;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

@Environment(EnvType.CLIENT)
public class VexorModClient implements ClientModInitializer {

    private static final KeyBinding.Category VEXOR_CATEGORY =
            KeyBinding.Category.create(Identifier.of("vexor", "vexor"));

    public static KeyBinding toggleMapKey;
    public static KeyBinding zoomInKey;
    public static KeyBinding zoomOutKey;
    public static MinimapRenderer minimapRenderer;

    @Override
    public void onInitializeClient() {
        toggleMapKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.vexor.toggle_map",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_H,
                VEXOR_CATEGORY
        ));
        zoomInKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.vexor.zoom_in",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_KP_ADD,
                VEXOR_CATEGORY
        ));
        zoomOutKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.vexor.zoom_out",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_KP_SUBTRACT,
                VEXOR_CATEGORY
        ));

        minimapRenderer = new MinimapRenderer();

        HudRenderCallback.EVENT.register((drawContext, tickCounter) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null && client.currentScreen == null) {
                minimapRenderer.render(drawContext, client);
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleMapKey.wasPressed()) minimapRenderer.toggleVisible();
            while (zoomInKey.wasPressed()) minimapRenderer.adjustZoom(true);
            while (zoomOutKey.wasPressed()) minimapRenderer.adjustZoom(false);

            if (client.player != null) {
                minimapRenderer.tick(client.player);
            }
        });
    }
}
