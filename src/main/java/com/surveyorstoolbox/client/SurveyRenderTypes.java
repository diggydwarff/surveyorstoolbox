package com.surveyorstoolbox.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

import java.util.OptionalDouble;

/**
 * Custom client render types used by the survey overlays on Minecraft 1.19.2.
 */
public final class SurveyRenderTypes extends RenderStateShard {
    /**
     * Faint no-depth-test pass used underneath normal survey lines so occluded
     * geometry remains visible through terrain without writing to the depth buffer.
     */
    public static final RenderType XRAY_LINES = RenderType.create(
            "surveyors_toolbox_xray_lines",
            DefaultVertexFormat.POSITION_COLOR_NORMAL,
            VertexFormat.Mode.LINES,
            256,
            false,
            false,
            RenderType.CompositeState.builder()
                    .setShaderState(RENDERTYPE_LINES_SHADER)
                    .setLineState(DEFAULT_LINE)
                    .setLayeringState(NO_LAYERING)
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setDepthTestState(NO_DEPTH_TEST)
                    .setCullState(NO_CULL)
                    .setLightmapState(NO_LIGHTMAP)
                    .setWriteMaskState(COLOR_WRITE)
                    .createCompositeState(false)
    );

    /**
     * Translucent, depth-tested geometry for node/surface/polygon fills.
     * Depth writes are disabled so overlapping translucent survey geometry does
     * not incorrectly hide later overlay geometry.
     */
    public static final RenderType SURVEY_FILL = RenderType.create(
            "surveyors_toolbox_fill",
            DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS,
            256,
            false,
            true,
            RenderType.CompositeState.builder()
                    .setShaderState(POSITION_COLOR_SHADER)
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setDepthTestState(LEQUAL_DEPTH_TEST)
                    .setCullState(NO_CULL)
                    .setLightmapState(NO_LIGHTMAP)
                    .setWriteMaskState(COLOR_WRITE)
                    .createCompositeState(false)
    );

    public static final RenderType GLOW_OUTER_LINES = translucentLineStrip(
            "surveyors_toolbox_glow_outer", 7.0D
    );

    public static final RenderType GLOW_INNER_LINES = translucentLineStrip(
            "surveyors_toolbox_glow_inner", 3.5D
    );

    private static RenderType translucentLineStrip(String name, double width) {
        return RenderType.create(
                name,
                DefaultVertexFormat.POSITION_COLOR,
                VertexFormat.Mode.LINE_STRIP,
                256,
                false,
                false,
                RenderType.CompositeState.builder()
                        .setShaderState(POSITION_COLOR_SHADER)
                        .setLineState(new LineStateShard(OptionalDouble.of(width)))
                        .setLayeringState(NO_LAYERING)
                        .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                        .setDepthTestState(LEQUAL_DEPTH_TEST)
                        .setCullState(NO_CULL)
                        .setLightmapState(NO_LIGHTMAP)
                        .setWriteMaskState(COLOR_WRITE)
                        .createCompositeState(false)
        );
    }

    private SurveyRenderTypes() {
        super("surveyors_toolbox_render_types", () -> {}, () -> {});
    }
}
