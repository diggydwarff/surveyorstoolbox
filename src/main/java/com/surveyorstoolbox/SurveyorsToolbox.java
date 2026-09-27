package com.surveyorstoolbox;

import com.surveyorstoolbox.network.SurveyNetworking;
import com.surveyorstoolbox.registry.ModItems;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

@Mod(SurveyorsToolbox.MOD_ID)
public final class SurveyorsToolbox {
    public static final String MOD_ID = "surveyors_toolbox";

    public SurveyorsToolbox(IEventBus modBus) {
        ModItems.ITEMS.register(modBus);
        modBus.addListener(SurveyNetworking::register);
        modBus.addListener(this::addCreativeTabContents);
    }

    private void addCreativeTabContents(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(ModItems.SURVEYORS_RULER.get());
            event.accept(ModItems.ARCHITECTS_ERASER.get());
            event.accept(ModItems.GLOW_CHALK.get());
            ModItems.CHALKS.values().forEach(item -> event.accept(item.get()));
        }
    }
}
