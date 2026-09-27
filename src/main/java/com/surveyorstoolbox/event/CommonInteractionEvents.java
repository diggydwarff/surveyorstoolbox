package com.surveyorstoolbox.event;

import com.surveyorstoolbox.SurveyorsToolbox;
import com.surveyorstoolbox.client.SurveyClientActions;
import com.surveyorstoolbox.item.ArchitectsEraserItem;
import com.surveyorstoolbox.item.ChalkItem;
import com.surveyorstoolbox.item.GlowChalkItem;
import com.surveyorstoolbox.measurement.SurveyManager;
import com.surveyorstoolbox.registry.ModItems;
import net.minecraft.world.InteractionResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

@EventBusSubscriber(modid = SurveyorsToolbox.MOD_ID)
public final class CommonInteractionEvents {
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getItemStack().is(ModItems.SURVEYORS_RULER.get())) {
            if (event.getEntity().level().isClientSide()) {
                SurveyManager.handleBlockUse(event.getPos(), event.getEntity());
            }
            cancel(event);
            return;
        }

        if (event.getItemStack().getItem() instanceof ChalkItem || event.getItemStack().getItem() instanceof GlowChalkItem) {
            if (event.getEntity().level().isClientSide()) {
                SurveyClientActions.commitChalk(event.getEntity(), event.getHand());
            }
            cancel(event);
            return;
        }

        if (event.getItemStack().getItem() instanceof ArchitectsEraserItem) {
            if (event.getEntity().level().isClientSide()) {
                if (event.getEntity().isShiftKeyDown()) {
                    SurveyClientActions.clearOwnChalk(event.getHand());
                } else {
                    SurveyClientActions.eraseNearest(event.getHand(), event.getPos().asLong());
                }
            }
            cancel(event);
            return;
        }

    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getItemStack().is(ModItems.SURVEYORS_RULER.get())) {
            if (!event.getEntity().isShiftKeyDown()) return;
            if (event.getEntity().level().isClientSide()) {
                SurveyManager.handleAirUse(event.getEntity());
            }
            cancel(event);
            return;
        }

        if (event.getItemStack().getItem() instanceof ChalkItem || event.getItemStack().getItem() instanceof GlowChalkItem) {
            if (event.getEntity().level().isClientSide()) {
                SurveyClientActions.commitChalk(event.getEntity(), event.getHand());
            }
            cancel(event);
            return;
        }

        if (event.getItemStack().getItem() instanceof ArchitectsEraserItem) {
            if (!event.getEntity().isShiftKeyDown()) return;
            if (event.getEntity().level().isClientSide()) {
                SurveyClientActions.clearOwnChalk(event.getHand());
            }
            cancel(event);
            return;
        }

    }

    private static void cancel(PlayerInteractEvent.RightClickBlock event) {
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    private static void cancel(PlayerInteractEvent.RightClickItem event) {
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    private CommonInteractionEvents() { }
}
