package com.surveyorstoolbox.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

import java.util.function.Consumer;

public final class ChalkItem extends Item {
    private final DyeColor color;

    public ChalkItem(DyeColor color, Properties properties) {
        super(properties);
        this.color = color;
    }

    public DyeColor color() {
        return color;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
        tooltip.accept(Component.literal("Right-click after measuring to make the guide persistent.")
                .withStyle(ChatFormatting.GRAY));
    }
}
