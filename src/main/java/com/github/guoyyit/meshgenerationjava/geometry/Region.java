package com.github.guoyyit.meshgenerationjava.geometry;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Planar region: one outer boundary loop plus optional hole loops. */
public class Region {

    private final List<Point2D> outer;
    private final List<List<Point2D>> holes;

    @JsonCreator
    public Region(@JsonProperty("outer") List<Point2D> outer,
                  @JsonProperty("holes") List<List<Point2D>> holes) {
        this.outer = outer;
        this.holes = holes == null ? List.of() : holes;
    }

    public List<Point2D> getOuter() {
        return outer;
    }

    public List<List<Point2D>> getHoles() {
        return holes;
    }

    /** Region area (outer minus holes). */
    public double area() {
        double area = Math.abs(GeometryUtils.polygonSignedArea(outer));
        for (List<Point2D> hole : holes) {
            area -= Math.abs(GeometryUtils.polygonSignedArea(hole));
        }
        return area;
    }
}
