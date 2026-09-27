package com.surveyorstoolbox.measurement;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record MeasurementSnapshot(
        MeasurementMode mode,
        List<BlockPos> points,
        List<Vec3> walkPath,
        Set<BlockPos> interiorCells,
        String label
) {
    public static final int MAX_POINTS = 2048;
    public static final int MAX_WALK_SAMPLES = 768;
    public static final int MAX_INTERIOR_CELLS = 2048;

    private static final double WALK_FIXED_SCALE = 1000.0D;
    private static final int MAX_LABEL_LENGTH = 256;
    private static final double MAX_PERSISTED_SPAN = 16_384.0D;

    public MeasurementSnapshot {
        mode = mode == null ? MeasurementMode.DISTANCE : mode;
        points = List.copyOf(points == null ? List.of() : points);
        walkPath = List.copyOf(walkPath == null ? List.of() : walkPath);
        interiorCells = Set.copyOf(interiorCells == null ? Set.of() : interiorCells);
        label = sanitizeLabel(label);
    }

    public boolean isValidForPersistence() {
        if (label.isBlank()) return false;
        if (points.size() > MAX_POINTS) return false;
        if (walkPath.size() > MAX_WALK_SAMPLES) return false;
        if (interiorCells.size() > MAX_INTERIOR_CELLS) return false;
        if (!finiteWalkPath()) return false;
        if (span() > MAX_PERSISTED_SPAN) return false;

        return switch (mode) {
            case DISTANCE, RECTANGLE, BOX, SLOPE, HEIGHT, CIRCLE, SPACING, CENTER ->
                    points.size() == 2 && walkPath.isEmpty() && interiorCells.isEmpty();
            case POLYLINE ->
                    points.size() >= 2 && walkPath.isEmpty() && interiorCells.isEmpty();
            case POLYGON ->
                    points.size() >= 3 && walkPath.isEmpty() && interiorCells.isEmpty();
            case WALK_SURVEY ->
                    points.isEmpty() && walkPath.size() >= 3 && interiorCells.isEmpty();
            case INTERIOR ->
                    points.isEmpty() && walkPath.isEmpty() && !interiorCells.isEmpty();
        };
    }

    public MeasurementSnapshot compacted() {
        List<Vec3> compactWalk = walkPath;
        if (walkPath.size() > MAX_WALK_SAMPLES) {
            compactWalk = sample(walkPath, MAX_WALK_SAMPLES);
        }
        return new MeasurementSnapshot(mode, points, compactWalk, interiorCells, label);
    }

    public CompoundTag toTag() {
        MeasurementSnapshot compact = compacted();
        CompoundTag tag = new CompoundTag();
        tag.putString("Mode", compact.mode().name());
        tag.putString("Label", compact.label());

        long[] packedPoints = new long[compact.points().size()];
        for (int i = 0; i < compact.points().size(); i++) {
            packedPoints[i] = compact.points().get(i).asLong();
        }
        tag.putLongArray("Points", packedPoints);

        long[] packedWalk = new long[compact.walkPath().size() * 3];
        for (int i = 0; i < compact.walkPath().size(); i++) {
            Vec3 point = compact.walkPath().get(i);
            int base = i * 3;
            packedWalk[base] = Math.round(point.x * WALK_FIXED_SCALE);
            packedWalk[base + 1] = Math.round(point.y * WALK_FIXED_SCALE);
            packedWalk[base + 2] = Math.round(point.z * WALK_FIXED_SCALE);
        }
        tag.putLongArray("Walk", packedWalk);

        long[] packedInterior = compact.interiorCells().stream()
                .limit(MAX_INTERIOR_CELLS)
                .mapToLong(BlockPos::asLong)
                .toArray();
        tag.putLongArray("Interior", packedInterior);
        return tag;
    }

    public static MeasurementSnapshot fromTag(CompoundTag tag) {
        MeasurementMode mode;
        try {
            mode = MeasurementMode.valueOf(tag.getString("Mode"));
        } catch (IllegalArgumentException ignored) {
            mode = MeasurementMode.DISTANCE;
        }

        long[] packedPoints = tag.getLongArray("Points");
        if (packedPoints.length > MAX_POINTS) {
            packedPoints = Arrays.copyOf(packedPoints, MAX_POINTS);
        }
        List<BlockPos> points = new ArrayList<>(packedPoints.length);
        for (long packed : packedPoints) {
            points.add(BlockPos.of(packed));
        }

        long[] packedWalk = tag.getLongArray("Walk");
        int walkCount = Math.min(packedWalk.length / 3, MAX_WALK_SAMPLES);
        List<Vec3> walk = new ArrayList<>(walkCount);
        for (int i = 0; i < walkCount; i++) {
            int base = i * 3;
            walk.add(new Vec3(
                    packedWalk[base] / WALK_FIXED_SCALE,
                    packedWalk[base + 1] / WALK_FIXED_SCALE,
                    packedWalk[base + 2] / WALK_FIXED_SCALE
            ));
        }

        long[] packedInterior = tag.getLongArray("Interior");
        int interiorCount = Math.min(packedInterior.length, MAX_INTERIOR_CELLS);
        Set<BlockPos> interior = new HashSet<>(interiorCount);
        for (int i = 0; i < interiorCount; i++) {
            interior.add(BlockPos.of(packedInterior[i]));
        }

        return new MeasurementSnapshot(mode, points, walk, interior, tag.getString("Label"));
    }

    public Vec3 anchor() {
        if (mode == MeasurementMode.CIRCLE && points.size() >= 1) {
            return topCenter(points.get(0));
        }

        if (mode == MeasurementMode.CENTER && points.size() >= 2) {
            return midpoint(center(points.get(0)), center(points.get(1)));
        }

        if (mode == MeasurementMode.RECTANGLE && points.size() >= 2) {
            List<Vec3> rectangle = rectangleLoop(points.get(0), points.get(1));
            if (!rectangle.isEmpty()) return boundsCenter(rectangle);
        }

        if (mode == MeasurementMode.BOX && points.size() >= 2) {
            BlockPos a = points.get(0);
            BlockPos b = points.get(1);
            return new Vec3(
                    (Math.min(a.getX(), b.getX()) + Math.max(a.getX(), b.getX()) + 1.0D) * 0.5D,
                    (Math.min(a.getY(), b.getY()) + Math.max(a.getY(), b.getY()) + 1.0D) * 0.5D,
                    (Math.min(a.getZ(), b.getZ()) + Math.max(a.getZ(), b.getZ()) + 1.0D) * 0.5D
            );
        }

        if (!interiorCells.isEmpty()) {
            double x = 0.0D;
            double y = 0.0D;
            double z = 0.0D;
            for (BlockPos pos : interiorCells) {
                x += pos.getX() + 0.5D;
                y += pos.getY() + 1.05D;
                z += pos.getZ() + 0.5D;
            }
            double count = interiorCells.size();
            return new Vec3(x / count, y / count, z / count);
        }

        if (!walkPath.isEmpty()) {
            return boundsCenter(walkPath).add(0.0D, 0.35D, 0.0D);
        }

        if (!points.isEmpty()) {
            List<Vec3> vecs = points.stream().map(MeasurementSnapshot::center).toList();
            return boundsCenter(vecs).add(0.0D, 0.3D, 0.0D);
        }

        return Vec3.ZERO;
    }

    public double distanceTo(Vec3 target) {
        if (target == null) return Double.POSITIVE_INFINITY;

        return switch (mode) {
            case DISTANCE, HEIGHT, SPACING ->
                    twoPointSegmentDistance(target);

            case SLOPE -> {
                if (points.size() < 2) yield Double.POSITIVE_INFINITY;
                Vec3 a = center(points.get(0));
                Vec3 b = center(points.get(1));
                Vec3 runEnd = new Vec3(b.x, a.y, b.z);
                yield Math.min(
                        distancePointToSegment(target, a, b),
                        distancePointToSegment(target, a, runEnd)
                );
            }

            case POLYLINE -> distanceToPointPath(target, false);
            case POLYGON -> distanceToPointPath(target, true);

            case WALK_SURVEY ->
                    walkPath.size() >= 2
                            ? distanceToPath(target, walkPath, true)
                            : anchor().distanceTo(target);

            case INTERIOR -> distanceToInterior(target);

            case RECTANGLE -> {
                if (points.size() < 2) yield Double.POSITIVE_INFINITY;
                List<Vec3> loop = rectangleLoop(points.get(0), points.get(1));
                yield loop.isEmpty() ? anchor().distanceTo(target) : distanceToPath(target, loop, true);
            }

            case BOX -> distanceToBox(target);

            case CIRCLE -> distanceToCircle(target);

            case CENTER -> {
                if (points.size() < 2) yield anchor().distanceTo(target);
                Vec3 c = midpoint(center(points.get(0)), center(points.get(1)));
                yield c.distanceTo(target);
            }
        };
    }

    public double span() {
        List<Vec3> samples = new ArrayList<>();

        for (BlockPos point : points) {
            samples.add(center(point));
        }
        samples.addAll(walkPath);
        for (BlockPos cell : interiorCells) {
            samples.add(new Vec3(cell.getX() + 0.5D, cell.getY() + 1.0D, cell.getZ() + 0.5D));
        }

        if (samples.size() < 2) return 0.0D;

        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;

        for (Vec3 point : samples) {
            minX = Math.min(minX, point.x);
            minY = Math.min(minY, point.y);
            minZ = Math.min(minZ, point.z);
            maxX = Math.max(maxX, point.x);
            maxY = Math.max(maxY, point.y);
            maxZ = Math.max(maxZ, point.z);
        }

        double dx = maxX - minX;
        double dy = maxY - minY;
        double dz = maxZ - minZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private double twoPointSegmentDistance(Vec3 target) {
        if (points.size() < 2) return Double.POSITIVE_INFINITY;
        return distancePointToSegment(target, center(points.get(0)), center(points.get(1)));
    }

    private double distanceToPointPath(Vec3 target, boolean close) {
        if (points.size() < 2) return anchor().distanceTo(target);
        List<Vec3> vecs = points.stream().map(MeasurementSnapshot::topCenter).toList();
        return distanceToPath(target, vecs, close);
    }

    private double distanceToInterior(Vec3 target) {
        double best = Double.POSITIVE_INFINITY;
        for (BlockPos cell : interiorCells) {
            double x0 = cell.getX();
            double x1 = x0 + 1.0D;
            double y = cell.getY() + 1.035D;
            double z0 = cell.getZ();
            double z1 = z0 + 1.0D;

            double clampedX = Math.max(x0, Math.min(target.x, x1));
            double clampedZ = Math.max(z0, Math.min(target.z, z1));
            double dx = target.x - clampedX;
            double dy = target.y - y;
            double dz = target.z - clampedZ;
            best = Math.min(best, Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
        return best;
    }

    private double distanceToBox(Vec3 target) {
        if (points.size() < 2) return Double.POSITIVE_INFINITY;
        BlockPos a = points.get(0);
        BlockPos b = points.get(1);

        double x0 = Math.min(a.getX(), b.getX());
        double y0 = Math.min(a.getY(), b.getY());
        double z0 = Math.min(a.getZ(), b.getZ());
        double x1 = Math.max(a.getX(), b.getX()) + 1.0D;
        double y1 = Math.max(a.getY(), b.getY()) + 1.0D;
        double z1 = Math.max(a.getZ(), b.getZ()) + 1.0D;

        double dx = Math.max(Math.max(x0 - target.x, 0.0D), target.x - x1);
        double dy = Math.max(Math.max(y0 - target.y, 0.0D), target.y - y1);
        double dz = Math.max(Math.max(z0 - target.z, 0.0D), target.z - z1);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private double distanceToCircle(Vec3 target) {
        if (points.size() < 2) return Double.POSITIVE_INFINITY;

        Vec3 center = topCenter(points.get(0));
        Vec3 edge = topCenter(points.get(1));
        double radius = Math.hypot(edge.x - center.x, edge.z - center.z);
        double planar = Math.hypot(target.x - center.x, target.z - center.z);
        double radialDelta = Math.abs(planar - radius);
        double verticalDelta = target.y - center.y;
        return Math.sqrt(radialDelta * radialDelta + verticalDelta * verticalDelta);
    }

    private boolean finiteWalkPath() {
        for (Vec3 point : walkPath) {
            if (!Double.isFinite(point.x) || !Double.isFinite(point.y) || !Double.isFinite(point.z)) {
                return false;
            }
        }
        return true;
    }

    private static List<Vec3> rectangleLoop(BlockPos a, BlockPos b) {
        int dx = b.getX() - a.getX();
        int dy = b.getY() - a.getY();
        int dz = b.getZ() - a.getZ();

        if (dy == 0) {
            double y = a.getY() + 1.02D;
            return List.of(
                    new Vec3(Math.min(a.getX(), b.getX()), y, Math.min(a.getZ(), b.getZ())),
                    new Vec3(Math.max(a.getX(), b.getX()) + 1.0D, y, Math.min(a.getZ(), b.getZ())),
                    new Vec3(Math.max(a.getX(), b.getX()) + 1.0D, y, Math.max(a.getZ(), b.getZ()) + 1.0D),
                    new Vec3(Math.min(a.getX(), b.getX()), y, Math.max(a.getZ(), b.getZ()) + 1.0D)
            );
        }

        if (dz == 0) {
            double z = a.getZ() + 0.5D;
            return List.of(
                    new Vec3(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), z),
                    new Vec3(Math.max(a.getX(), b.getX()) + 1.0D, Math.min(a.getY(), b.getY()), z),
                    new Vec3(Math.max(a.getX(), b.getX()) + 1.0D, Math.max(a.getY(), b.getY()) + 1.0D, z),
                    new Vec3(Math.min(a.getX(), b.getX()), Math.max(a.getY(), b.getY()) + 1.0D, z)
            );
        }

        if (dx == 0) {
            double x = a.getX() + 0.5D;
            return List.of(
                    new Vec3(x, Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ())),
                    new Vec3(x, Math.min(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()) + 1.0D),
                    new Vec3(x, Math.max(a.getY(), b.getY()) + 1.0D, Math.max(a.getZ(), b.getZ()) + 1.0D),
                    new Vec3(x, Math.max(a.getY(), b.getY()) + 1.0D, Math.min(a.getZ(), b.getZ()))
            );
        }

        return List.of();
    }

    private static double distanceToPath(Vec3 target, List<Vec3> path, boolean close) {
        double best = Double.POSITIVE_INFINITY;
        for (int i = 1; i < path.size(); i++) {
            best = Math.min(best, distancePointToSegment(target, path.get(i - 1), path.get(i)));
        }
        if (close && path.size() > 2) {
            best = Math.min(best, distancePointToSegment(target, path.get(path.size() - 1), path.get(0)));
        }
        return best;
    }

    private static double distancePointToSegment(Vec3 p, Vec3 a, Vec3 b) {
        Vec3 ab = b.subtract(a);
        double lengthSq = ab.lengthSqr();
        if (lengthSq < 1.0e-8D) return p.distanceTo(a);

        double t = p.subtract(a).dot(ab) / lengthSq;
        t = Math.max(0.0D, Math.min(1.0D, t));
        return p.distanceTo(a.add(ab.scale(t)));
    }

    private static Vec3 boundsCenter(List<Vec3> points) {
        if (points.isEmpty()) return Vec3.ZERO;

        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;

        for (Vec3 point : points) {
            minX = Math.min(minX, point.x);
            minY = Math.min(minY, point.y);
            minZ = Math.min(minZ, point.z);
            maxX = Math.max(maxX, point.x);
            maxY = Math.max(maxY, point.y);
            maxZ = Math.max(maxZ, point.z);
        }

        return new Vec3(
                (minX + maxX) * 0.5D,
                (minY + maxY) * 0.5D,
                (minZ + maxZ) * 0.5D
        );
    }

    private static Vec3 midpoint(Vec3 a, Vec3 b) {
        return a.add(b).scale(0.5D);
    }

    private static Vec3 center(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
    }

    private static Vec3 topCenter(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5D, pos.getY() + 1.035D, pos.getZ() + 0.5D);
    }

    private static String sanitizeLabel(String input) {
        if (input == null) return "";

        StringBuilder out = new StringBuilder(Math.min(input.length(), MAX_LABEL_LENGTH));
        boolean previousSpace = false;

        for (int i = 0; i < input.length() && out.length() < MAX_LABEL_LENGTH; i++) {
            char c = input.charAt(i);
            boolean space = Character.isWhitespace(c) || Character.isISOControl(c);

            if (space) {
                if (!previousSpace && !out.isEmpty()) {
                    out.append(' ');
                }
                previousSpace = true;
            } else {
                out.append(c);
                previousSpace = false;
            }
        }

        int length = out.length();
        if (length > 0 && out.charAt(length - 1) == ' ') {
            out.setLength(length - 1);
        }
        return out.toString();
    }

    private static List<Vec3> sample(List<Vec3> input, int max) {
        if (input.size() <= max) return List.copyOf(input);

        int stride = (int) Math.ceil(input.size() / (double) max);
        List<Vec3> out = new ArrayList<>(max + 1);
        for (int i = 0; i < input.size(); i += stride) {
            out.add(input.get(i));
        }

        Vec3 last = input.get(input.size() - 1);
        if (!out.get(out.size() - 1).equals(last)) {
            out.add(last);
        }

        if (out.size() > max) {
            out = new ArrayList<>(out.subList(0, max - 1));
            out.add(last);
        }
        return List.copyOf(out);
    }
}
