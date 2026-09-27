package com.surveyorstoolbox.client;

import com.surveyorstoolbox.SurveyorsToolbox;
import com.surveyorstoolbox.measurement.SurveyManager;
import com.surveyorstoolbox.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid = SurveyorsToolbox.MOD_ID, value = Dist.CLIENT)
public final class ClientEvents {
    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        SurveyManager.clearForDisconnect();
        ChalkClientStore.clear();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || minecraft.level == null) return;

        SurveyManager.ensureLevel(player);
        SurveyManager.tickDisplay(player);
        ChalkClientStore.tick(player);

        // Once a walk survey has started, keep sampling movement even if the player
        // temporarily changes hotbar slots. Otherwise returning to the ruler would
        // create one huge artificial straight segment.
        SurveyManager.tickWalkSurvey(player);

        boolean holdingRuler = player.getMainHandItem().is(ModItems.SURVEYORS_RULER.get())
                || player.getOffhandItem().is(ModItems.SURVEYORS_RULER.get());

        // Always drain key clicks so presses made while not holding the ruler do not
        // queue up and unexpectedly execute later.
        while (ClientKeyMappings.NEXT_MODE.consumeClick()) {
            if (holdingRuler) {
                if (player.isShiftKeyDown()) SurveyManager.previousMode(player);
                else SurveyManager.nextMode(player);
            }
        }

        while (ClientKeyMappings.CLEAR.consumeClick()) {
            if (holdingRuler) SurveyManager.clear(player);
        }

    }

    private ClientEvents() { }
}
