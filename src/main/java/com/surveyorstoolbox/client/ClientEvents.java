package com.surveyorstoolbox.client;

import com.surveyorstoolbox.SurveyorsToolbox;
import com.surveyorstoolbox.measurement.SurveyManager;
import com.surveyorstoolbox.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = SurveyorsToolbox.MOD_ID, value = Dist.CLIENT)
public final class ClientEvents {
    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        SurveyManager.clearForDisconnect();
        ChalkClientStore.clear();
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || minecraft.level == null) return;

        SurveyManager.ensureLevel(player);
        SurveyManager.tickDisplay(player);
        ChalkClientStore.tick(player);
        SurveyManager.tickWalkSurvey(player);

        boolean holdingRuler = player.getMainHandItem().is(ModItems.SURVEYORS_RULER.get())
                || player.getOffhandItem().is(ModItems.SURVEYORS_RULER.get());

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
