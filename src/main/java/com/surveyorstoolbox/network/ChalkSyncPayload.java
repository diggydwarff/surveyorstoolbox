package com.surveyorstoolbox.network;

import com.surveyorstoolbox.client.ClientPacketHandler;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record ChalkSyncPayload(CompoundTag data) {
    public static void encode(ChalkSyncPayload message, FriendlyByteBuf buffer) {
        buffer.writeNbt(message.data());
    }

    public static ChalkSyncPayload decode(FriendlyByteBuf buffer) {
        CompoundTag tag = buffer.readNbt();
        return new ChalkSyncPayload(tag == null ? new CompoundTag() : tag);
    }

    public static void handle(ChalkSyncPayload message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> ClientPacketHandler.handleChalkSync(message)
        ));
        context.setPacketHandled(true);
    }
}
