package com.surveyorstoolbox.client;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.surveyorstoolbox.SurveyorsToolbox;
import com.surveyorstoolbox.measurement.MeasurementMode;
import com.surveyorstoolbox.measurement.SurveyManager;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Client-only world renderer for temporary measurements.
 *
 * The visual language is intentionally very obvious:
 * - cyan while a measurement is active
 * - green after it is finalized
 * - a wide translucent glow underneath a sharp core line
 * - faint translucent fills for measurable surfaces
 *
 * Nothing here creates entities or modifies the level.
 */
@EventBusSubscriber(modid = SurveyorsToolbox.MOD_ID, value = Dist.CLIENT)
public final class SurveyRenderer {
    private static final NeonColor ACTIVE = new NeonColor(0.05f, 0.95f, 1.00f);
    private static final NeonColor COMPLETE = new NeonColor(0.20f, 1.00f, 0.35f);
    private static final NeonColor GUIDE = new NeonColor(1.00f, 0.83f, 0.12f);
    private static final NeonColor RISE = new NeonColor(1.00f, 0.18f, 0.72f);

    /**
     * Faint no-depth-test pass. The normal neon line still depth-tests, while this
     * pass keeps buried/occluded portions readable through terrain.
     */
    // Dedicated no-depth-test translucent pass; this keeps occluded portions
    // faintly visible through terrain, matching the older mod versions.
    private static final RenderType XRAY_LINES = SurveyRenderTypes.XRAY_LINES;

    private static final double GLOW_OUTER_WIDTH = 7.0D;
    private static final double GLOW_INNER_WIDTH = 3.5D;
    private static final int CIRCLE_SEGMENTS = 96;
    private static final int MAX_FILL_VERTICES = 256;
    private static final int MAX_SPACING_TICKS = 160;
    private static final int MAX_RENDERED_WALK_POINTS = 4096;
    private static final int MAX_RENDERED_NODES = 256;
    private static final float XRAY_ALPHA = 0.42f;
    private static final float LABEL_SCALE = 0.028f;
    private static final double LABEL_MAX_DISTANCE = 48.0D;
    private static final int LABEL_MAX_CHARS = 58;
    private static final double LABEL_HEIGHT = 0.82D;

    // Keep survey geometry off Minecraft's shared world-render buffers.
    // RenderLevelStageEvent fires while vanilla/other mods may be finishing those
    // buffers; owning this source prevents stale BufferBuilder instances.
    private static final ByteBufferBuilder SURVEY_BYTE_BUFFER = new ByteBufferBuilder(2 * 1024 * 1024);
    private static final MultiBufferSource.BufferSource SURVEY_BUFFERS = MultiBufferSource.immediate(SURVEY_BYTE_BUFFER);


