package com.surveyorstoolbox.client;

import com.surveyorstoolbox.chalk.ChalkMark;
import com.surveyorstoolbox.network.ServerSurveyActionPayload;
import com.surveyorstoolbox.network.SurveyNetworking;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

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
        if (activeLevel != player.getLevel()) {
            activeLevel = player.getLevel();
            MARKS.clear();
            lastRequestPosition = null;
            lastRequestGameTime = Long.MIN_VALUE;
        }

        long now = player.getLevel().getGameTime();
        boolean moved = lastRequestPosition == null
                || player.position().distanceToSqr(lastRequestPosition) > 24.0D * 24.0D;
        boolean stale = now - lastRequestGameTime >= 100L;

        if (lastRequestPosition == null || moved || stale) {
            SurveyNetworking.sendToServer(ServerSurveyActionPayload.requestSync());
            lastRequestPosition = player.position();
            lastRequestGameTime = now;
        }
    }

    public static void applySync(CompoundTag tag) {
        if (tag.getBoolean("Reset")) {
            MARKS.clear();
        }

        ListTag list = tag.getList("Marks", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            ChalkMark mark = ChalkMark.fromTag(list.getCompound(i));
            if (!mark.measurement().isValidForPersistence()) continue;

            MARKS.removeIf(existing -> existing.id().equals(mark.id()));
            MARKS.add(mark);
        }
    }

    public static List<ChalkMark> marks() { return VIEW; }

    public static void clear() {
        MARKS.clear();
        activeLevel = null;
        lastRequestPosition = null;
        lastRequestGameTime = Long.MIN_VALUE;
    }

    private ChalkClientStore() { }
}
