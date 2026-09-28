package com.surveyorstoolbox.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.InteractionHand;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record ServerSurveyActionPayload(int action, int hand, CompoundTag data) {
    public static final int REQUEST_SYNC = 0;
    public static final int COMMIT_CHALK = 1;
    public static final int ERASE_NEAREST = 2;
    public static final int CLEAR_OWN_CHALK = 3;

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

    public static void encode(ServerSurveyActionPayload message, FriendlyByteBuf buffer) {
        buffer.writeVarInt(message.action());
        buffer.writeVarInt(message.hand());
        buffer.writeNbt(message.data());
    }

    public static ServerSurveyActionPayload decode(FriendlyByteBuf buffer) {
        int action = buffer.readVarInt();
        int hand = buffer.readVarInt();
        CompoundTag data = buffer.readNbt();
        return new ServerSurveyActionPayload(action, hand, data == null ? new CompoundTag() : data);
    }

    public static void handle(ServerSurveyActionPayload message, Supplier<NetworkEvent.Context> contextSupplier) {
        SurveyNetworking.handleServerAction(message, contextSupplier);
    }
}
