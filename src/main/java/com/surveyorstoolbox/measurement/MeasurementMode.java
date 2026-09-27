package com.surveyorstoolbox.measurement;

public enum MeasurementMode {
    DISTANCE("Distance", "Two points: direct, horizontal and XYZ delta"),
    POLYLINE("Polyline", "Add connected points; sneak-use to finish"),
    RECTANGLE("Rectangle", "Two corners: width, length, perimeter and area"),
    BOX("Box / Volume", "Two corners: dimensions, surface area and volume"),
    POLYGON("Polygon", "Add corners; sneak-use to close and calculate"),
    WALK_SURVEY("Walk Survey", "Click start, then walk the boundary; sneak-use to finish"),
    INTERIOR("Interior", "Click an enclosed floor to flood-measure it"),
    SLOPE("Slope", "Two points: rise, run, percent, ratio and angle"),
    HEIGHT("Height", "Two points: vertical height difference"),
    CIRCLE("Circle", "Center then edge: radius, diameter, circumference and area"),
    SPACING("Spacing", "Two points: length plus equal-spacing suggestions"),
    CENTER("Center Finder", "Two corners: center position and dimensions");

    private final String displayName;
    private final String description;

    MeasurementMode(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    public MeasurementMode next() {
        MeasurementMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    public MeasurementMode previous() {
        MeasurementMode[] values = values();
        return values[(ordinal() - 1 + values.length) % values.length];
    }
}
