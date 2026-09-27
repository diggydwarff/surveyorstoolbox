package com.surveyorstoolbox.registry;

import com.surveyorstoolbox.SurveyorsToolbox;
import com.surveyorstoolbox.item.ArchitectsEraserItem;
import com.surveyorstoolbox.item.ChalkItem;
import com.surveyorstoolbox.item.GlowChalkItem;
import com.surveyorstoolbox.item.SurveyorsRulerItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.EnumMap;
import java.util.Map;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(SurveyorsToolbox.MOD_ID);

    public static final DeferredItem<SurveyorsRulerItem> SURVEYORS_RULER = ITEMS.registerItem(
            "surveyors_ruler",
            SurveyorsRulerItem::new,
            new Item.Properties().stacksTo(1)
    );

    public static final Map<DyeColor, DeferredItem<ChalkItem>> CHALKS = new EnumMap<>(DyeColor.class);


    public static final DeferredItem<GlowChalkItem> GLOW_CHALK = ITEMS.registerItem(
            "glow_chalk",
            GlowChalkItem::new,
            new Item.Properties().durability(32)
    );


    public static final DeferredItem<ArchitectsEraserItem> ARCHITECTS_ERASER = ITEMS.registerItem(
            "architects_eraser",
            ArchitectsEraserItem::new,
            new Item.Properties().stacksTo(1)
    );


    static {
        for (DyeColor color : DyeColor.values()) {
            CHALKS.put(color, ITEMS.registerItem(
                    color.getName() + "_chalk",
                    properties -> new ChalkItem(color, properties),
                    new Item.Properties().durability(32)
            ));
        }
    }

    public static DeferredItem<ChalkItem> chalk(DyeColor color) {
        return CHALKS.get(color);
    }

    private ModItems() { }
}
