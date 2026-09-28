package com.surveyorstoolbox.client;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.surveyorstoolbox.SurveyorsToolbox;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;

/**
 * Render types used by the survey overlays.
 *
 * Minecraft 1.21.5+ moved depth/blend state out of RenderType state shards and
 * into RenderPipeline.  The x-ray pass therefore needs its own pipeline instead
 * of reusing RenderType.lines(), otherwise it is depth-tested exactly like the
 * solid core line and disappears behind blocks.
 */
@EventBusSubscriber(modid = SurveyorsToolbox.MOD_ID, value = Dist.CLIENT)
public final class SurveyRenderTypes {
    private static final RenderPipeline XRAY_LINE_PIPELINE = RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
            .withLocation(ResourceLocation.fromNamespaceAndPath(SurveyorsToolbox.MOD_ID, "xray_lines"))
            .withBlend(BlendFunction.TRANSLUCENT)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .build();

    public static final RenderType XRAY_LINES = RenderType.create(
            SurveyorsToolbox.MOD_ID + ":xray_lines",
            256,
            false,
            true,
            XRAY_LINE_PIPELINE,
            RenderType.CompositeState.builder().createCompositeState(RenderType.OutlineProperty.NONE)
    );

    @SubscribeEvent
    public static void registerPipelines(RegisterRenderPipelinesEvent event) {
        event.registerPipeline(XRAY_LINE_PIPELINE);
    }

    private SurveyRenderTypes() {}
}
