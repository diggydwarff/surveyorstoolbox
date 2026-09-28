package com.surveyorstoolbox;

import com.surveyorstoolbox.network.SurveyNetworking;
import com.surveyorstoolbox.registry.ModItems;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(SurveyorsToolbox.MOD_ID)
public final class SurveyorsToolbox {
    public static final String MOD_ID = "surveyors_toolbox";

    public SurveyorsToolbox() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModItems.ITEMS.register(modBus);
        SurveyNetworking.register();
    }
}
