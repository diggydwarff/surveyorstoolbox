package com.surveyorstoolbox.client;

import com.surveyorstoolbox.measurement.MeasurementSnapshot;
import com.surveyorstoolbox.measurement.SurveyManager;
import com.surveyorstoolbox.network.ServerSurveyActionPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

public final class SurveyClientActions {
    public static void commitChalk(Player player, InteractionHand hand) {
        MeasurementSnapshot snapshot = SurveyManager.snapshotForPersistence();
        if (snapshot == null) {
            status(player, "Finish a measurement before using chalk.", ChatFormatting.GRAY);
            return;
        }
        if (!snapshot.isValidForPersistence()) {
            status(player, "This measurement is too large to chalk.", ChatFormatting.YELLOW);
            return;
        }
        ClientPacketDistributor.sendToServer(ServerSurveyActionPayload.commit(hand, snapshot.toTag()));
    }



    public static void eraseNearest(InteractionHand hand, long clickedPos) {
        ClientPacketDistributor.sendToServer(ServerSurveyActionPayload.erase(hand, clickedPos));
    }

    public static void clearOwnChalk(InteractionHand hand) {
        ClientPacketDistributor.sendToServer(ServerSurveyActionPayload.clearOwn(hand));
    }

    private static void status(Player player, String text, ChatFormatting color) {
        player.displayClientMessage(Component.literal(text).withStyle(color), true);
    }

    private SurveyClientActions() { }
}