    private static long cachedInteriorRevision = Long.MIN_VALUE;
    private static List<FillBox> cachedInteriorRuns = List.of();
    private static long cachedPolygonRevision = Long.MIN_VALUE;
    private static List<Triangle> cachedPolygonTriangles = List.of();

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent.AfterParticles event) {
        if (!SurveyManager.hasRenderableMeasurement()) return;

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) return;

        PoseStack poseStack = event.getPoseStack();
        if (poseStack == null) return;

        Camera camera = event.getCamera();
        Vec3 cameraPos = camera.getPosition();
        MultiBufferSource.BufferSource buffers = SURVEY_BUFFERS;

        poseStack.pushPose();
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        NeonColor primary = SurveyManager.isFinalized() ? COMPLETE : ACTIVE;
        renderMeasurement(poseStack, buffers, primary);
        if (SurveyManager.isFinalized()) {
            renderHologramStem(poseStack, buffers);
        }

        // Flush geometry. The text readout is projected into the HUD separately;
        // this avoids the unreliable world-font buffer path in RenderLevelStageEvent.
        buffers.endBatch();

        poseStack.popPose();
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (!SurveyManager.isFinalized()) return;

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) return;

        Vec3 anchor = findLabelAnchor(
                SurveyManager.getPointsForRender(),
                SurveyManager.getWalkPathForRender(),
                SurveyManager.getInteriorCellsForRender()
        );
        if (anchor == null) return;

        Camera camera = minecraft.gameRenderer.getMainCamera();
        Vec3 cameraPos = camera.getPosition();
        if (anchor.distanceToSqr(cameraPos) > LABEL_MAX_DISTANCE * LABEL_MAX_DISTANCE) return;

        ScreenPoint projected = projectToScreen(anchor, camera, event.getGuiGraphics().guiWidth(), event.getGuiGraphics().guiHeight(), minecraft);
        if (projected == null) return;

        List<String> lines = hologramLines(SurveyManager.getResultLabel());
        if (lines.isEmpty()) return;

        var graphics = event.getGuiGraphics();
        var font = minecraft.font;

        int maxWidth = 0;
        for (String line : lines) {
            maxWidth = Math.max(maxWidth, font.width(line));
        }

        int paddingX = 5;
        int paddingY = 3;
        int lineHeight = 10;
        int boxWidth = maxWidth + paddingX * 2;
        int boxHeight = lines.size() * lineHeight + paddingY * 2 - 1;

        int centerX = Math.max(boxWidth / 2 + 3,
                Math.min(graphics.guiWidth() - boxWidth / 2 - 3, projected.x()));
        int topY = Math.max(3,
                Math.min(graphics.guiHeight() - boxHeight - 3, projected.y() - boxHeight / 2));

        int left = centerX - boxWidth / 2;
        int right = left + boxWidth;
        int bottom = topY + boxHeight;

        // Restrained survey-hologram plate: dark translucent backing with a thin
        // green accent. It stays readable without becoming a giant HUD element.
        graphics.fill(left, topY, right, bottom, 0xA6121A14);
        graphics.fill(left, topY, right, topY + 1, 0xD84AF06A);
        graphics.fill(left, bottom - 1, right, bottom, 0x804AF06A);

        for (int i = 0; i < lines.size(); i++) {
            int y = topY + paddingY + i * lineHeight;
            int color = i == 0 ? 0xFFF1FFF3 : 0xFFD1F3D6;
            graphics.drawCenteredString(font, lines.get(i), centerX, y, color);
        }
    }

    private static void renderMeasurement(PoseStack poseStack, MultiBufferSource.BufferSource buffers, NeonColor primary) {
        MeasurementMode mode = SurveyManager.getMode();
        List<BlockPos> points = SurveyManager.getPointsForRender();
        List<Vec3> walkPath = SurveyManager.getWalkPathForRender();
        Set<BlockPos> interior = SurveyManager.getInteriorCellsForRender();

        switch (mode) {
            case DISTANCE -> renderSimpleLineMode(poseStack, buffers, points, primary);
            case POLYLINE -> renderPolyline(poseStack, buffers, points, false, primary);
            case RECTANGLE -> renderRectangle(poseStack, buffers, points, primary);
            case BOX -> renderBox(poseStack, buffers, points, primary);
            case POLYGON -> renderPolygon(poseStack, buffers, points, primary);
            case WALK_SURVEY -> renderWalkSurvey(poseStack, buffers, walkPath, primary);
            case INTERIOR -> renderInterior(poseStack, buffers, interior, primary);
            case SLOPE -> renderSlope(poseStack, buffers, points, primary);
            case HEIGHT -> renderSimpleLineMode(poseStack, buffers, points, primary);
            case CIRCLE -> renderCircle(poseStack, buffers, points, primary);
            case SPACING -> renderSpacing(poseStack, buffers, points, primary);
            case CENTER -> renderCenter(poseStack, buffers, points, primary);
        }

        renderMeasurementNodes(poseStack, buffers, points, primary);
    }

    private static void renderMeasurementNodes(PoseStack poseStack, MultiBufferSource.BufferSource buffers,
                                               List<BlockPos> points, NeonColor color) {
        if (points.isEmpty()) return;
        int stride = Math.max(1, (int) Math.ceil(points.size() / (double) MAX_RENDERED_NODES));
        for (int i = 0; i < points.size(); i += stride) {
            renderNode(poseStack, buffers, blockCenter(points.get(i)), color);
        }
        int last = points.size() - 1;
        if (last > 0 && last % stride != 0) {
            renderNode(poseStack, buffers, blockCenter(points.get(last)), color);
        }
    }

    private static List<Vec3> samplePathForRender(List<Vec3> input, int maxPoints) {
        if (input.size() <= maxPoints) return input;
        int stride = (int) Math.ceil(input.size() / (double) maxPoints);
        List<Vec3> output = new ArrayList<>(maxPoints + 1);
        for (int i = 0; i < input.size(); i += stride) output.add(input.get(i));
        Vec3 last = input.get(input.size() - 1);
        if (output.get(output.size() - 1) != last) output.add(last);
        return output;
    }

    private static void renderSimpleLineMode(PoseStack poseStack, MultiBufferSource.BufferSource buffers,
                                             List<BlockPos> points, NeonColor color) {
        if (points.size() < 2) return;
        drawNeonStrip(poseStack, buffers,
                List.of(blockCenter(points.get(0)), blockCenter(points.get(1))), false, color);
    }

    private static void renderPolyline(PoseStack poseStack, MultiBufferSource.BufferSource buffers,
                                       List<BlockPos> points, boolean close, NeonColor color) {
        if (points.size() < 2) return;
        List<Vec3> path = points.stream().map(SurveyRenderer::blockCenter).toList();
        drawNeonStrip(poseStack, buffers, path, close, color);
    }

    private static void renderRectangle(PoseStack poseStack, MultiBufferSource.BufferSource buffers,
                                        List<BlockPos> points, NeonColor color) {
        if (points.size() < 2) return;

        BlockPos a = points.get(0);
        BlockPos b = points.get(1);
        int dx = b.getX() - a.getX();
        int dy = b.getY() - a.getY();
        int dz = b.getZ() - a.getZ();

        if (dy == 0) {
            double minX = Math.min(a.getX(), b.getX());
            double maxX = Math.max(a.getX(), b.getX()) + 1.0;
            double minZ = Math.min(a.getZ(), b.getZ());
            double maxZ = Math.max(a.getZ(), b.getZ()) + 1.0;
            double y = a.getY() + 1.015;
            List<Vec3> loop = List.of(
                    new Vec3(minX, y, minZ), new Vec3(maxX, y, minZ),
                    new Vec3(maxX, y, maxZ), new Vec3(minX, y, maxZ)
            );
            drawSurfaceFillBox(poseStack, buffers, minX, y - 0.008, minZ, maxX, y + 0.008, maxZ, color, 0.055f);
            drawNeonStrip(poseStack, buffers, loop, true, color);
        } else if (dz == 0) {
            double minX = Math.min(a.getX(), b.getX());
            double maxX = Math.max(a.getX(), b.getX()) + 1.0;
            double minY = Math.min(a.getY(), b.getY());
            double maxY = Math.max(a.getY(), b.getY()) + 1.0;
            double z = a.getZ() + 1.015;
            drawSurfaceFillBox(poseStack, buffers, minX, minY, z - 0.008, maxX, maxY, z + 0.008, color, 0.055f);
            drawNeonStrip(poseStack, buffers, List.of(
                    new Vec3(minX, minY, z), new Vec3(maxX, minY, z),
                    new Vec3(maxX, maxY, z), new Vec3(minX, maxY, z)
            ), true, color);
        } else if (dx == 0) {
            double minZ = Math.min(a.getZ(), b.getZ());
            double maxZ = Math.max(a.getZ(), b.getZ()) + 1.0;
            double minY = Math.min(a.getY(), b.getY());
            double maxY = Math.max(a.getY(), b.getY()) + 1.0;
            double x = a.getX() + 1.015;
            drawSurfaceFillBox(poseStack, buffers, x - 0.008, minY, minZ, x + 0.008, maxY, maxZ, color, 0.055f);
            drawNeonStrip(poseStack, buffers, List.of(
                    new Vec3(x, minY, minZ), new Vec3(x, minY, maxZ),
                    new Vec3(x, maxY, maxZ), new Vec3(x, maxY, minZ)
            ), true, color);
        }
    }

    private static void renderBox(PoseStack poseStack, MultiBufferSource.BufferSource buffers,
                                  List<BlockPos> points, NeonColor color) {
        if (points.size() < 2) return;
        BlockPos a = points.get(0);
        BlockPos b = points.get(1);

        double x0 = Math.min(a.getX(), b.getX());
        double y0 = Math.min(a.getY(), b.getY());
        double z0 = Math.min(a.getZ(), b.getZ());
        double x1 = Math.max(a.getX(), b.getX()) + 1.0;
        double y1 = Math.max(a.getY(), b.getY()) + 1.0;
        double z1 = Math.max(a.getZ(), b.getZ()) + 1.0;

        drawNeonStrip(poseStack, buffers, List.of(
                new Vec3(x0, y0, z0), new Vec3(x1, y0, z0), new Vec3(x1, y0, z1), new Vec3(x0, y0, z1)
        ), true, color);
        drawNeonStrip(poseStack, buffers, List.of(
                new Vec3(x0, y1, z0), new Vec3(x1, y1, z0), new Vec3(x1, y1, z1), new Vec3(x0, y1, z1)
        ), true, color);
        drawNeonStrip(poseStack, buffers, List.of(new Vec3(x0, y0, z0), new Vec3(x0, y1, z0)), false, color);
        drawNeonStrip(poseStack, buffers, List.of(new Vec3(x1, y0, z0), new Vec3(x1, y1, z0)), false, color);
        drawNeonStrip(poseStack, buffers, List.of(new Vec3(x1, y0, z1), new Vec3(x1, y1, z1)), false, color);
        drawNeonStrip(poseStack, buffers, List.of(new Vec3(x0, y0, z1), new Vec3(x0, y1, z1)), false, color);
    }

    private static void renderPolygon(PoseStack poseStack, MultiBufferSource.BufferSource buffers,
                                      List<BlockPos> points, NeonColor color) {
        if (points.size() < 2) return;
        List<Vec3> path = points.stream().map(SurveyRenderer::blockTopCenter).toList();
        boolean close = SurveyManager.isFinalized() && points.size() >= 3;
        drawNeonStrip(poseStack, buffers, path, close, color);
        if (close) renderPolygonFill(poseStack, buffers, path, color, 0.045f, 0.0);
    }

    private static void renderWalkSurvey(PoseStack poseStack, MultiBufferSource.BufferSource buffers,
                                         List<Vec3> walkPath, NeonColor color) {
        if (walkPath.size() < 2) return;
        boolean close = SurveyManager.isFinalized() && walkPath.size() >= 3;
        List<Vec3> renderPath = samplePathForRender(walkPath, MAX_RENDERED_WALK_POINTS);
        drawNeonStrip(poseStack, buffers, renderPath, close, color, 0.055);
        if (close) renderPolygonFill(poseStack, buffers, walkPath, color, 0.035f, 0.055);

        renderNode(poseStack, buffers, walkPath.get(0).add(0.0, 0.055, 0.0), color);
    }

    private static void renderInterior(PoseStack poseStack, MultiBufferSource.BufferSource buffers,
                                       Set<BlockPos> cells, NeonColor color) {
        if (cells.isEmpty()) return;

        long revision = SurveyManager.getVisualRevision();
        if (cachedInteriorRevision != revision) {
            cachedInteriorRuns = buildInteriorRuns(cells);
            cachedInteriorRevision = revision;
        }

        for (FillBox run : cachedInteriorRuns) {
            drawSurfaceFillBox(poseStack, buffers,
                    run.minX(), run.minY(), run.minZ(),
                    run.maxX(), run.maxY(), run.maxZ(),
                    color, 0.075f);
        }

        // Same rule as drawNeonStrip: finish the complete x-ray pass before
        // requesting the normal line render type.
        VertexConsumer xray = buffers.getBuffer(XRAY_LINES);
        renderInteriorPerimeter(poseStack, xray, cells, color, XRAY_ALPHA);

        VertexConsumer core = buffers.getBuffer(RenderType.lines());
        renderInteriorPerimeter(poseStack, core, cells, color, 1.0f);
    }

    private static void renderInteriorPerimeter(PoseStack poseStack, VertexConsumer consumer,
                                                Set<BlockPos> cells, NeonColor color, float alpha) {
        for (BlockPos floor : cells) {
            double y = floor.getY() + 1.032;
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                if (cells.contains(floor.relative(direction))) continue;

                Vec3 a;
                Vec3 b;
                switch (direction) {
                    case NORTH -> {
                        a = new Vec3(floor.getX(), y, floor.getZ());
                        b = new Vec3(floor.getX() + 1.0, y, floor.getZ());
                    }
                    case SOUTH -> {
                        a = new Vec3(floor.getX(), y, floor.getZ() + 1.0);
                        b = new Vec3(floor.getX() + 1.0, y, floor.getZ() + 1.0);
                    }
                    case WEST -> {
                        a = new Vec3(floor.getX(), y, floor.getZ());
                        b = new Vec3(floor.getX(), y, floor.getZ() + 1.0);
                    }
                    case EAST -> {
                        a = new Vec3(floor.getX() + 1.0, y, floor.getZ());
                        b = new Vec3(floor.getX() + 1.0, y, floor.getZ() + 1.0);
                    }
                    default -> throw new IllegalStateException("Unexpected horizontal direction");
                }
                addCoreSegment(poseStack, consumer, a, b, color, alpha);
            }
        }
    }

    private static void renderSlope(PoseStack poseStack, MultiBufferSource.BufferSource buffers,
                                    List<BlockPos> points, NeonColor color) {
        if (points.size() < 2) return;
        Vec3 a = blockCenter(points.get(0));
        Vec3 b = blockCenter(points.get(1));
        Vec3 horizontalEnd = new Vec3(b.x, a.y, b.z);

        drawNeonStrip(poseStack, buffers, List.of(a, b), false, color);
        drawNeonStrip(poseStack, buffers, List.of(a, horizontalEnd), false, GUIDE);
        if (Math.abs(b.y - a.y) > 0.001) {
            drawNeonStrip(poseStack, buffers, List.of(horizontalEnd, b), false, RISE);
        }
    }

    private static void renderCircle(PoseStack poseStack, MultiBufferSource.BufferSource buffers,
                                     List<BlockPos> points, NeonColor color) {
        if (points.size() < 2) return;
        Vec3 center = blockTopCenter(points.get(0));
        Vec3 edge = blockTopCenter(points.get(1));
        double radius = Math.hypot(edge.x - center.x, edge.z - center.z);
        if (radius < 0.001) return;

        List<Vec3> circle = new ArrayList<>(CIRCLE_SEGMENTS);
        for (int i = 0; i < CIRCLE_SEGMENTS; i++) {
            double angle = (Math.PI * 2.0 * i) / CIRCLE_SEGMENTS;
            circle.add(new Vec3(
                    center.x + Math.cos(angle) * radius,
                    center.y,
                    center.z + Math.sin(angle) * radius
            ));
        }
        drawNeonStrip(poseStack, buffers, circle, true, color);
        renderNode(poseStack, buffers, center, color);
    }

    private static void renderSpacing(PoseStack poseStack, MultiBufferSource.BufferSource buffers,
                                      List<BlockPos> points, NeonColor color) {
        if (points.size() < 2) return;
        Vec3 a = blockTopCenter(points.get(0));
        Vec3 b = blockTopCenter(points.get(1));
        drawNeonStrip(poseStack, buffers, List.of(a, b), false, color);

        int dx = points.get(1).getX() - points.get(0).getX();
        int dz = points.get(1).getZ() - points.get(0).getZ();
        if (dx != 0 && dz != 0) return;

        int length = Math.abs(dx != 0 ? dx : dz);
        if (length <= 1) return;
        int step = Math.max(1, (int) Math.ceil(length / (double) MAX_SPACING_TICKS));

        VertexConsumer xray = buffers.getBuffer(XRAY_LINES);
        renderSpacingTicks(poseStack, xray, a, b, dx, length, step, XRAY_ALPHA);

        VertexConsumer core = buffers.getBuffer(RenderType.lines());
        renderSpacingTicks(poseStack, core, a, b, dx, length, step, 1.0f);
    }

    private static void renderSpacingTicks(PoseStack poseStack, VertexConsumer consumer,
                                           Vec3 a, Vec3 b, int dx, int length, int step, float alpha) {
        for (int i = step; i < length; i += step) {
            double t = i / (double) length;
            Vec3 p = a.lerp(b, t);
            double half = (i % 5 == 0) ? 0.36 : 0.20;
            Vec3 ta;
            Vec3 tb;
            if (dx != 0) {
                ta = p.add(0, 0, -half);
                tb = p.add(0, 0, half);
            } else {
                ta = p.add(-half, 0, 0);
                tb = p.add(half, 0, 0);
            }
            addCoreSegment(poseStack, consumer, ta, tb, GUIDE, alpha);
        }
    }

    private static void renderCenter(PoseStack poseStack, MultiBufferSource.BufferSource buffers,
                                     List<BlockPos> points, NeonColor color) {
        if (points.size() < 2) return;
        BlockPos a = points.get(0);
        BlockPos b = points.get(1);
        Vec3 center = new Vec3(
                (a.getX() + b.getX()) / 2.0 + 0.5,
                (a.getY() + b.getY()) / 2.0 + 0.5,
                (a.getZ() + b.getZ()) / 2.0 + 0.5
        );
        double arm = 0.65;
        drawNeonStrip(poseStack, buffers, List.of(center.add(-arm, 0, 0), center.add(arm, 0, 0)), false, color);
        drawNeonStrip(poseStack, buffers, List.of(center.add(0, -arm, 0), center.add(0, arm, 0)), false, color);
        drawNeonStrip(poseStack, buffers, List.of(center.add(0, 0, -arm), center.add(0, 0, arm)), false, color);
        renderNode(poseStack, buffers, center, color);
    }

    private static ScreenPoint projectToScreen(Vec3 world,
                                                    Camera camera,
                                                    int guiWidth,
                                                    int guiHeight,
                                                    Minecraft minecraft) {
        Vec3 rel = world.subtract(camera.getPosition());

        var look = camera.getLookVector();
        var up = camera.getUpVector();
        var left = camera.getLeftVector();

        double forward = rel.x * look.x() + rel.y * look.y() + rel.z * look.z();
        if (forward <= 0.08D) return null;

        // Camera exposes a left vector, while screen X grows to the right.
        double right = -(rel.x * left.x() + rel.y * left.y() + rel.z * left.z());
        double vertical = rel.x * up.x() + rel.y * up.y() + rel.z * up.z();

        double fov = minecraft.options.fov().get();
        double tanHalfFov = Math.tan(Math.toRadians(fov * 0.5D));
        if (tanHalfFov <= 0.0D) return null;

        double aspect = guiWidth / (double) Math.max(1, guiHeight);
        double ndcX = right / (forward * tanHalfFov * aspect);
        double ndcY = vertical / (forward * tanHalfFov);

        // Keep labels from clamping in from locations that are genuinely far
        // outside the player's view.
        if (Math.abs(ndcX) > 1.15D || Math.abs(ndcY) > 1.15D) return null;

        int x = (int) Math.round((ndcX * 0.5D + 0.5D) * guiWidth);
        int y = (int) Math.round((0.5D - ndcY * 0.5D) * guiHeight);
        return new ScreenPoint(x, y);
    }

    private static List<String> hologramLines(String text) {
        if (text == null || text.isBlank()) return List.of();

        String[] parts = text.split("\\s*\\|\\s*");
        if (parts.length == 1) {
            return List.of(trimLabel(parts[0]));
        }

        String first = trimLabel(parts[0]);
        String second = parts[1];
        if (parts.length > 2 && second.length() + parts[2].length() + 3 <= LABEL_MAX_CHARS) {
            second = second + " | " + parts[2];
        }
        return List.of(first, trimLabel(second));
    }

    private static String trimLabel(String text) {
        if (text.length() <= LABEL_MAX_CHARS) return text;
        return text.substring(0, LABEL_MAX_CHARS - 1) + "…";
    }

    private static void renderHologramStem(PoseStack poseStack, MultiBufferSource.BufferSource buffers) {
        Vec3 anchor = findLabelAnchor(
                SurveyManager.getPointsForRender(),
                SurveyManager.getWalkPathForRender(),
                SurveyManager.getInteriorCellsForRender()
        );
        if (anchor == null) return;

        Vec3 bottom = anchor.add(0.0, -LABEL_HEIGHT + 0.08, 0.0);
        Vec3 top = anchor.add(0.0, -0.12, 0.0);
        VertexConsumer line = buffers.getBuffer(RenderType.lines());
        addCoreSegment(poseStack, line, bottom, top, COMPLETE, 0.72f);

        // Tiny cross at the label attachment point: visible enough to read as a
        // holographic annotation, but far less intrusive than another full node box.
        double half = 0.10;
        addCoreSegment(poseStack, line,
                top.add(-half, 0.0, 0.0), top.add(half, 0.0, 0.0), COMPLETE, 0.82f);
        addCoreSegment(poseStack, line,
                top.add(0.0, 0.0, -half), top.add(0.0, 0.0, half), COMPLETE, 0.82f);
    }

    private static Vec3 findLabelAnchor(List<BlockPos> points, List<Vec3> walkPath, Set<BlockPos> interior) {
        if (!interior.isEmpty()) {
            double minX = Double.POSITIVE_INFINITY;
            double minZ = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            double maxZ = Double.NEGATIVE_INFINITY;
            for (BlockPos cell : interior) {
                minX = Math.min(minX, cell.getX());
                minZ = Math.min(minZ, cell.getZ());
                maxX = Math.max(maxX, cell.getX() + 1.0);
                maxY = Math.max(maxY, cell.getY() + 1.0);
                maxZ = Math.max(maxZ, cell.getZ() + 1.0);
            }
            return new Vec3((minX + maxX) * 0.5, maxY + 1.25, (minZ + maxZ) * 0.5);
        }

        if (!walkPath.isEmpty()) {
            double minX = Double.POSITIVE_INFINITY;
            double minZ = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            double maxZ = Double.NEGATIVE_INFINITY;
            int stride = Math.max(1, walkPath.size() / 1024);
            for (int i = 0; i < walkPath.size(); i += stride) {
                Vec3 point = walkPath.get(i);
                minX = Math.min(minX, point.x);
                minZ = Math.min(minZ, point.z);
                maxX = Math.max(maxX, point.x);
                maxY = Math.max(maxY, point.y);
                maxZ = Math.max(maxZ, point.z);
            }
            Vec3 last = walkPath.get(walkPath.size() - 1);
            minX = Math.min(minX, last.x);
            minZ = Math.min(minZ, last.z);
            maxX = Math.max(maxX, last.x);
            maxY = Math.max(maxY, last.y);
            maxZ = Math.max(maxZ, last.z);
            return new Vec3((minX + maxX) * 0.5, maxY + 1.5, (minZ + maxZ) * 0.5);
        }

        if (!points.isEmpty()) {
            if (points.size() == 2) {
                Vec3 a = blockCenter(points.get(0));
                Vec3 b = blockCenter(points.get(1));
                Vec3 midpoint = a.add(b).scale(0.5);
                return midpoint.add(0.0, LABEL_HEIGHT, 0.0);
            }

            double minX = Double.POSITIVE_INFINITY;
            double minZ = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            double maxZ = Double.NEGATIVE_INFINITY;
            for (BlockPos point : points) {
                minX = Math.min(minX, point.getX() + 0.5);
                minZ = Math.min(minZ, point.getZ() + 0.5);
                maxX = Math.max(maxX, point.getX() + 0.5);
                maxY = Math.max(maxY, point.getY() + 0.5);
                maxZ = Math.max(maxZ, point.getZ() + 0.5);
            }
            return new Vec3((minX + maxX) * 0.5, maxY + LABEL_HEIGHT, (minZ + maxZ) * 0.5);
        }

        return null;
    }

    private static void drawNeonStrip(PoseStack poseStack, MultiBufferSource.BufferSource buffers,
                                      List<Vec3> points, boolean close, NeonColor color) {
        drawNeonStrip(poseStack, buffers, points, close, color, 0.0);
    }

    private static void drawNeonStrip(PoseStack poseStack, MultiBufferSource.BufferSource buffers,
                                      List<Vec3> points, boolean close, NeonColor color, double yOffset) {
        if (points.size() < 2) return;

        drawGlowStrip(poseStack, buffers, points, close, color, GLOW_OUTER_WIDTH, 0.11f, yOffset);
        drawGlowStrip(poseStack, buffers, points, close, color, GLOW_INNER_WIDTH, 0.34f, yOffset);

        // MultiBufferSource.immediate(...) reuses one backing builder for ordinary
        // render types. Requesting a second type finalizes the first, so never keep
        // consumers for XRAY_LINES and RenderType.lines() alive at the same time.
        VertexConsumer xray = buffers.getBuffer(XRAY_LINES);
        for (int i = 1; i < points.size(); i++) {
            addCoreSegment(poseStack, xray, points.get(i - 1), points.get(i), color, XRAY_ALPHA, yOffset);
        }
        if (close && points.size() > 2) {
            addCoreSegment(poseStack, xray, points.get(points.size() - 1), points.get(0), color, XRAY_ALPHA, yOffset);
        }

        VertexConsumer core = buffers.getBuffer(RenderType.lines());
        for (int i = 1; i < points.size(); i++) {
            addCoreSegment(poseStack, core, points.get(i - 1), points.get(i), color, 1.0f, yOffset);
        }
        if (close && points.size() > 2) {
            addCoreSegment(poseStack, core, points.get(points.size() - 1), points.get(0), color, 1.0f, yOffset);
        }
    }

    private static void drawGlowStrip(PoseStack poseStack, MultiBufferSource.BufferSource buffers,
                                      List<Vec3> points, boolean close, NeonColor color,
                                      double width, float alpha, double yOffset) {
        RenderType type = RenderType.debugLineStrip(width);
        VertexConsumer consumer = buffers.getBuffer(type);
        Matrix4f matrix = poseStack.last().pose();

        for (Vec3 point : points) {
            consumer.addVertex(matrix, (float) point.x, (float) (point.y + yOffset), (float) point.z)
                    .setColor(color.r(), color.g(), color.b(), alpha);
        }
        if (close && points.size() > 2) {
            Vec3 first = points.get(0);
            consumer.addVertex(matrix, (float) first.x, (float) (first.y + yOffset), (float) first.z)
                    .setColor(color.r(), color.g(), color.b(), alpha);
        }

        // Flush to terminate this strip. Otherwise the next independent strip would connect to it.
        buffers.endBatch(type);
    }

    private static void addCoreSegment(PoseStack poseStack, VertexConsumer consumer,
                                       Vec3 a, Vec3 b, NeonColor color, float alpha) {
        addCoreSegment(poseStack, consumer, a, b, color, alpha, 0.0);
    }

    private static void addCoreSegment(PoseStack poseStack, VertexConsumer consumer,
                                       Vec3 a, Vec3 b, NeonColor color, float alpha, double yOffset) {
        float dx = (float) (b.x - a.x);
        float dy = (float) (b.y - a.y);
        float dz = (float) (b.z - a.z);
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 0.0001f) return;

        dx /= length;
        dy /= length;
        dz /= length;

        Matrix4f matrix = poseStack.last().pose();
        consumer.addVertex(matrix, (float) a.x, (float) (a.y + yOffset), (float) a.z)
                .setColor(color.r(), color.g(), color.b(), alpha)
                .setNormal(poseStack.last(), dx, dy, dz);
        consumer.addVertex(matrix, (float) b.x, (float) (b.y + yOffset), (float) b.z)
                .setColor(color.r(), color.g(), color.b(), alpha)
                .setNormal(poseStack.last(), dx, dy, dz);
    }

    private static void renderNode(PoseStack poseStack, MultiBufferSource.BufferSource buffers,
                                   Vec3 point, NeonColor color) {
        double size = 0.13;
        VertexConsumer fill = buffers.getBuffer(RenderType.debugFilledBox());
        ShapeRenderer.addChainedFilledBoxVertices(
                poseStack, fill,
                point.x - size, point.y - size, point.z - size,
                point.x + size, point.y + size, point.z + size,
                color.r(), color.g(), color.b(), 0.24f
        );

        VertexConsumer xray = buffers.getBuffer(XRAY_LINES);
        ShapeRenderer.renderLineBox(
                poseStack, xray,
                point.x - size, point.y - size, point.z - size,
                point.x + size, point.y + size, point.z + size,
                color.r(), color.g(), color.b(), XRAY_ALPHA
        );

        VertexConsumer line = buffers.getBuffer(RenderType.lines());
        ShapeRenderer.renderLineBox(
                poseStack, line,
                point.x - size, point.y - size, point.z - size,
                point.x + size, point.y + size, point.z + size,
                color.r(), color.g(), color.b(), 1.0f
        );
    }

    private static void drawSurfaceFillBox(PoseStack poseStack, MultiBufferSource.BufferSource buffers,
                                           double minX, double minY, double minZ,
                                           double maxX, double maxY, double maxZ,
                                           NeonColor color, float alpha) {
        VertexConsumer fill = buffers.getBuffer(RenderType.debugFilledBox());
        ShapeRenderer.addChainedFilledBoxVertices(
                poseStack, fill,
                minX, minY, minZ, maxX, maxY, maxZ,
                color.r(), color.g(), color.b(), alpha
        );
    }

    private static void renderPolygonFill(PoseStack poseStack, MultiBufferSource.BufferSource buffers,
                                          List<Vec3> rawPolygon, NeonColor color, float alpha, double yOffset) {
        long revision = SurveyManager.getVisualRevision();
        if (cachedPolygonRevision != revision) {
            List<Vec3> polygon = simplifyPolygon(rawPolygon, MAX_FILL_VERTICES);
            cachedPolygonTriangles = polygon.size() < 3 ? List.of() : triangulateXZ(polygon);
            cachedPolygonRevision = revision;
        }
        if (cachedPolygonTriangles.isEmpty()) return;

        // debugQuads is two-sided, which keeps the planar fill visible from above or below.
        VertexConsumer fill = buffers.getBuffer(RenderType.debugQuads());
        Matrix4f matrix = poseStack.last().pose();
        for (Triangle triangle : cachedPolygonTriangles) {
            Vec3 a = triangle.a();
            Vec3 b = triangle.b();
            Vec3 c = triangle.c();
            float ay = (float) (a.y + 0.015 + yOffset);
            float by = (float) (b.y + 0.015 + yOffset);
            float cy = (float) (c.y + 0.015 + yOffset);

            // RenderType.debugQuads() uses QUADS. Repeating C makes the second
            // triangle degenerate while the first triangle fills A-B-C.
            fill.addVertex(matrix, (float) a.x, ay, (float) a.z).setColor(color.r(), color.g(), color.b(), alpha);
            fill.addVertex(matrix, (float) b.x, by, (float) b.z).setColor(color.r(), color.g(), color.b(), alpha);
            fill.addVertex(matrix, (float) c.x, cy, (float) c.z).setColor(color.r(), color.g(), color.b(), alpha);
            fill.addVertex(matrix, (float) c.x, cy, (float) c.z).setColor(color.r(), color.g(), color.b(), alpha);
        }
    }

    private static List<FillBox> buildInteriorRuns(Set<BlockPos> cells) {
        Map<RowKey, List<Integer>> rows = new HashMap<>();
        for (BlockPos cell : cells) {
            rows.computeIfAbsent(new RowKey(cell.getY(), cell.getZ()), ignored -> new ArrayList<>()).add(cell.getX());
        }

        List<FillBox> runs = new ArrayList<>();
        for (Map.Entry<RowKey, List<Integer>> entry : rows.entrySet()) {
            List<Integer> xs = entry.getValue();
            xs.sort(Comparator.naturalOrder());
            int start = xs.get(0);
            int previous = start;
            for (int i = 1; i <= xs.size(); i++) {
                boolean flush = i == xs.size() || xs.get(i) != previous + 1;
                if (flush) {
                    double y = entry.getKey().y() + 1.006;
                    runs.add(new FillBox(
                            start, y, entry.getKey().z(),
                            previous + 1.0, y + 0.018, entry.getKey().z() + 1.0
                    ));
                    if (i < xs.size()) start = xs.get(i);
                }
                if (i < xs.size()) previous = xs.get(i);
            }
        }
        return List.copyOf(runs);
    }

    private static List<Vec3> simplifyPolygon(List<Vec3> input, int maxVertices) {
        if (input.size() <= maxVertices) return new ArrayList<>(input);
        int stride = (int) Math.ceil(input.size() / (double) maxVertices);
        List<Vec3> output = new ArrayList<>(maxVertices);
        for (int i = 0; i < input.size(); i += stride) output.add(input.get(i));
        return output;
    }

    /** Ear-clipping triangulation using X/Z as the polygon plane. */
    private static List<Triangle> triangulateXZ(List<Vec3> polygon) {
        int n = polygon.size();
        if (n < 3) return List.of();

        double signedArea = signedAreaXZ(polygon);
        if (Math.abs(signedArea) < 1.0e-7) return List.of();
        boolean ccw = signedArea > 0.0;

        List<Integer> indices = new ArrayList<>(n);
        for (int i = 0; i < n; i++) indices.add(i);
        List<Triangle> triangles = new ArrayList<>(n - 2);

        int guard = 0;
        while (indices.size() > 3 && guard++ < n * n) {
            boolean clipped = false;
            for (int i = 0; i < indices.size(); i++) {
                int prevIndex = indices.get((i - 1 + indices.size()) % indices.size());
                int currIndex = indices.get(i);
                int nextIndex = indices.get((i + 1) % indices.size());

                Vec3 a = polygon.get(prevIndex);
                Vec3 b = polygon.get(currIndex);
                Vec3 c = polygon.get(nextIndex);
                double cross = crossXZ(a, b, c);
                if (ccw ? cross <= 1.0e-7 : cross >= -1.0e-7) continue;

                boolean containsOther = false;
                for (int candidate : indices) {
                    if (candidate == prevIndex || candidate == currIndex || candidate == nextIndex) continue;
                    if (pointInTriangleXZ(polygon.get(candidate), a, b, c)) {
                        containsOther = true;
                        break;
                    }
                }
                if (containsOther) continue;

                triangles.add(new Triangle(a, b, c));
                indices.remove(i);
                clipped = true;
                break;
            }
            if (!clipped) return List.of(); // self-intersection or otherwise invalid polygon
        }

        if (indices.size() == 3) {
            triangles.add(new Triangle(
                    polygon.get(indices.get(0)),
                    polygon.get(indices.get(1)),
                    polygon.get(indices.get(2))
            ));
        }
        return triangles;
    }

    private static double signedAreaXZ(List<Vec3> polygon) {
        double area = 0.0;
        for (int i = 0; i < polygon.size(); i++) {
            Vec3 a = polygon.get(i);
            Vec3 b = polygon.get((i + 1) % polygon.size());
            area += a.x * b.z - b.x * a.z;
        }
        return area * 0.5;
    }

    private static double crossXZ(Vec3 a, Vec3 b, Vec3 c) {
        return (b.x - a.x) * (c.z - a.z) - (b.z - a.z) * (c.x - a.x);
    }

    private static boolean pointInTriangleXZ(Vec3 p, Vec3 a, Vec3 b, Vec3 c) {
        double c1 = crossXZ(a, b, p);
        double c2 = crossXZ(b, c, p);
        double c3 = crossXZ(c, a, p);
        boolean hasNegative = c1 < -1.0e-7 || c2 < -1.0e-7 || c3 < -1.0e-7;
        boolean hasPositive = c1 > 1.0e-7 || c2 > 1.0e-7 || c3 > 1.0e-7;
        return !(hasNegative && hasPositive);
    }

    private static Vec3 blockCenter(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    private static Vec3 blockTopCenter(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5, pos.getY() + 1.025, pos.getZ() + 0.5);
    }

    private record NeonColor(float r, float g, float b) { }
    private record RowKey(int y, int z) { }
    private record FillBox(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) { }
    private record ScreenPoint(int x, int y) {}

    private record Triangle(Vec3 a, Vec3 b, Vec3 c) { }

    private SurveyRenderer() { }
}
