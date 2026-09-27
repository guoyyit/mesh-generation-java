package com.github.guoyyit.meshgenerationjava.geometry;

import com.fasterxml.jackson.annotation.JsonFormat;

/** Immutable 2D point/vector; serialized as a compact [x, y] array. */
@JsonFormat(shape = JsonFormat.Shape.ARRAY)
public record Point2D(double x, double y) {

    public double distanceTo(Point2D other) {
        return Math.hypot(x - other.x, y - other.y);
    }

    public Point2D midpoint(Point2D other) {
        return new Point2D((x + other.x) * 0.5, (y + other.y) * 0.5);
    }

    public Point2D plus(Point2D other) {
        return new Point2D(x + other.x, y + other.y);
    }

    public Point2D scale(double factor) {
        return new Point2D(x * factor, y * factor);
    }
}
