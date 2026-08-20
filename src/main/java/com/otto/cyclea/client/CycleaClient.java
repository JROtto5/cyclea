package com.otto.cyclea.client;

import com.otto.cyclea.CycleaState;
import com.otto.cyclea.feature.TargetScanner;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * Entry point. Registers the two keys (toggle + cycle target), the HUD readout,
 * and the in-world guide-line renderer, and runs the scanner on a light cadence
 * so the finder stays cheap.
 */
public class CycleaClient implements ClientModInitializer {

    private static KeyMapping toggleKey;
    private static KeyMapping cycleKey;
    private int tickCounter = 0;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.cyclea.toggle", GLFW.GLFW_KEY_G, "key.category.cyclea"));
        cycleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.cyclea.cycle", GLFW.GLFW_KEY_B, "key.category.cyclea"));

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        WorldRenderEvents.AFTER_TRANSLUCENT.register(GuideRenderer::render);
        HudRenderCallback.EVENT.register((graphics, tickDelta) -> CycleaHud.render(graphics));
    }

    private void onTick(Minecraft mc) {
        while (toggleKey.consumeClick()) {
            CycleaState.get().toggle();
        }
        while (cycleKey.consumeClick()) {
            CycleaState.get().cycleTarget();
        }

        if (!CycleaState.get().isActive() || mc.level == null) {
            return;
        }
        // rescan a few times a second, not every tick
        if (++tickCounter >= 8) {
            tickCounter = 0;
            CycleaState.get().setFound(TargetScanner.scan(mc));
        }
    }
}
