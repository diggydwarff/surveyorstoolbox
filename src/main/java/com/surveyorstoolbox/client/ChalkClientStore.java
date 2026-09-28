package com.surveyorstoolbox.client;

import com.surveyorstoolbox.chalk.ChalkMark;
import com.surveyorstoolbox.network.ServerSurveyActionPayload;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ChalkClientStore {
    private static final List<ChalkMark> MARKS = new ArrayList<>();
    private static final List<ChalkMark> VIEW = Collections.unmodifiableList(MARKS);

    private static Level activeLevel;
    private static Vec3 lastRequestPosition;
    private static long lastRequestGameTime = Long.MIN_VALUE;

    public static void tick(Player player) {
        if (activeLevel != player.level()) {
            activeLevel = player.level();
            MARKS.clear();
            lastRequestPosition = null;
            lastRequestGameTime = Long.MIN_VALUE;
        }

        long now = player.level().getGameTime();
        boolean moved = lastRequestPosition == null
                || player.position().distanceToSqr(lastRequestPosition) > 24.0D * 24.0D;
        boolean stale = now - lastRequestGameTime >= 100L;

        if (lastRequestPosition == null || moved || stale) {
            ClientPacketDistributor.sendToServer(ServerSurveyActionPayload.requestSync());
            lastRequestPosition = player.position();
            lastRequestGameTime = now;
        }
    }

    public static void applySync(CompoundTag tag) {
        if (tag.getBooleanOr("Reset", false)) {
            MARKS.clear();
        }

        ListTag list = tag.getListOrEmpty("Marks");
        for (int i = 0; i < list.size(); i++) {
            CompoundTag markTag = list.getCompound(i).orElse(null);
            if (markTag == null) continue;

            ChalkMark mark = ChalkMark.fromTag(markTag);
            if (!mark.measurement().isValidForPersistence()) continue;

            MARKS.removeIf(existing -> existing.id().equals(mark.id()));
            MARKS.add(mark);
        }
    }

    public static List<ChalkMark> marks() {
        return VIEW;
    }

    public static void clear() {
        MARKS.clear();
        activeLevel = null;
        lastRequestPosition = null;
        lastRequestGameTime = Long.MIN_VALUE;
    }

    private ChalkClientStore() { }
}
