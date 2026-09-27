package com.surveyorstoolbox.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

public final class GlowChalkItem extends Item {
    public GlowChalkItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal("Use on a finished measurement to make it persistent and glowing.")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("Works before or after colored chalk.")
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
