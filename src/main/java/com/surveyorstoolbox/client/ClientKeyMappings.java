package com.surveyorstoolbox.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.surveyorstoolbox.SurveyorsToolbox;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = SurveyorsToolbox.MOD_ID, value = Dist.CLIENT)
public final class ClientKeyMappings {
    public static final KeyMapping NEXT_MODE = new KeyMapping(
            "key.surveyors_toolbox.next_mode",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_M,
            "key.categories.surveyors_toolbox"
    );

    public static final KeyMapping CLEAR = new KeyMapping(
            "key.surveyors_toolbox.clear",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_R,
            "key.categories.surveyors_toolbox"
    );

    @SubscribeEvent
    public static void register(RegisterKeyMappingsEvent event) {
        event.register(NEXT_MODE);
        event.register(CLEAR);
    }

    private ClientKeyMappings() {}
}
