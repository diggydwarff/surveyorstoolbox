package com.surveyorstoolbox.chalk;

import com.surveyorstoolbox.measurement.MeasurementSnapshot;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.DyeColor;

import java.util.UUID;

public record ChalkMark(
        UUID id,
        UUID owner,
        String ownerName,
        DyeColor color,
        MeasurementSnapshot measurement
) {
    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putUUID("Owner", owner);
        tag.putString("OwnerName", ownerName);
        tag.putInt("Color", color.getId());
        tag.put("Measurement", measurement.toTag());
        return tag;
    }

    public static ChalkMark fromTag(CompoundTag tag) {
        UUID id = tag.hasUUID("Id") ? tag.getUUID("Id") : UUID.randomUUID();
        UUID owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : new UUID(0L, 0L);
        String ownerName = tag.getString("OwnerName");
        DyeColor color = DyeColor.byId(tag.getInt("Color"));
        MeasurementSnapshot measurement = MeasurementSnapshot.fromTag(tag.getCompound("Measurement"));
        return new ChalkMark(id, owner, ownerName, color, measurement);
    }
}
