package com.surveyorstoolbox.client;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.surveyorstoolbox.SurveyorsToolbox;
import com.surveyorstoolbox.chalk.ChalkMark;
import com.surveyorstoolbox.measurement.MeasurementMode;
import com.surveyorstoolbox.measurement.MeasurementSnapshot;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@EventBusSubscriber(modid = SurveyorsToolbox.MOD_ID, value = Dist.CLIENT)
public final class PersistentSurveyRenderer {
    private static final ByteBufferBuilder BUFFER = new ByteBufferBuilder(2 * 1024 * 1024);
    private static final MultiBufferSource.BufferSource BUFFERS = MultiBufferSource.immediate(BUFFER);

    private static final RenderType XRAY_LINES = RenderType.create(
            "surveyors_toolbox_persistent_xray",
            DefaultVertexFormat.POSITION_COLOR_NORMAL,
            VertexFormat.Mode.LINES,
            256,
            false,
            false,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderType.RENDERTYPE_LINES_SHADER)
                    .setLineState(RenderType.DEFAULT_LINE)
                    .setTransparencyState(RenderType.TRANSLUCENT_TRANSPARENCY)
                    .setDepthTestState(RenderType.NO_DEPTH_TEST)
                    .setCullState(RenderType.NO_CULL)
                    .setWriteMaskState(RenderType.COLOR_WRITE)
                    .createCompositeState(false)
    );

    private static final double CHALK_RENDER_DISTANCE = 96.0D;
    private static final double CHALK_FULL_DISTANCE = 36.0D;
    private static final double LABEL_RENDER_DISTANCE = 72.0D;
    private static final int MAX_RENDERED_CHALK_MARKS = 64;
    private static final int MAX_RENDERED_LABELS = 24;
    private static final int LABEL_PADDING_X = 4;
    private static final int LABEL_HEIGHT = 13;
    private static final int CIRCLE_SEGMENTS = 72;

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) return;

        if (ChalkClientStore.marks().isEmpty()) return;

        Camera camera = event.getCamera();
        Vec3 cameraPos = camera.getPosition();
        PoseStack poseStack = event.getPoseStack();
        if (poseStack == null) return;

        poseStack.pushPose();
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        List<ChalkMark> visible = ChalkClientStore.marks().stream()
                    .filter(mark -> mark.measurement().distanceTo(cameraPos) <= CHALK_RENDER_DISTANCE)
                    .sorted(Comparator.comparingDouble(mark -> mark.measurement().distanceTo(cameraPos)))
                    .limit(MAX_RENDERED_CHALK_MARKS)
                    .toList();

        for (ChalkMark mark : visible) {
            double distance = mark.measurement().distanceTo(cameraPos);
            float brightness = distance <= CHALK_FULL_DISTANCE
                    ? 1.0f
                    : (float) Math.max(mark.glowing() ? 0.60D : 0.42D, 1.0D - (distance - CHALK_FULL_DISTANCE) / 90.0D);
            if (mark.glowing()) {
                renderSnapshot(poseStack, mark.measurement(), color(mark.color(), Math.min(1.0f, brightness + 0.20f)), 0.58f * brightness);
            }
            renderSnapshot(poseStack, mark.measurement(), color(mark.color(), brightness), (mark.glowing() ? 0.42f : 0.34f) * brightness);
        }

        BUFFERS.endBatch();
        poseStack.popPose();
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) return;

        if (ChalkClientStore.marks().isEmpty()) return;

        var graphics = event.getGuiGraphics();
        var font = minecraft.font;
        Camera camera = minecraft.gameRenderer.getMainCamera();
        Vec3 cameraPos = camera.getPosition();
        int guiWidth = graphics.guiWidth();
        int guiHeight = graphics.guiHeight();

        List<LabelCandidate> candidates = new ArrayList<>();

        for (ChalkMark mark : ChalkClientStore.marks()) {
                MeasurementSnapshot snapshot = mark.measurement();
                double distance = snapshot.distanceTo(cameraPos);
                if (distance > LABEL_RENDER_DISTANCE) continue;

                LabelGeometry geometry = labelGeometry(snapshot, cameraPos);
                ScreenPoint anchor = projectToScreen(geometry.anchor(), camera, guiWidth, guiHeight, minecraft);
                if (anchor == null) continue;

                ScreenDirection direction = projectDirection(geometry, camera, guiWidth, guiHeight, minecraft);
                candidates.add(new LabelCandidate(
                        compactLabel(snapshot, mark.glowing()),
                        anchor,
                        direction,
                        distance,
                        accentArgb(mark)
                ));
            }

        candidates.sort(Comparator.comparingDouble(LabelCandidate::distance));

        List<ScreenRect> occupied = new ArrayList<>();
        ChalkMark focus = focusedMark(minecraft);

        // Reserve the top-center focus panel so compact labels never cover it.
        if (focus != null) {
            String title = titleFor(focus);
            String detail = focus.measurement().label();
            if (detail.length() > 92) detail = detail.substring(0, 91) + "…";
            int focusWidth = Math.max(font.width(title), font.width(detail)) + 12;
            int focusX = (guiWidth - focusWidth) / 2;
            int focusY = Math.max(8, guiHeight / 7);
            occupied.add(new ScreenRect(focusX - 3, focusY - 3, focusX + focusWidth + 3, focusY + 28));
        }

        int rendered = 0;
        for (LabelCandidate candidate : candidates) {
            if (rendered >= MAX_RENDERED_LABELS) break;
            if (candidate.text().isBlank()) continue;

            int textWidth = font.width(candidate.text());
            int boxWidth = textWidth + LABEL_PADDING_X * 2;
            ScreenRect placement = findLabelPlacement(
                    candidate, boxWidth, LABEL_HEIGHT, guiWidth, guiHeight, occupied
            );
            if (placement == null) continue;

            int backgroundAlpha = candidate.distance() <= 32.0D ? 0xB8 : 0x96;
            int background = (backgroundAlpha << 24) | 0x101512;
            int accent = (0xDD << 24) | (candidate.accentArgb() & 0x00FFFFFF);
            int textColor = 0xFFF0FFF2;

            graphics.fill(placement.left(), placement.top(), placement.right(), placement.bottom(), background);
            graphics.fill(placement.left(), placement.top(), placement.right(), placement.top() + 1, accent);

            int textX = placement.left() + LABEL_PADDING_X;
            int textY = placement.top() + 3;
            graphics.drawString(font, candidate.text(), textX, textY, textColor, false);

            occupied.add(placement.inflate(2));
            rendered++;
        }

        if (focus != null) {
            renderFocusedPanel(graphics, minecraft, focus);
        }
    }

    private static void renderFocusedPanel(net.minecraft.client.gui.GuiGraphics graphics,
                                           Minecraft minecraft,
                                           ChalkMark focus) {
        var font = minecraft.font;
        String title = titleFor(focus);
        String detail = focus.measurement().label();
        if (detail.length() > 92) detail = detail.substring(0, 91) + "…";

        int width = Math.max(font.width(title), font.width(detail)) + 12;
        int x = (graphics.guiWidth() - width) / 2;
        int y = Math.max(8, graphics.guiHeight() / 7);
        int accent = (0xDD << 24) | (argb(focus.color()) & 0x00FFFFFF);

        graphics.fill(x, y, x + width, y + 25, 0xB8101512);
        graphics.fill(x, y, x + width, y + 1, accent);
        graphics.drawCenteredString(font, title, graphics.guiWidth() / 2, y + 4, 0xFFE9FFF0);
        graphics.drawCenteredString(font, detail, graphics.guiWidth() / 2, y + 14, 0xFFCDE8D2);
    }

    private static ChalkMark focusedMark(Minecraft minecraft) {
        Vec3 eye = minecraft.player.getEyePosition();
        Vec3 look = minecraft.player.getViewVector(1.0f).normalize();

        ChalkMark best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (ChalkMark mark : ChalkClientStore.marks()) {
            LabelGeometry geometry = labelGeometry(mark.measurement(), eye);
            Vec3 to = geometry.anchor().subtract(eye);
            double distance = to.length();
            if (distance < 0.1D || distance > 48.0D) continue;

            double dot = to.normalize().dot(look);
            if (dot < 0.92D) continue;

            double score = (1.0D - dot) * 60.0D + distance * 0.018D;
            if (score < bestScore) {
                best = mark;
                bestScore = score;
            }
        }
        return best;
    }

    private static ScreenRect findLabelPlacement(LabelCandidate candidate,
                                                  int width,
                                                  int height,
                                                  int guiWidth,
                                                  int guiHeight,
                                                  List<ScreenRect> occupied) {
        double dx = candidate.direction().x();
        double dy = candidate.direction().y();
        double length = Math.sqrt(dx * dx + dy * dy);
        if (length < 0.001D) {
            dx = 1.0D;
            dy = 0.0D;
        } else {
            dx /= length;
            dy /= length;
        }

        double px = -dy;
        double py = dx;
        double step = Math.max(18.0D, width * 0.58D);

        double[][] offsets = {
                {0.0D, 0.0D},
                {step, 0.0D}, {-step, 0.0D},
                {step * 2.0D, 0.0D}, {-step * 2.0D, 0.0D},
                {0.0D, 14.0D}, {0.0D, -14.0D},
                {step, 14.0D}, {-step, 14.0D},
                {step, -14.0D}, {-step, -14.0D},
                {0.0D, 28.0D}, {0.0D, -28.0D}
        };

        for (double[] offset : offsets) {
            double along = offset[0];
            double perpendicular = offset[1];

            int centerX = (int) Math.round(candidate.anchor().x() + dx * along + px * perpendicular);
            int centerY = (int) Math.round(candidate.anchor().y() + dy * along + py * perpendicular);

            int left = centerX - width / 2;
            int top = centerY - height / 2;
            ScreenRect rect = new ScreenRect(left, top, left + width, top + height);

            if (rect.left() < 3 || rect.top() < 3 || rect.right() > guiWidth - 3 || rect.bottom() > guiHeight - 3) {
                continue;
            }

            boolean overlaps = false;
            for (ScreenRect existing : occupied) {
                if (rect.overlaps(existing)) {
                    overlaps = true;
                    break;
                }
            }
            if (!overlaps) return rect;
        }

        return null;
    }

    private static LabelGeometry labelGeometry(MeasurementSnapshot snapshot, Vec3 reference) {
        if (snapshot.mode() == MeasurementMode.CIRCLE && snapshot.points().size() >= 2) {
            Vec3 center = topCenter(snapshot.points().get(0));
            Vec3 edge = topCenter(snapshot.points().get(1));
            double radius = Math.hypot(edge.x - center.x, edge.z - center.z);

            if (radius > 0.001D) {
                double rx = reference.x - center.x;
                double rz = reference.z - center.z;
                double rlen = Math.hypot(rx, rz);

                Vec3 radial;
                if (rlen > 0.001D) {
                    radial = new Vec3(rx / rlen, 0.0D, rz / rlen);
                } else {
                    Vec3 original = edge.subtract(center);
                    double olen = Math.hypot(original.x, original.z);
                    radial = olen > 0.001D
                            ? new Vec3(original.x / olen, 0.0D, original.z / olen)
                            : new Vec3(1.0D, 0.0D, 0.0D);
                }

                Vec3 anchor = center.add(radial.scale(radius)).add(0.0D, 0.18D, 0.0D);
                Vec3 tangent = new Vec3(-radial.z, 0.0D, radial.x);
                return new LabelGeometry(anchor, anchor.subtract(tangent), anchor.add(tangent));
            }
        }

        List<Segment> segments = buildSegments(snapshot);
        Segment best = null;
        Vec3 bestPoint = null;
        double bestDistanceSq = Double.POSITIVE_INFINITY;

        for (Segment segment : segments) {
            Vec3 closest = closestPointOnSegment(reference, segment.a(), segment.b());
            double distanceSq = closest.distanceToSqr(reference);
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                best = segment;
                bestPoint = closest;
            }
        }

        if (best != null && bestPoint != null) {
            Vec3 anchor = bestPoint.add(0.0D, 0.14D, 0.0D);
            return new LabelGeometry(anchor, best.a(), best.b());
        }

        Vec3 anchor = snapshot.anchor().add(0.0D, 0.2D, 0.0D);
        return new LabelGeometry(
                anchor,
                anchor.add(-1.0D, 0.0D, 0.0D),
                anchor.add(1.0D, 0.0D, 0.0D)
        );
    }

    private static Vec3 closestPointOnSegment(Vec3 point, Vec3 a, Vec3 b) {
        Vec3 ab = b.subtract(a);
        double lengthSq = ab.lengthSqr();
        if (lengthSq < 1.0e-8D) return a;

        double t = point.subtract(a).dot(ab) / lengthSq;
        t = Math.max(0.0D, Math.min(1.0D, t));
        return a.add(ab.scale(t));
    }

    private static ScreenDirection projectDirection(LabelGeometry geometry,
                                                    Camera camera,
                                                    int guiWidth,
                                                    int guiHeight,
                                                    Minecraft minecraft) {
        ScreenPoint a = projectToScreen(geometry.directionA(), camera, guiWidth, guiHeight, minecraft);
        ScreenPoint b = projectToScreen(geometry.directionB(), camera, guiWidth, guiHeight, minecraft);
        if (a == null || b == null) return new ScreenDirection(1.0D, 0.0D);

        double dx = b.x() - a.x();
        double dy = b.y() - a.y();
        if (Math.abs(dx) + Math.abs(dy) < 0.001D) return new ScreenDirection(1.0D, 0.0D);
        return new ScreenDirection(dx, dy);
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

        double right = -(rel.x * left.x() + rel.y * left.y() + rel.z * left.z());
        double vertical = rel.x * up.x() + rel.y * up.y() + rel.z * up.z();

        double fov = minecraft.options.fov().get();
        double tanHalfFov = Math.tan(Math.toRadians(fov * 0.5D));
        if (tanHalfFov <= 0.0D) return null;

        double aspect = guiWidth / (double) Math.max(1, guiHeight);
        double ndcX = right / (forward * tanHalfFov * aspect);
        double ndcY = vertical / (forward * tanHalfFov);

        if (Math.abs(ndcX) > 1.10D || Math.abs(ndcY) > 1.10D) return null;

        int x = (int) Math.round((ndcX * 0.5D + 0.5D) * guiWidth);
        int y = (int) Math.round((0.5D - ndcY * 0.5D) * guiHeight);
        return new ScreenPoint(x, y);
    }

    private static String compactLabel(MeasurementSnapshot snapshot, boolean glowing) {
        List<BlockPos> points = snapshot.points();

        String base = switch (snapshot.mode()) {
            case DISTANCE -> {
                if (points.size() < 2) yield "Distance";
                yield "Distance " + fmtCompact(blockDeltaDistance(points.get(0), points.get(1))) + " b";
            }
            case POLYLINE -> "Polyline " + fmtCompact(pointPathLength(points, false)) + " b";
            case RECTANGLE -> rectangleCompact(points);
            case BOX -> boxCompact(points);
            case POLYGON -> "Polygon " + fmtCompact(polygonArea(points)) + " b²";
            case WALK_SURVEY -> "Walk " + fmtCompact(walkArea(snapshot.walkPath())) + " b²";
            case INTERIOR -> "Interior " + snapshot.interiorCells().size() + " b²";
            case SLOPE -> slopeCompact(points);
            case HEIGHT -> {
                if (points.size() < 2) yield "Height";
                yield "Height " + Math.abs(points.get(1).getY() - points.get(0).getY()) + " b";
            }
            case CIRCLE -> circleCompact(points);
            case SPACING -> {
                if (points.size() < 2) yield "Spacing";
                double dx = points.get(1).getX() - points.get(0).getX();
                double dz = points.get(1).getZ() - points.get(0).getZ();
                yield "Spacing " + fmtCompact(Math.hypot(dx, dz)) + " b";
            }
            case CENTER -> "Center";
        };
        return glowing ? "Glow • " + base : base;
    }

    private static String titleFor(ChalkMark mark) {
        String prefix = mark.glowing() ? "Glow " : "";
        return prefix + capitalize(mark.color().getName()) + " chalk • " + mark.ownerName();
    }

    private static int accentArgb(ChalkMark mark) {
        int base = argb(mark.color());
        if (!mark.glowing()) return base;
        int r = Math.min(255, ((base >> 16) & 0xFF) + 34);
        int g = Math.min(255, ((base >> 8) & 0xFF) + 34);
        int b = Math.min(255, (base & 0xFF) + 34);
        return (r << 16) | (g << 8) | b;
    }

    private static String rectangleCompact(List<BlockPos> points) {
        if (points.size() < 2) return "Rectangle";
        BlockPos a = points.get(0);
        BlockPos b = points.get(1);
        int dx = Math.abs(b.getX() - a.getX());
        int dy = Math.abs(b.getY() - a.getY());
        int dz = Math.abs(b.getZ() - a.getZ());

        int first;
        int second;
        if (dy == 0) {
            first = dx + 1;
            second = dz + 1;
        } else if (dz == 0) {
            first = dx + 1;
            second = dy + 1;
        } else {
            first = dz + 1;
            second = dy + 1;
        }
        return first + "×" + second + " • " + ((long) first * second) + " b²";
    }

    private static String boxCompact(List<BlockPos> points) {
        if (points.size() < 2) return "Box";
        BlockPos a = points.get(0);
        BlockPos b = points.get(1);
        int x = Math.abs(b.getX() - a.getX()) + 1;
        int y = Math.abs(b.getY() - a.getY()) + 1;
        int z = Math.abs(b.getZ() - a.getZ()) + 1;
        return x + "×" + y + "×" + z + " • " + ((long) x * y * z) + " b³";
    }

    private static String slopeCompact(List<BlockPos> points) {
        if (points.size() < 2) return "Slope";
        BlockPos a = points.get(0);
        BlockPos b = points.get(1);
        double dx = b.getX() - a.getX();
        double dz = b.getZ() - a.getZ();
        double dy = b.getY() - a.getY();
        double run = Math.hypot(dx, dz);
        if (run < 0.0001D) return "Slope vertical";
        double pct = (dy / run) * 100.0D;
        double angle = Math.toDegrees(Math.atan2(dy, run));
        return "Slope " + fmtCompact(angle) + "° • " + fmtCompact(pct) + "%";
    }

    private static String circleCompact(List<BlockPos> points) {
        if (points.size() < 2) return "Circle";
        BlockPos a = points.get(0);
        BlockPos b = points.get(1);
        double radius = Math.hypot(b.getX() - a.getX(), b.getZ() - a.getZ());
        long footprint = circleFootprint(radius);
        return "Circle r" + fmtCompact(radius) + " • ~" + footprint + " b²";
    }

    private static double blockDeltaDistance(BlockPos a, BlockPos b) {
        double dx = b.getX() - a.getX();
        double dy = b.getY() - a.getY();
        double dz = b.getZ() - a.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double pointPathLength(List<BlockPos> points, boolean close) {
        if (points.size() < 2) return 0.0D;
        double length = 0.0D;
        for (int i = 1; i < points.size(); i++) {
            length += blockDeltaDistance(points.get(i - 1), points.get(i));
        }
        if (close && points.size() > 2) {
            length += blockDeltaDistance(points.get(points.size() - 1), points.get(0));
        }
        return length;
    }

    private static double polygonArea(List<BlockPos> points) {
        if (points.size() < 3) return 0.0D;
        double sum = 0.0D;
        for (int i = 0; i < points.size(); i++) {
            BlockPos a = points.get(i);
            BlockPos b = points.get((i + 1) % points.size());
            sum += (double) a.getX() * b.getZ() - (double) b.getX() * a.getZ();
        }
        return Math.abs(sum) * 0.5D;
    }

    private static double walkArea(List<Vec3> points) {
        if (points.size() < 3) return 0.0D;
        double sum = 0.0D;
        for (int i = 0; i < points.size(); i++) {
            Vec3 a = points.get(i);
            Vec3 b = points.get((i + 1) % points.size());
            sum += a.x * b.z - b.x * a.z;
        }
        return Math.abs(sum) * 0.5D;
    }

    private static long circleFootprint(double radius) {
        if (radius <= 0.0D) return 1L;
        if (radius > 256.0D) return Math.round(Math.PI * radius * radius);

        int extent = (int) Math.ceil(radius);
        double radiusSq = radius * radius + 1.0e-7D;
        long count = 0L;
        for (int x = -extent; x <= extent; x++) {
            for (int z = -extent; z <= extent; z++) {
                if ((double) x * x + (double) z * z <= radiusSq) count++;
            }
        }
        return count;
    }

    private static String fmtCompact(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.05D) {
            return Long.toString(Math.round(value));
        }
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    private static int argb(DyeColor dye) {
        int rgb = dye.getTextureDiffuseColor();
        return 0xFF000000 | (rgb & 0x00FFFFFF);
    }

    private static void renderSnapshot(PoseStack poseStack,
                                       MeasurementSnapshot snapshot,
                                       NeonColor color,
                                       float xrayAlpha) {
        List<Segment> segments = buildSegments(snapshot);
        if (segments.isEmpty()) return;

        VertexConsumer xray = BUFFERS.getBuffer(XRAY_LINES);
        for (Segment segment : segments) {
            addSegment(poseStack, xray, segment.a(), segment.b(), color, xrayAlpha);
        }

        VertexConsumer core = BUFFERS.getBuffer(RenderType.lines());
        for (Segment segment : segments) {
            addSegment(poseStack, core, segment.a(), segment.b(), color, 0.96f);
        }
    }

    private static List<Segment> buildSegments(MeasurementSnapshot snapshot) {
        List<Segment> out = new ArrayList<>();
        List<BlockPos> points = snapshot.points();

        switch (snapshot.mode()) {
            case DISTANCE, HEIGHT, SPACING -> {
                if (points.size() >= 2) {
                    out.add(new Segment(center(points.get(0)), center(points.get(1))));
                }
            }
            case POLYLINE -> addPointPath(out, points, false);
            case POLYGON -> addPointPath(out, points, true);
            case RECTANGLE -> addRectangle(out, points);
            case BOX -> addBox(out, points);
            case WALK_SURVEY -> addVecPath(out, snapshot.walkPath(), true);
            case INTERIOR -> addInteriorBoundary(out, snapshot);
            case SLOPE -> {
                if (points.size() >= 2) {
                    Vec3 a = center(points.get(0));
                    Vec3 b = center(points.get(1));
                    out.add(new Segment(a, b));
                    out.add(new Segment(a, new Vec3(b.x, a.y, b.z)));
                }
            }
            case CIRCLE -> addCircle(out, points);
            case CENTER -> addCenter(out, points);
        }

        return out;
    }

    private static void addPointPath(List<Segment> out, List<BlockPos> points, boolean close) {
        if (points.size() < 2) return;
        for (int i = 1; i < points.size(); i++) {
            out.add(new Segment(topCenter(points.get(i - 1)), topCenter(points.get(i))));
        }
        if (close && points.size() > 2) {
            out.add(new Segment(topCenter(points.get(points.size() - 1)), topCenter(points.get(0))));
        }
    }

    private static void addVecPath(List<Segment> out, List<Vec3> path, boolean close) {
        if (path.size() < 2) return;
        for (int i = 1; i < path.size(); i++) {
            out.add(new Segment(path.get(i - 1).add(0, 0.06, 0), path.get(i).add(0, 0.06, 0)));
        }
        if (close && path.size() > 2) {
            out.add(new Segment(path.get(path.size() - 1).add(0, 0.06, 0), path.get(0).add(0, 0.06, 0)));
        }
    }

    private static void addRectangle(List<Segment> out, List<BlockPos> points) {
        if (points.size() < 2) return;
        BlockPos a = points.get(0);
        BlockPos b = points.get(1);
        int dx = b.getX() - a.getX();
        int dy = b.getY() - a.getY();
        int dz = b.getZ() - a.getZ();

        if (dy == 0) {
            double y = a.getY() + 1.02;
            addLoop(out, List.of(
                    new Vec3(Math.min(a.getX(), b.getX()), y, Math.min(a.getZ(), b.getZ())),
                    new Vec3(Math.max(a.getX(), b.getX()) + 1, y, Math.min(a.getZ(), b.getZ())),
                    new Vec3(Math.max(a.getX(), b.getX()) + 1, y, Math.max(a.getZ(), b.getZ()) + 1),
                    new Vec3(Math.min(a.getX(), b.getX()), y, Math.max(a.getZ(), b.getZ()) + 1)
            ));
        } else if (dz == 0) {
            double z = a.getZ() + 0.5;
            addLoop(out, List.of(
                    new Vec3(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), z),
                    new Vec3(Math.max(a.getX(), b.getX()) + 1, Math.min(a.getY(), b.getY()), z),
                    new Vec3(Math.max(a.getX(), b.getX()) + 1, Math.max(a.getY(), b.getY()) + 1, z),
                    new Vec3(Math.min(a.getX(), b.getX()), Math.max(a.getY(), b.getY()) + 1, z)
            ));
        } else if (dx == 0) {
            double x = a.getX() + 0.5;
            addLoop(out, List.of(
                    new Vec3(x, Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ())),
                    new Vec3(x, Math.min(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()) + 1),
                    new Vec3(x, Math.max(a.getY(), b.getY()) + 1, Math.max(a.getZ(), b.getZ()) + 1),
                    new Vec3(x, Math.max(a.getY(), b.getY()) + 1, Math.min(a.getZ(), b.getZ()))
            ));
        }
    }

    private static void addBox(List<Segment> out, List<BlockPos> points) {
        if (points.size() < 2) return;
        BlockPos a = points.get(0);
        BlockPos b = points.get(1);
        double x0 = Math.min(a.getX(), b.getX());
        double y0 = Math.min(a.getY(), b.getY());
        double z0 = Math.min(a.getZ(), b.getZ());
        double x1 = Math.max(a.getX(), b.getX()) + 1.0;
        double y1 = Math.max(a.getY(), b.getY()) + 1.0;
        double z1 = Math.max(a.getZ(), b.getZ()) + 1.0;

        Vec3[] v = {
                new Vec3(x0,y0,z0), new Vec3(x1,y0,z0), new Vec3(x1,y0,z1), new Vec3(x0,y0,z1),
                new Vec3(x0,y1,z0), new Vec3(x1,y1,z0), new Vec3(x1,y1,z1), new Vec3(x0,y1,z1)
        };
        int[][] edges = {
                {0,1},{1,2},{2,3},{3,0},{4,5},{5,6},{6,7},{7,4},{0,4},{1,5},{2,6},{3,7}
        };
        for (int[] edge : edges) out.add(new Segment(v[edge[0]], v[edge[1]]));
    }

    private static void addInteriorBoundary(List<Segment> out, MeasurementSnapshot snapshot) {
        for (BlockPos floor : snapshot.interiorCells()) {
            double y = floor.getY() + 1.035;
            BlockPos north = floor.north();
            BlockPos south = floor.south();
            BlockPos west = floor.west();
            BlockPos east = floor.east();
            if (!snapshot.interiorCells().contains(north))
                out.add(new Segment(new Vec3(floor.getX(), y, floor.getZ()), new Vec3(floor.getX()+1, y, floor.getZ())));
            if (!snapshot.interiorCells().contains(south))
                out.add(new Segment(new Vec3(floor.getX(), y, floor.getZ()+1), new Vec3(floor.getX()+1, y, floor.getZ()+1)));
            if (!snapshot.interiorCells().contains(west))
                out.add(new Segment(new Vec3(floor.getX(), y, floor.getZ()), new Vec3(floor.getX(), y, floor.getZ()+1)));
            if (!snapshot.interiorCells().contains(east))
                out.add(new Segment(new Vec3(floor.getX()+1, y, floor.getZ()), new Vec3(floor.getX()+1, y, floor.getZ()+1)));
        }
    }

    private static void addCircle(List<Segment> out, List<BlockPos> points) {
        if (points.size() < 2) return;
        Vec3 center = topCenter(points.get(0));
        Vec3 edge = topCenter(points.get(1));
        double radius = Math.hypot(edge.x - center.x, edge.z - center.z);
        if (radius < 0.001) return;

        Vec3 previous = null;
        Vec3 first = null;
        for (int i = 0; i < CIRCLE_SEGMENTS; i++) {
            double angle = Math.PI * 2.0 * i / CIRCLE_SEGMENTS;
            Vec3 current = new Vec3(center.x + Math.cos(angle)*radius, center.y, center.z + Math.sin(angle)*radius);
            if (first == null) first = current;
            if (previous != null) out.add(new Segment(previous, current));
            previous = current;
        }
        if (previous != null && first != null) out.add(new Segment(previous, first));
    }

    private static void addCenter(List<Segment> out, List<BlockPos> points) {
        if (points.size() < 2) return;
        BlockPos a = points.get(0);
        BlockPos b = points.get(1);
        Vec3 c = new Vec3(
                (a.getX()+b.getX())/2.0 + 0.5,
                (a.getY()+b.getY())/2.0 + 0.5,
                (a.getZ()+b.getZ())/2.0 + 0.5
        );
        double arm = 0.65;
        out.add(new Segment(c.add(-arm,0,0), c.add(arm,0,0)));
        out.add(new Segment(c.add(0,-arm,0), c.add(0,arm,0)));
        out.add(new Segment(c.add(0,0,-arm), c.add(0,0,arm)));
    }

    private static void addLoop(List<Segment> out, List<Vec3> points) {
        for (int i = 1; i < points.size(); i++) out.add(new Segment(points.get(i-1), points.get(i)));
        if (points.size() > 2) out.add(new Segment(points.get(points.size()-1), points.get(0)));
    }

    private static void addSegment(PoseStack poseStack, VertexConsumer consumer,
                                   Vec3 a, Vec3 b, NeonColor color, float alpha) {
        float dx = (float) (b.x - a.x);
        float dy = (float) (b.y - a.y);
        float dz = (float) (b.z - a.z);
        float length = (float) Math.sqrt(dx*dx + dy*dy + dz*dz);
        if (length < 0.0001f) return;
        dx /= length; dy /= length; dz /= length;

        Matrix4f matrix = poseStack.last().pose();
        consumer.addVertex(matrix, (float)a.x, (float)a.y, (float)a.z)
                .setColor(color.r(), color.g(), color.b(), alpha)
                .setNormal(poseStack.last(), dx, dy, dz);
        consumer.addVertex(matrix, (float)b.x, (float)b.y, (float)b.z)
                .setColor(color.r(), color.g(), color.b(), alpha)
                .setNormal(poseStack.last(), dx, dy, dz);
    }

    private static Vec3 center(BlockPos p) {
        return new Vec3(p.getX()+0.5, p.getY()+0.5, p.getZ()+0.5);
    }

    private static Vec3 topCenter(BlockPos p) {
        return new Vec3(p.getX()+0.5, p.getY()+1.035, p.getZ()+0.5);
    }

    private static NeonColor color(DyeColor dye, float brightness) {
        int rgb = dye.getTextureDiffuseColor();
        float r = ((rgb >> 16) & 0xFF) / 255.0f;
        float g = ((rgb >> 8) & 0xFF) / 255.0f;
        float b = (rgb & 0xFF) / 255.0f;

        // Keep black/brown/gray chalk readable as luminous survey lines.
        r = (0.16f + 0.84f * r) * brightness;
        g = (0.16f + 0.84f * g) * brightness;
        b = (0.16f + 0.84f * b) * brightness;
        return new NeonColor(r, g, b);
    }

    private static String capitalize(String input) {
        if (input == null || input.isBlank()) return "Chalk";
        return Character.toUpperCase(input.charAt(0)) + input.substring(1).replace('_', ' ');
    }

    private record ScreenPoint(int x, int y) { }
    private record ScreenDirection(double x, double y) { }
    private record ScreenRect(int left, int top, int right, int bottom) {
        boolean overlaps(ScreenRect other) {
            return left < other.right && right > other.left && top < other.bottom && bottom > other.top;
        }

        ScreenRect inflate(int amount) {
            return new ScreenRect(left - amount, top - amount, right + amount, bottom + amount);
        }
    }
    private record LabelGeometry(Vec3 anchor, Vec3 directionA, Vec3 directionB) { }
    private record LabelCandidate(String text,
                                  ScreenPoint anchor,
                                  ScreenDirection direction,
                                  double distance,
                                  int accentArgb) { }

    private record Segment(Vec3 a, Vec3 b) { }
    private record NeonColor(float r, float g, float b) { }

    private PersistentSurveyRenderer() { }
}
