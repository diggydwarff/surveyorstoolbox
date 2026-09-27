package com.surveyorstoolbox.chalk;

import com.surveyorstoolbox.measurement.MeasurementSnapshot;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class ChalkSavedData extends SavedData {
    private static final String DATA_NAME = "surveyors_toolbox_chalk";
    private static final int MAX_MARKS_PER_DIMENSION = 4096;

    private final List<ChalkMark> marks = new ArrayList<>();

    public static ChalkSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(ChalkSavedData::new, ChalkSavedData::load),
                DATA_NAME
        );
    }

    public static ChalkSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        ChalkSavedData data = new ChalkSavedData();
        ListTag list = tag.getList("Marks", Tag.TAG_COMPOUND);

        for (int i = 0; i < list.size() && data.marks.size() < MAX_MARKS_PER_DIMENSION; i++) {
            ChalkMark mark = ChalkMark.fromTag(list.getCompound(i));
            if (mark.measurement().isValidForPersistence()) {
                data.marks.add(mark);
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (ChalkMark mark : marks) {
            list.add(mark.toTag());
        }
        tag.put("Marks", list);
        return tag;
    }

    public AddResult saveColored(ServerPlayer player, DyeColor color, MeasurementSnapshot measurement) {
        MeasurementSnapshot compact = measurement.compacted();
        if (!compact.isValidForPersistence()) return AddResult.INVALID;

        int existingIndex = findOwnedMeasurement(player.getUUID(), compact);
        if (existingIndex >= 0) {
            ChalkMark existing = marks.get(existingIndex);
            if (existing.color() == color && existing.glowing()) {
                return AddResult.DUPLICATE;
            }
            if (existing.color() == color && !existing.glowing()) {
                return AddResult.DUPLICATE;
            }

            marks.set(existingIndex, new ChalkMark(
                    existing.id(),
                    existing.owner(),
                    existing.ownerName(),
                    color,
                    existing.glowing(),
                    compact
            ));
            setDirty();
            return AddResult.UPDATED;
        }

        if (marks.size() >= MAX_MARKS_PER_DIMENSION) {
            return AddResult.FULL;
        }

        marks.add(new ChalkMark(
                UUID.randomUUID(),
                player.getUUID(),
                player.getGameProfile().getName(),
                color,
                false,
                compact
        ));
        setDirty();
        return AddResult.ADDED;
    }

    public AddResult saveGlow(ServerPlayer player, MeasurementSnapshot measurement) {
        MeasurementSnapshot compact = measurement.compacted();
        if (!compact.isValidForPersistence()) return AddResult.INVALID;

        int existingIndex = findOwnedMeasurement(player.getUUID(), compact);
        if (existingIndex >= 0) {
            ChalkMark existing = marks.get(existingIndex);
            if (existing.glowing()) {
                return AddResult.DUPLICATE;
            }

            marks.set(existingIndex, new ChalkMark(
                    existing.id(),
                    existing.owner(),
                    existing.ownerName(),
                    existing.color(),
                    true,
                    compact
            ));
            setDirty();
            return AddResult.UPDATED;
        }

        if (marks.size() >= MAX_MARKS_PER_DIMENSION) {
            return AddResult.FULL;
        }

        marks.add(new ChalkMark(
                UUID.randomUUID(),
                player.getUUID(),
                player.getGameProfile().getName(),
                DyeColor.WHITE,
                true,
                compact
        ));
        setDirty();
        return AddResult.ADDED;
    }

    private int findOwnedMeasurement(UUID owner, MeasurementSnapshot measurement) {
        for (int i = 0; i < marks.size(); i++) {
            ChalkMark mark = marks.get(i);
            if (mark.owner().equals(owner) && mark.measurement().equals(measurement)) {
                return i;
            }
        }
        return -1;
    }

    public List<ChalkMark> near(Vec3 center, double radius, int limit) {
        return marks.stream()
                .map(mark -> new MarkDistance(mark, mark.measurement().distanceTo(center)))
                .filter(entry -> entry.distance() <= radius)
                .sorted(Comparator.comparingDouble(MarkDistance::distance))
                .limit(limit)
                .map(MarkDistance::mark)
                .toList();
    }

    public RemovalResult removeNearest(ServerPlayer player, Vec3 target, double maxDistance) {
        ChalkMark nearest = null;
        double best = maxDistance;

        for (ChalkMark mark : marks) {
            double distance = mark.measurement().distanceTo(target);
            if (distance < best) {
                nearest = mark;
                best = distance;
            }
        }

        if (nearest == null) return RemovalResult.NONE;
        if (!nearest.owner().equals(player.getUUID()) && !player.hasPermissions(2)) {
            return RemovalResult.NOT_OWNER;
        }

        marks.remove(nearest);
        setDirty();
        return RemovalResult.REMOVED;
    }

    public int clearOwned(UUID owner) {
        int before = marks.size();
        marks.removeIf(mark -> mark.owner().equals(owner));
        int removed = before - marks.size();
        if (removed > 0) setDirty();
        return removed;
    }

    public enum AddResult {
        ADDED,
        UPDATED,
        DUPLICATE,
        FULL,
        INVALID
    }

    public enum RemovalResult {
        NONE,
        NOT_OWNER,
        REMOVED
    }

    private record MarkDistance(ChalkMark mark, double distance) { }
}
