package com.surveyorstoolbox.event;

import com.surveyorstoolbox.SurveyorsToolbox;
import com.surveyorstoolbox.client.SurveyClientActions;
import com.surveyorstoolbox.item.ArchitectsEraserItem;
import com.surveyorstoolbox.item.ChalkItem;
import com.surveyorstoolbox.measurement.SurveyManager;
import com.surveyorstoolbox.registry.ModItems;
import net.minecraft.world.InteractionResult;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = SurveyorsToolbox.MOD_ID)
public final class CommonInteractionEvents {
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getItemStack().is(ModItems.SURVEYORS_RULER.get())) {
            if (event.getEntity().getLevel().isClientSide()) {
                SurveyManager.handleBlockUse(event.getPos(), event.getEntity());
            }
            cancel(event);
            return;
        }

        if (event.getItemStack().getItem() instanceof ChalkItem) {
            if (event.getEntity().getLevel().isClientSide()) {
                SurveyClientActions.commitChalk(event.getEntity(), event.getHand());
            }
            cancel(event);
            return;
        }

        if (event.getItemStack().getItem() instanceof ArchitectsEraserItem) {
            if (event.getEntity().getLevel().isClientSide()) {
                if (event.getEntity().isShiftKeyDown()) {
                    SurveyClientActions.clearOwnChalk(event.getHand());
                } else {
                    SurveyClientActions.eraseNearest(event.getHand(), event.getPos().asLong());
                }
            }
            cancel(event);
        }
    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getItemStack().is(ModItems.SURVEYORS_RULER.get())) {
            if (!event.getEntity().isShiftKeyDown()) return;
            if (event.getEntity().getLevel().isClientSide()) {
                SurveyManager.handleAirUse(event.getEntity());
            }
            cancel(event);
            return;
        }

        if (event.getItemStack().getItem() instanceof ChalkItem) {
            if (event.getEntity().getLevel().isClientSide()) {
                SurveyClientActions.commitChalk(event.getEntity(), event.getHand());
            }
            cancel(event);
            return;
        }

        if (event.getItemStack().getItem() instanceof ArchitectsEraserItem) {
            if (!event.getEntity().isShiftKeyDown()) return;
            if (event.getEntity().getLevel().isClientSide()) {
                SurveyClientActions.clearOwnChalk(event.getHand());
            }
            cancel(event);
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
