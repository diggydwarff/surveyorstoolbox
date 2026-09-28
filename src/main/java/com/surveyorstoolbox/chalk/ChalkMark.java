package com.surveyorstoolbox.chalk;

import com.surveyorstoolbox.measurement.MeasurementSnapshot;
import net.minecraft.core.UUIDUtil;
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
        tag.store("Id", UUIDUtil.CODEC, id);
        tag.store("Owner", UUIDUtil.CODEC, owner);
        tag.putString("OwnerName", ownerName);
        tag.putInt("Color", color.getId());
        tag.put("Measurement", measurement.toTag());
        return tag;
    }

    public static ChalkMark fromTag(CompoundTag tag) {
        UUID id = tag.read("Id", UUIDUtil.CODEC).orElseGet(UUID::randomUUID);
        UUID owner = tag.read("Owner", UUIDUtil.CODEC).orElse(new UUID(0L, 0L));
        String ownerName = tag.getStringOr("OwnerName", "");
        DyeColor color = DyeColor.byId(tag.getIntOr("Color", DyeColor.WHITE.getId()));
        MeasurementSnapshot measurement = MeasurementSnapshot.fromTag(tag.getCompoundOrEmpty("Measurement"));
        return new ChalkMark(id, owner, ownerName, color, measurement);
    }
}
