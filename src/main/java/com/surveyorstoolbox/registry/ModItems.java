package com.surveyorstoolbox.registry;

import com.surveyorstoolbox.SurveyorsToolbox;
import com.surveyorstoolbox.item.ArchitectsEraserItem;
import com.surveyorstoolbox.item.ChalkItem;
import com.surveyorstoolbox.item.SurveyorsRulerItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.EnumMap;
import java.util.Map;

public final class ModItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, SurveyorsToolbox.MOD_ID);

    public static final RegistryObject<SurveyorsRulerItem> SURVEYORS_RULER = ITEMS.register(
            "surveyors_ruler",
            () -> new SurveyorsRulerItem(new Item.Properties().stacksTo(1).tab(CreativeModeTab.TAB_TOOLS))
    );

    public static final Map<DyeColor, RegistryObject<ChalkItem>> CHALKS = new EnumMap<>(DyeColor.class);

    public static final RegistryObject<ArchitectsEraserItem> ARCHITECTS_ERASER = ITEMS.register(
            "architects_eraser",
            () -> new ArchitectsEraserItem(new Item.Properties().stacksTo(1).tab(CreativeModeTab.TAB_TOOLS))
    );

    static {
        for (DyeColor color : DyeColor.values()) {
            CHALKS.put(color, ITEMS.register(
                    color.getName() + "_chalk",
                    () -> new ChalkItem(color, new Item.Properties().durability(32).tab(CreativeModeTab.TAB_TOOLS))
            ));
        }
    }

    public static RegistryObject<ChalkItem> chalk(DyeColor color) { return CHALKS.get(color); }

    private ModItems() { }
}
