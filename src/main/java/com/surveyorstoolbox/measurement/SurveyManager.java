package com.surveyorstoolbox.measurement;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class SurveyManager {
    private static MeasurementMode mode = MeasurementMode.DISTANCE;
    private static final List<BlockPos> points = new ArrayList<>();
    private static final List<Vec3> walkPath = new ArrayList<>();
    private static final Set<BlockPos> interiorCells = new HashSet<>();
    private static final List<BlockPos> pointsView = Collections.unmodifiableList(points);
    private static final List<Vec3> walkPathView = Collections.unmodifiableList(walkPath);
    private static final Set<BlockPos> interiorCellsView = Collections.unmodifiableSet(interiorCells);

    private static Level activeLevel;
    private static Player activePlayer;
    private static boolean walking;
    private static boolean finalized;
    private static boolean walkCapWarned;
    private static Vec3 lastWalkPoint;
    private static double walkTravelledPlanar;
    private static String resultLabel;
    private static long finalizedUntilGameTime;
    private static long visualRevision;

    // Completed geometry + label remains as a temporary world-space hologram.
    private static final int COMPLETED_HOLOGRAM_TICKS = 8 * 20;

    private static final int MAX_INTERIOR_CELLS = 8192;
    private static final int MAX_MANUAL_POINTS = 2048;
    private static final int MAX_WALK_POINTS = 20000;
    private static final double WALK_POINT_SPACING = 0.70D;
    private static final double WALK_AUTO_CLOSE_DISTANCE = 1.50D;
    private static final double WALK_AUTO_CLOSE_MIN_DISTANCE = 8.0D;
    private static final int WALK_AUTO_CLOSE_MIN_POINTS = 12;

    public static void ensureLevel(Player player) {
        if (activeLevel != player.getLevel() || activePlayer != player) {
            activeLevel = player.getLevel();
            activePlayer = player;
            resetMeasurement();
        }
    }

    public static void clearForDisconnect() {
        resetMeasurement();
        activeLevel = null;
        activePlayer = null;
    }

    public static void tickDisplay(Player player) {
        ensureLevel(player);
        if (finalized
                && finalizedUntilGameTime > 0L
                && player.getLevel().getGameTime() >= finalizedUntilGameTime) {
            resetMeasurement();
        }
    }

    public static void nextMode(Player player) {
        ensureLevel(player);
        mode = mode.next();
        resetMeasurement();
        showMode(player);
    }

    public static void previousMode(Player player) {
        ensureLevel(player);
        mode = mode.previous();
        resetMeasurement();
        showMode(player);
    }

    public static void clear(Player player) {
        ensureLevel(player);
        resetMeasurement();
        message(player, "Survey cleared.", ChatFormatting.GRAY);
    }

    public static void handleAirUse(Player player) {
        if (player == null || !player.isShiftKeyDown()) return;
        ensureLevel(player);
        finishCurrent(player);
    }

    private static void showMode(Player player) {
        message(player, "Surveyor's Ruler — " + mode.displayName() + " | " + mode.description(), ChatFormatting.AQUA);
    }

    public static void handleBlockUse(BlockPos clickedPos, Player player) {
        if (player == null) return;
        ensureLevel(player);

        if (player.isShiftKeyDown()) {
            finishCurrent(player);
            return;
        }

        if (finalized) {
            resetMeasurement();
        }

        if (points.size() >= 2 && mode != MeasurementMode.POLYLINE && mode != MeasurementMode.POLYGON) {
            points.clear();
        }

        switch (mode) {
            case INTERIOR -> {
                if (measureInterior(clickedPos, player)) {
                    finalizeMeasurement(player);
                }
            }
            case WALK_SURVEY -> {
                if (walking) {
                    message(player, "Walk Survey is already running. Return near the start or sneak-use to finish.", ChatFormatting.YELLOW);
                } else {
                    startWalkSurvey(player);
                }
            }
            case POLYLINE, POLYGON -> addManualPoint(clickedPos, player);
            default -> {
                points.add(clickedPos.immutable());
                touchVisuals();
                if (points.size() == 1) {
                    message(player, mode.displayName() + ": first point " + formatPos(clickedPos), ChatFormatting.AQUA);
                } else {
                    if (calculateTwoPointMode(player, points.get(0), points.get(1))) {
                        finalizeMeasurement(player);
                    }
                }
            }
        }
    }

    private static void addManualPoint(BlockPos clickedPos, Player player) {
        if (points.size() >= MAX_MANUAL_POINTS) {
            message(player, "Point limit reached (" + MAX_MANUAL_POINTS + "). Finish or clear this measurement.", ChatFormatting.YELLOW);
            return;
        }

        points.add(clickedPos.immutable());
        touchVisuals();
        message(player, mode.displayName() + ": point " + points.size() + " added at " + formatPos(clickedPos), ChatFormatting.AQUA);

        if (mode == MeasurementMode.POLYLINE && points.size() >= 2) {
            message(player, "Polyline: " + fmt(polylineLength(points, false)) + " blocks (" + points.size() + " nodes)", ChatFormatting.AQUA);
        }
    }

    private static void finishCurrent(Player player) {
        if (finalized) {
            message(player, "Measurement complete. Press R to clear it now.", ChatFormatting.GRAY);
            return;
        }

        if (mode == MeasurementMode.WALK_SURVEY && walking) {
            finishWalkSurvey(player, false);
            return;
        }

        if (mode == MeasurementMode.POLYLINE) {
            if (points.size() < 2) {
                message(player, "Polyline needs at least two points.", ChatFormatting.RED);
                return;
            }
            resultMessage(player, "Polyline complete: " + fmt(polylineLength(points, false)) + " blocks across " + (points.size() - 1) + " segments.", ChatFormatting.GREEN);
            finalizeMeasurement(player);
            return;
        }

        if (mode == MeasurementMode.POLYGON) {
            if (points.size() < 3) {
                message(player, "Polygon needs at least three points.", ChatFormatting.RED);
                return;
            }
            resultMessage(player, "Polygon: perimeter " + fmt(polylineLength(points, true)) + " blocks | area " + fmt(polygonAreaBlocks(points)) + " blocks²", ChatFormatting.GREEN);
            finalizeMeasurement(player);
            return;
        }

        if (finalized || !points.isEmpty() || !interiorCells.isEmpty()) {
            resetMeasurement();
            message(player, "Measurement cleared.", ChatFormatting.GRAY);
        }
    }

    private static boolean calculateTwoPointMode(Player player, BlockPos a, BlockPos b) {
        int dx = b.getX() - a.getX();
        int dy = b.getY() - a.getY();
        int dz = b.getZ() - a.getZ();

        double direct = Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz);
        double horizontal = Math.sqrt((double) dx * dx + (double) dz * dz);

        switch (mode) {
            case DISTANCE -> resultMessage(player,
                    "Distance " + fmt(direct) + " | horizontal " + fmt(horizontal)
                            + " | ΔX " + signed(dx) + " ΔY " + signed(dy) + " ΔZ " + signed(dz),
                    ChatFormatting.GREEN);

            case RECTANGLE -> {
                if (dy == 0) {
                    reportRectangle(player, "XZ", Math.abs(dx) + 1, Math.abs(dz) + 1);
                } else if (dz == 0) {
                    reportRectangle(player, "XY", Math.abs(dx) + 1, Math.abs(dy) + 1);
                } else if (dx == 0) {
                    reportRectangle(player, "YZ", Math.abs(dz) + 1, Math.abs(dy) + 1);
                } else {
                    message(player, "Rectangle points must share an X, Y, or Z plane. Use Box / Volume for 3D selections.", ChatFormatting.YELLOW);
                    return false;
                }
            }

            case BOX -> {
                int x = Math.abs(dx) + 1;
                int y = Math.abs(dy) + 1;
                int z = Math.abs(dz) + 1;
                long volume = (long) x * y * z;
                long surface = 2L * ((long) x * y + (long) x * z + (long) y * z);
                resultMessage(player, "Box " + x + " × " + y + " × " + z + " | volume " + volume + " blocks³ | surface " + surface + " blocks²", ChatFormatting.GREEN);
            }

            case SLOPE -> {
                if (horizontal < 0.0001) {
                    resultMessage(player, "Vertical line: rise " + signed(dy) + " blocks; horizontal run is 0.", ChatFormatting.GREEN);
                } else {
                    double pct = (dy / horizontal) * 100.0;
                    double angle = Math.toDegrees(Math.atan2(dy, horizontal));
                    double ratio = Math.abs(dy) < 0.0001 ? Double.POSITIVE_INFINITY : horizontal / Math.abs(dy);
                    String ratioText = Double.isInfinite(ratio) ? "level" : "1:" + fmt(ratio);
                    resultMessage(player, "Slope rise " + signed(dy) + " | run " + fmt(horizontal) + " | "
                            + fmt(pct) + "% | " + ratioText + " | " + fmt(angle) + "°", ChatFormatting.GREEN);
                }
            }

            case HEIGHT -> resultMessage(player,
                    "Height difference: " + Math.abs(dy) + " blocks (" + signed(dy) + " from first point)",
                    ChatFormatting.GREEN);

            case CIRCLE -> {
                double radius = horizontal;
                long footprint = circleFootprintBlocks(radius);
                resultMessage(player, "Circle radius " + fmt(radius) + " | diameter " + fmt(radius * 2.0)
                        + " | circumference " + fmt(2.0 * Math.PI * radius)
                        + " | theoretical area " + fmt(Math.PI * radius * radius) + " blocks²"
                        + " | approx. interior ~" + footprint + " blocks²", ChatFormatting.GREEN);
            }

            case SPACING -> calculateSpacing(player, dx, dz, horizontal);

            case CENTER -> {
                double cx = (a.getX() + b.getX()) / 2.0 + 0.5;
                double cy = (a.getY() + b.getY()) / 2.0 + 0.5;
                double cz = (a.getZ() + b.getZ()) / 2.0 + 0.5;
                int sx = Math.abs(dx) + 1;
                int sy = Math.abs(dy) + 1;
                int sz = Math.abs(dz) + 1;
                resultMessage(player, "Center " + fmt(cx) + ", " + fmt(cy) + ", " + fmt(cz)
                        + " | size " + sx + " × " + sy + " × " + sz, ChatFormatting.GREEN);
            }

            default -> { }
        }
        return true;
    }


    private static void reportRectangle(Player player, String plane, int sideA, int sideB) {
        long area = (long) sideA * sideB;
        long perimeter = 2L * (sideA + sideB);
        resultMessage(player, "Rectangle " + plane + " " + sideA + " × " + sideB
                + " | area " + area + " blocks² | perimeter " + perimeter, ChatFormatting.GREEN);
    }

    private static void calculateSpacing(Player player, int dx, int dz, double horizontal) {
        if (dx != 0 && dz != 0) {
            resultMessage(player, "Diagonal run " + fmt(horizontal) + " blocks | whole-block spacing suggestions are only shown for axis-aligned lines.", ChatFormatting.GREEN);
            return;
        }

        int length = Math.abs(dx != 0 ? dx : dz);
        if (length == 0) {
            message(player, "Spacing needs two different horizontal positions.", ChatFormatting.YELLOW);
            return;
        }

        List<Integer> divisors = usefulDivisors(length);
        String suggestions = divisors.isEmpty()
                ? "no useful whole-block equal divisions"
                : divisors.stream()
                .map(sections -> sections + " sections @ " + (length / sections) + " blocks")
                .reduce((x, y) -> x + ", " + y)
                .orElse("");

        resultMessage(player, "Run " + length + " blocks | " + suggestions, ChatFormatting.GREEN);
    }

    private static void startWalkSurvey(Player player) {
        resetMeasurement();
        walking = true;

        Vec3 initial = player.position();
        walkPath.add(initial);
        touchVisuals();
        lastWalkPoint = initial;
        walkTravelledPlanar = 0.0;

        message(player, "Walk Survey started at your position. Walk the boundary; return near the start to auto-close, or sneak-use to finish.", ChatFormatting.AQUA);
    }

    public static void tickWalkSurvey(Player player) {
        ensureLevel(player);
        if (!walking || mode != MeasurementMode.WALK_SURVEY) return;

        Vec3 now = player.position();
        if (lastWalkPoint == null || horizontalDistance(now, lastWalkPoint) < WALK_POINT_SPACING) return;

        if (walkPath.size() >= MAX_WALK_POINTS) {
            if (!walkCapWarned) {
                walkCapWarned = true;
                message(player, "Walk Survey point limit reached. Return to the start or sneak-use to finish.", ChatFormatting.YELLOW);
            }
            return;
        }

        walkTravelledPlanar += horizontalDistance(lastWalkPoint, now);
        walkPath.add(now);
        touchVisuals();
        lastWalkPoint = now;

        if (walkPath.size() >= WALK_AUTO_CLOSE_MIN_POINTS
                && walkTravelledPlanar >= WALK_AUTO_CLOSE_MIN_DISTANCE
                && horizontalDistance(now, walkPath.get(0)) <= WALK_AUTO_CLOSE_DISTANCE
                && Math.abs(now.y - walkPath.get(0).y) <= 2.0D) {
            finishWalkSurvey(player, true);
        }
    }

    private static void finishWalkSurvey(Player player, boolean automatic) {
        if (walkPath.size() < 3 || walkTravelledPlanar < 2.0) {
            message(player, "Walk Survey needs a longer path before it can be closed.", ChatFormatting.RED);
            return;
        }

        walking = false;
        touchVisuals();

        double tracedGround = 0.0;
        double tracedPlanar = 0.0;
        for (int i = 1; i < walkPath.size(); i++) {
            tracedGround += walkPath.get(i - 1).distanceTo(walkPath.get(i));
            tracedPlanar += horizontalDistance(walkPath.get(i - 1), walkPath.get(i));
        }

        Vec3 first = walkPath.get(0);
        Vec3 last = walkPath.get(walkPath.size() - 1);
        double closingGround = last.distanceTo(first);
        double closingPlanar = horizontalDistance(last, first);
        double perimeterPlanar = tracedPlanar + closingPlanar;
        double perimeterGround = tracedGround + closingGround;
        double area = polygonAreaVec(walkPath);

        String closeNote = automatic
                ? " | auto-closed"
                : closingPlanar > 3.0 ? " | straight-line close " + fmt(closingPlanar) + " blocks" : "";

        resultMessage(player, "Walk Survey: perimeter " + fmt(perimeterPlanar)
                + " | terrain perimeter " + fmt(perimeterGround)
                + " | enclosed area " + fmt(area) + " blocks²"
                + closeNote, ChatFormatting.GREEN);
        finalizeMeasurement(player);
    }

    private static boolean measureInterior(BlockPos clickedFloor, Player player) {
        Level level = player.getLevel();
        interiorCells.clear();

        BlockPos start = normalizeFloorStart(level, clickedFloor);
        if (start == null) {
            message(player, "Could not find a walkable floor at that position.", ChatFormatting.RED);
            return false;
        }

        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        interiorCells.add(start);
        boolean capped = false;

        while (!queue.isEmpty()) {
            BlockPos current = queue.removeFirst();

            if (interiorCells.size() >= MAX_INTERIOR_CELLS) {
                capped = true;
                break;
            }

            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos next = current.relative(dir);
                if (interiorCells.contains(next)) continue;

                // Stay on the same floor elevation. Allowing +/-1 here lets a scan climb onto
                // a wall and then spill to the exterior of an otherwise enclosed room.
                if (isWalkableFloor(level, next)) {
                    interiorCells.add(next);
                    queue.addLast(next);
                }
            }
        }

        long perimeterEdges = 0;
        for (BlockPos floor : interiorCells) {
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                if (!interiorCells.contains(floor.relative(dir))) {
                    perimeterEdges++;
                }
            }
        }

        touchVisuals();
        String suffix = capped ? " (scan cap reached; room may be open or larger than the scan limit)" : "";
        resultMessage(player, "Interior: area " + interiorCells.size()
                + " blocks² | perimeter approx. " + perimeterEdges + " blocks" + suffix,
                capped ? ChatFormatting.YELLOW : ChatFormatting.GREEN);
        return true;
    }

    private static BlockPos normalizeFloorStart(Level level, BlockPos clicked) {
        if (isWalkableFloor(level, clicked)) return clicked;
        if (isWalkableFloor(level, clicked.below())) return clicked.below();
        if (isWalkableFloor(level, clicked.above())) return clicked.above();
        return null;
    }

    private static boolean isWalkableFloor(Level level, BlockPos floor) {
        BlockState floorState = level.getBlockState(floor);
        if (floorState.isAir() || floorState.getCollisionShape(level, floor).isEmpty()) {
            return false;
        }

        BlockPos feet = floor.above();
        BlockPos head = floor.above(2);
        return level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                && level.getBlockState(head).getCollisionShape(level, head).isEmpty();
    }

    public static MeasurementMode getMode() {
        return mode;
    }

    public static List<BlockPos> getPointsForRender() {
        return pointsView;
    }

    public static List<Vec3> getWalkPathForRender() {
        return walkPathView;
    }

    public static Set<BlockPos> getInteriorCellsForRender() {
        return interiorCellsView;
    }

    public static boolean isWalking() {
        return walking;
    }

    public static boolean isFinalized() {
        return finalized;
    }

    public static String getResultLabel() {
        return resultLabel;
    }

    public static boolean hasRenderableMeasurement() {
        return !points.isEmpty() || !walkPath.isEmpty() || !interiorCells.isEmpty();
    }

    public static long getVisualRevision() {
        return visualRevision;
    }

    public static MeasurementSnapshot snapshotForPersistence() {
        if (!finalized || resultLabel == null || resultLabel.isBlank()) return null;
        MeasurementSnapshot snapshot = new MeasurementSnapshot(mode, points, walkPath, interiorCells, resultLabel).compacted();
        return snapshot.isValidForPersistence() ? snapshot : null;
    }

    private static long circleFootprintBlocks(double radius) {
        if (radius <= 0.0D) return 1L;
        if (radius > 256.0D) {
            return Math.round(Math.PI * radius * radius);
        }

        int extent = (int) Math.ceil(radius);
        double radiusSq = radius * radius + 1.0e-7;
        long count = 0L;
        for (int x = -extent; x <= extent; x++) {
            for (int z = -extent; z <= extent; z++) {
                if ((double) x * x + (double) z * z <= radiusSq) count++;
            }
        }
        return count;
    }

    private static List<Integer> usefulDivisors(int length) {
        List<Integer> out = new ArrayList<>();
        for (int sections = 2; sections <= Math.min(12, length); sections++) {
            if (length % sections == 0) out.add(sections);
        }
        return out;
    }

    private static double polylineLength(List<BlockPos> nodes, boolean close) {
        double total = 0.0;
        for (int i = 1; i < nodes.size(); i++) {
            total += center(nodes.get(i - 1)).distanceTo(center(nodes.get(i)));
        }
        if (close && nodes.size() > 2) {
            total += center(nodes.get(nodes.size() - 1)).distanceTo(center(nodes.get(0)));
        }
        return total;
    }

    private static double polygonAreaBlocks(List<BlockPos> nodes) {
        return polygonAreaVec(nodes.stream().map(SurveyManager::center).toList());
    }

    private static double polygonAreaVec(List<Vec3> nodes) {
        if (nodes.size() < 3) return 0.0;
        double sum = 0.0;
        for (int i = 0; i < nodes.size(); i++) {
            Vec3 a = nodes.get(i);
            Vec3 b = nodes.get((i + 1) % nodes.size());
            sum += a.x * b.z - b.x * a.z;
        }
        return Math.abs(sum) * 0.5;
    }

    private static Vec3 center(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    private static double horizontalDistance(Vec3 a, Vec3 b) {
        double dx = b.x - a.x;
        double dz = b.z - a.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static String signed(int value) {
        return value >= 0 ? "+" + value : Integer.toString(value);
    }

    private static String formatPos(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    private static String fmt(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.005) {
            return Long.toString(Math.round(value));
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static void finalizeMeasurement(Player player) {
        finalized = true;
        finalizedUntilGameTime = player.getLevel().getGameTime() + COMPLETED_HOLOGRAM_TICKS;
        touchVisuals();
    }

    private static void resultMessage(Player player, String text, ChatFormatting color) {
        resultLabel = text;
        touchVisuals();
        message(player, text, color);
    }

    private static void message(Player player, String text, ChatFormatting color) {
        player.displayClientMessage(Component.literal(text).withStyle(color), true);
    }

    private static void resetMeasurement() {
        points.clear();
        walkPath.clear();
        interiorCells.clear();
        walking = false;
        finalized = false;
        finalizedUntilGameTime = 0L;
        walkCapWarned = false;
        lastWalkPoint = null;
        walkTravelledPlanar = 0.0;
        resultLabel = null;
        touchVisuals();
    }

    private static void touchVisuals() {
        visualRevision++;
    }

    private SurveyManager() { }
}
