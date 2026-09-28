package com.surveyorstoolbox.client;

import com.surveyorstoolbox.network.ChalkSyncPayload;

public final class ClientPacketHandler {
    public static void handleChalkSync(ChalkSyncPayload payload) {
        ChalkClientStore.applySync(payload.data());
    }

    private ClientPacketHandler() { }
}
