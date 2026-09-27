package com.surveyorstoolbox.network;

import com.surveyorstoolbox.SurveyorsToolbox;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ChalkSyncPayload(CompoundTag data) implements CustomPacketPayload {
    public static final Type<ChalkSyncPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(SurveyorsToolbox.MOD_ID, "chalk_sync")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, ChalkSyncPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.COMPOUND_TAG, ChalkSyncPayload::data,
                    ChalkSyncPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
