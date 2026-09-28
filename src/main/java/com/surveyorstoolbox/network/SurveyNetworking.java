package com.surveyorstoolbox.network;

import com.surveyorstoolbox.SurveyorsToolbox;
import com.surveyorstoolbox.chalk.ChalkMark;
import com.surveyorstoolbox.chalk.ChalkSavedData;
import com.surveyorstoolbox.item.ArchitectsEraserItem;
import com.surveyorstoolbox.item.ChalkItem;
import com.surveyorstoolbox.measurement.MeasurementSnapshot;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

public final class SurveyNetworking {
    private static final String PROTOCOL_VERSION = "1";

    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(SurveyorsToolbox.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private static final double SYNC_RADIUS = 128.0D;
    private static final int MAX_SYNC_MARKS = 128;
    private static final int SYNC_CHUNK_SIZE = 8;
    private static final double MAX_COMMIT_DISTANCE = 48.0D;
    private static final double MAX_ERASER_TARGET_DISTANCE = 8.0D;
    private static final long MIN_SYNC_REQUEST_TICKS = 10L;
    private static final Map<UUID, Long> LAST_SYNC_REQUEST = new HashMap<>();

    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(
                id++, ServerSurveyActionPayload.class,
                ServerSurveyActionPayload::encode,
                ServerSurveyActionPayload::decode,
                ServerSurveyActionPayload::handle
        );
        CHANNEL.registerMessage(
                id, ChalkSyncPayload.class,
                ChalkSyncPayload::encode,
                ChalkSyncPayload::decode,
                ChalkSyncPayload::handle
        );
    }

    public static void sendToServer(ServerSurveyActionPayload payload) {
        CHANNEL.sendToServer(payload);
    }

    public static void handleServerAction(ServerSurveyActionPayload payload,
                                          Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer player = context.getSender();
        if (player == null) {
            context.setPacketHandled(true);
            return;
        }

        context.enqueueWork(() -> handleServerActionOnMainThread(payload, player));
        context.setPacketHandled(true);
    }

    private static void handleServerActionOnMainThread(ServerSurveyActionPayload payload, ServerPlayer player) {
        InteractionHand hand = payload.hand() == 1 ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        ItemStack held = player.getItemInHand(hand);
        ServerLevel level = player.getLevel();

        switch (payload.action()) {
            case ServerSurveyActionPayload.REQUEST_SYNC -> {
                long now = level.getGameTime();
                long previous = LAST_SYNC_REQUEST.getOrDefault(player.getUUID(), Long.MIN_VALUE / 2L);
                if (now - previous < MIN_SYNC_REQUEST_TICKS) return;

                LAST_SYNC_REQUEST.put(player.getUUID(), now);
                if (LAST_SYNC_REQUEST.size() > 1024) {
                    LAST_SYNC_REQUEST.keySet().removeIf(uuid ->
                            player.getServer() == null
                                    || player.getServer().getPlayerList().getPlayer(uuid) == null
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

                ChalkSavedData.AddResult result = ChalkSavedData.get(level).add(player, chalk.color(), snapshot);
                switch (result) {
                    case ADDED -> {
                        held.hurtAndBreak(1, player, entity -> entity.broadcastBreakEvent(hand));
                        status(player, capitalize(chalk.color().getName()) + " chalk guide saved.", ChatFormatting.GREEN);
                        syncDimension(level);
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

                Vec3 target = Vec3Target.from(payload.data().getLong("Target")).vec();
                if (player.getEyePosition().distanceTo(target) > MAX_ERASER_TARGET_DISTANCE) {
                    status(player, "That chalk mark is too far away.", ChatFormatting.GRAY);
                    return;
                }

                ChalkSavedData.RemovalResult result = ChalkSavedData.get(level).removeNearest(player, target, 5.5D);
                switch (result) {
                    case REMOVED -> {
                        status(player, "Chalk guide erased.", ChatFormatting.GREEN);
                        syncDimension(level);
                    }
                    case NOT_OWNER ->
                            status(player, "That chalk guide belongs to another player.", ChatFormatting.RED);
                    case NONE ->
                            status(player, "No chalk guide found nearby.", ChatFormatting.GRAY);
                }
            }

            case ServerSurveyActionPayload.CLEAR_OWN_CHALK -> {
                if (!(held.getItem() instanceof ArchitectsEraserItem)) return;

                int removed = ChalkSavedData.get(level).clearOwned(player.getUUID());
                status(player, "Cleared " + removed + " of your chalk guides.", ChatFormatting.GRAY);
                if (removed > 0) syncDimension(level);
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
        for (ServerPlayer player : level.players()) sendSync(player);
    }

    private static void sendSync(ServerPlayer player) {
        List<ChalkMark> marks = ChalkSavedData.get(player.getLevel())
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
        for (ChalkMark mark : marks) list.add(mark.toTag());

        CompoundTag tag = new CompoundTag();
        tag.putBoolean("Reset", reset);
        tag.put("Marks", list);
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new ChalkSyncPayload(tag));
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

        Vec3 vec() { return new Vec3(x, y, z); }
    }

    private SurveyNetworking() { }
}
