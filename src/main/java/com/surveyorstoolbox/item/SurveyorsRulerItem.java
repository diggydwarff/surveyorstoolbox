package com.surveyorstoolbox.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

public final class SurveyorsRulerItem extends Item {
    public SurveyorsRulerItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal("M: next measuring mode").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("Shift + M: previous mode").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("Sneak + right-click: finish / close").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("R: clear measurement").withStyle(ChatFormatting.GRAY));
    }
}
