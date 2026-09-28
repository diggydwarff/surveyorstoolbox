package com.surveyorstoolbox.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

import java.util.function.Consumer;

public final class SurveyorsRulerItem extends Item {
    public SurveyorsRulerItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
        tooltip.accept(Component.literal("M: next measuring mode").withStyle(ChatFormatting.GRAY));
        tooltip.accept(Component.literal("Shift + M: previous mode").withStyle(ChatFormatting.GRAY));
        tooltip.accept(Component.literal("Sneak + right-click: finish / close").withStyle(ChatFormatting.GRAY));
        tooltip.accept(Component.literal("R: clear measurement").withStyle(ChatFormatting.GRAY));
    }
}
