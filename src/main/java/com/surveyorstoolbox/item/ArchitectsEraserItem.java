package com.surveyorstoolbox.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

public final class ArchitectsEraserItem extends Item {
    public ArchitectsEraserItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal("Right-click near a chalk guide to erase it.")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("Sneak + right-click: clear all of your chalk guides in this dimension.")
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
