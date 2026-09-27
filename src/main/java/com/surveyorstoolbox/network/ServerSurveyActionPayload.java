package com.surveyorstoolbox.network;

import com.surveyorstoolbox.SurveyorsToolbox;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;

public record ServerSurveyActionPayload(int action, int hand, CompoundTag data) implements CustomPacketPayload {
    public static final int REQUEST_SYNC = 0;
    public static final int COMMIT_CHALK = 1;
    public static final int ERASE_NEAREST = 2;
    public static final int CLEAR_OWN_CHALK = 3;

    public static final Type<ServerSurveyActionPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(SurveyorsToolbox.MOD_ID, "survey_action")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, ServerSurveyActionPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, ServerSurveyActionPayload::action,
                    ByteBufCodecs.VAR_INT, ServerSurveyActionPayload::hand,
                    ByteBufCodecs.COMPOUND_TAG, ServerSurveyActionPayload::data,
                    ServerSurveyActionPayload::new
            );

    public static ServerSurveyActionPayload requestSync() {
        return new ServerSurveyActionPayload(REQUEST_SYNC, 0, new CompoundTag());
    }

    public static ServerSurveyActionPayload commit(InteractionHand hand, CompoundTag measurement) {
        return new ServerSurveyActionPayload(COMMIT_CHALK, hand == InteractionHand.MAIN_HAND ? 0 : 1, measurement);
    }

    public static ServerSurveyActionPayload erase(InteractionHand hand, long target) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("Target", target);
        return new ServerSurveyActionPayload(ERASE_NEAREST, hand == InteractionHand.MAIN_HAND ? 0 : 1, tag);
    }

    public static ServerSurveyActionPayload clearOwn(InteractionHand hand) {
        return new ServerSurveyActionPayload(CLEAR_OWN_CHALK, hand == InteractionHand.MAIN_HAND ? 0 : 1, new CompoundTag());
    }


    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
