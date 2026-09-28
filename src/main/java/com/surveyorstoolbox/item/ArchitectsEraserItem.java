package com.surveyorstoolbox.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

import java.util.function.Consumer;

public final class ArchitectsEraserItem extends Item {
    public ArchitectsEraserItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
        tooltip.accept(Component.literal("Right-click near a chalk guide to erase it.")
                .withStyle(ChatFormatting.GRAY));
        tooltip.accept(Component.literal("Sneak + right-click: clear all of your chalk guides in this dimension.")
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
