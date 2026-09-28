package com.surveyorstoolbox.network;

import com.surveyorstoolbox.chalk.ChalkMark;
import com.surveyorstoolbox.chalk.ChalkSavedData;
import com.surveyorstoolbox.client.ChalkClientStore;
import com.surveyorstoolbox.item.ArchitectsEraserItem;
import com.surveyorstoolbox.item.ChalkItem;
import com.surveyorstoolbox.measurement.MeasurementSnapshot;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SurveyNetworking {
    private static final double SYNC_RADIUS = 128.0D;
    private static final int MAX_SYNC_MARKS = 128;
    private static final int SYNC_CHUNK_SIZE = 8;

    private static final double MAX_COMMIT_DISTANCE = 48.0D;
    private static final double MAX_ERASER_TARGET_DISTANCE = 8.0D;
    private static final long MIN_SYNC_REQUEST_TICKS = 10L;

    private static final Map<UUID, Long> LAST_SYNC_REQUEST = new HashMap<>();

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");

        registrar.playToServer(
                ServerSurveyActionPayload.TYPE,
                ServerSurveyActionPayload.STREAM_CODEC,
                SurveyNetworking::handleServerAction
        );

        registrar.playToClient(
                ChalkSyncPayload.TYPE,
                ChalkSyncPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ChalkClientStore.applySync(payload.data()))
        );
    }

    private static void handleServerAction(ServerSurveyActionPayload payload,
                                           net.neoforged.neoforge.network.handling.IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;

        // Payload callbacks are not a safe place to mutate level, inventory or
        // SavedData directly. Move all server actions onto the player's server thread.
        context.enqueueWork(() -> handleServerActionOnMainThread(payload, player));
    }

    private static void handleServerActionOnMainThread(ServerSurveyActionPayload payload,
                                                       ServerPlayer player) {
        InteractionHand hand = payload.hand() == 1 ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        ItemStack held = player.getItemInHand(hand);

        switch (payload.action()) {
            case ServerSurveyActionPayload.REQUEST_SYNC -> {
                long now = player.level().getGameTime();
                long previous = LAST_SYNC_REQUEST.getOrDefault(player.getUUID(), Long.MIN_VALUE / 2L);
                if (now - previous < MIN_SYNC_REQUEST_TICKS) return;

                LAST_SYNC_REQUEST.put(player.getUUID(), now);
                if (LAST_SYNC_REQUEST.size() > 1024) {
                    LAST_SYNC_REQUEST.keySet().removeIf(uuid ->
                            player.getServer() == null || player.getServer().getPlayerList().getPlayer(uuid) == null
                    );
                }
                sendSync(player);
            }

            case ServerSurveyActionPayload.COMMIT_CHALK -> {
                if (!(held.getItem() instanceof ChalkItem chalk)) return;

                MeasurementSnapshot snapshot = MeasurementSnapshot.fromTag(payload.data()).compacted();
                if (!validSnapshotNearPlayer(snapshot, player)) {
                    status(player, "That measurement cannot be chalked from here.", ChatFormatting.YELLOW);
                    return;
                }

                ChalkSavedData.AddResult result = ChalkSavedData.get(player.level())
                        .add(player, chalk.color(), snapshot);

                switch (result) {
                    case ADDED -> {
                        held.hurtAndBreak(
                                1,
                                player.level(),
                                player,
                                item -> player.onEquippedItemBroken(item, LivingEntity.getSlotForHand(hand))
                        );
                        status(player, capitalize(chalk.color().getName()) + " chalk guide saved.", ChatFormatting.GREEN);
                        syncDimension(player.level());
                    }
                    case DUPLICATE ->
                            status(player, "That measurement is already chalked in this color.", ChatFormatting.GRAY);
                    case FULL ->
                            status(player, "This dimension has reached its persistent chalk-mark limit.", ChatFormatting.RED);
                    case INVALID ->
                            status(player, "That measurement is too large or invalid to chalk.", ChatFormatting.YELLOW);
                }
            }

            case ServerSurveyActionPayload.ERASE_NEAREST -> {
                if (!(held.getItem() instanceof ArchitectsEraserItem)) return;

                Vec3 target = Vec3Target.from(payload.data().getLongOr("Target", BlockPos.ZERO.asLong())).vec();
                if (player.getEyePosition().distanceTo(target) > MAX_ERASER_TARGET_DISTANCE) {
                    status(player, "That chalk mark is too far away.", ChatFormatting.GRAY);
                    return;
                }

                var result = ChalkSavedData.get(player.level()).removeNearest(player, target, 5.5D);
                switch (result) {
                    case REMOVED -> {
                        status(player, "Chalk guide erased.", ChatFormatting.GREEN);
                        syncDimension(player.level());
                    }
                    case NOT_OWNER ->
                            status(player, "That chalk guide belongs to another player.", ChatFormatting.RED);
                    case NONE ->
                            status(player, "No chalk guide found nearby.", ChatFormatting.GRAY);
                }
            }

            case ServerSurveyActionPayload.CLEAR_OWN_CHALK -> {
                if (!(held.getItem() instanceof ArchitectsEraserItem)) return;

                int removed = ChalkSavedData.get(player.level()).clearOwned(player.getUUID());
                status(player, "Cleared " + removed + " of your chalk guides.", ChatFormatting.GRAY);
                if (removed > 0) syncDimension(player.level());
            }


            default -> { }
        }
    }

    private static boolean validSnapshotNearPlayer(MeasurementSnapshot snapshot, ServerPlayer player) {
        if (!snapshot.isValidForPersistence()) return false;
        double distance = snapshot.distanceTo(player.position());
        return Double.isFinite(distance) && distance <= MAX_COMMIT_DISTANCE;
    }


    private static void syncDimension(ServerLevel level) {
        for (ServerPlayer player : level.players()) {
            sendSync(player);
        }
    }

    private static void sendSync(ServerPlayer player) {
        List<ChalkMark> marks = ChalkSavedData.get(player.level())
                .near(player.position(), SYNC_RADIUS, MAX_SYNC_MARKS);

        if (marks.isEmpty()) {
            sendSyncChunk(player, List.of(), true);
            return;
        }

        boolean reset = true;
        for (int start = 0; start < marks.size(); start += SYNC_CHUNK_SIZE) {
            int end = Math.min(marks.size(), start + SYNC_CHUNK_SIZE);
            sendSyncChunk(player, marks.subList(start, end), reset);
            reset = false;
        }
    }

    private static void sendSyncChunk(ServerPlayer player, List<ChalkMark> marks, boolean reset) {
        ListTag list = new ListTag();
        for (ChalkMark mark : marks) {
            list.add(mark.toTag());
        }

        CompoundTag tag = new CompoundTag();
        tag.putBoolean("Reset", reset);
        tag.put("Marks", list);
        PacketDistributor.sendToPlayer(player, new ChalkSyncPayload(tag));
    }

    private static void status(ServerPlayer player, String text, ChatFormatting color) {
        player.displayClientMessage(Component.literal(text).withStyle(color), true);
    }

    private static String capitalize(String input) {
        if (input == null || input.isBlank()) return "Chalk";
        return Character.toUpperCase(input.charAt(0)) + input.substring(1).replace('_', ' ');
    }

    private record Vec3Target(double x, double y, double z) {
        static Vec3Target from(long packed) {
            BlockPos pos = BlockPos.of(packed);
            return new Vec3Target(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
        }

        Vec3 vec() {
            return new Vec3(x, y, z);
        }
    }

    private SurveyNetworking() { }
}
