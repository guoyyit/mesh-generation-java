package com.github.guoyyit.meshgenerationjava.validation;

import com.github.guoyyit.meshgenerationjava.geometry.GeometryUtils;
import com.github.guoyyit.meshgenerationjava.geometry.Point2D;
import com.github.guoyyit.meshgenerationjava.geometry.Region;

import java.util.List;

/**
 * Validates region definitions and rejects them with classified errors:
 * duplicate points, self-intersections, degenerate loops, misplaced holes.
 */
public class RegionValidator {

    public void validate(Region region) {
        if (region == null) {
            throw new MeshValidationException(ErrorCode.INVALID_PARAMETER, "region must not be null");
        }
        List<Point2D> outer = region.getOuter();
        validateLoop(outer, "outer");
        List<List<Point2D>> holes = region.getHoles();
        for (int i = 0; i < holes.size(); i++) {
            validateLoop(holes.get(i), "hole[" + i + "]");
        }
        // Duplicate points across different loops.
        for (int i = 0; i < holes.size(); i++) {
            checkCrossDuplicates(outer, holes.get(i), "outer", "hole[" + i + "]");
            for (int j = i + 1; j < holes.size(); j++) {
                checkCrossDuplicates(holes.get(i), holes.get(j), "hole[" + i + "]", "hole[" + j + "]");
            }
        }
        // Hole placement relative to the outer boundary.
        for (int i = 0; i < holes.size(); i++) {
            List<Point2D> hole = holes.get(i);
            for (Point2D v : hole) {
                if (GeometryUtils.pointOnPolygonBoundary(v, outer)) {
                    throw new MeshValidationException(ErrorCode.HOLE_INTERSECTS_BOUNDARY,
                            "hole[" + i + "] vertex touches the outer boundary");
                }
                if (!GeometryUtils.pointInPolygon(v, outer)) {
                    throw new MeshValidationException(ErrorCode.HOLE_OUTSIDE_BOUNDARY,
                            "hole[" + i + "] vertex lies outside the outer boundary");
                }
            }
            if (loopsIntersect(hole, outer)) {
                throw new MeshValidationException(ErrorCode.HOLE_INTERSECTS_BOUNDARY,
                        "hole[" + i + "] edges intersect the outer boundary");
            }
        }
        // Holes must not overlap each other.
        for (int i = 0; i < holes.size(); i++) {
            for (int j = i + 1; j < holes.size(); j++) {
                List<Point2D> a = holes.get(i);
                List<Point2D> b = holes.get(j);
                if (loopsIntersect(a, b)
                        || GeometryUtils.pointInPolygon(a.get(0), b)
                        || GeometryUtils.pointInPolygon(b.get(0), a)
                        || GeometryUtils.pointOnPolygonBoundary(a.get(0), b)
                        || GeometryUtils.pointOnPolygonBoundary(b.get(0), a)) {
                    throw new MeshValidationException(ErrorCode.OVERLAPPING_HOLES,
                            "hole[" + i + "] overlaps hole[" + j + "]");
                }
            }
        }
    }

    private void validateLoop(List<Point2D> loop, String name) {
        if (loop == null || loop.size() < 3) {
            throw new MeshValidationException(ErrorCode.INVALID_PARAMETER,
                    name + " loop needs at least 3 points");
        }
        for (Point2D p : loop) {
            if (p == null || Double.isNaN(p.x()) || Double.isNaN(p.y())
                    || Double.isInfinite(p.x()) || Double.isInfinite(p.y())) {
                throw new MeshValidationException(ErrorCode.INVALID_PARAMETER,
                        name + " contains a null or non-finite point");
            }
        }
        // Duplicate / zero-length consecutive points.
        for (int i = 0; i < loop.size(); i++) {
            for (int j = i + 1; j < loop.size(); j++) {
                if (loop.get(i).distanceTo(loop.get(j)) < GeometryUtils.EPS) {
                    throw new MeshValidationException(ErrorCode.DUPLICATE_POINT,
                            name + " has duplicate points at index " + i + " and " + j);
                }
            }
        }
        // Self-intersection: non-adjacent edges must not touch.
        int n = loop.size();
        for (int i = 0; i < n; i++) {
            Point2D a1 = loop.get(i);
            Point2D a2 = loop.get((i + 1) % n);
            for (int j = i + 1; j < n; j++) {
                // Skip adjacent edges (they share exactly one endpoint).
                if (j == i || j == (i + 1) % n || i == (j + 1) % n) {
                    continue;
                }
                Point2D b1 = loop.get(j);
                Point2D b2 = loop.get((j + 1) % n);
                if (GeometryUtils.segmentsIntersect(a1, a2, b1, b2)) {
                    throw new MeshValidationException(ErrorCode.SELF_INTERSECTING_BOUNDARY,
                            name + " edges " + i + " and " + j + " intersect");
                }
            }
        }
        // Degenerate (zero-area) loop.
        if (Math.abs(GeometryUtils.polygonSignedArea(loop)) < GeometryUtils.EPS) {
            throw new MeshValidationException(ErrorCode.DEGENERATE_GEOMETRY,
                    name + " loop has (near) zero area");
        }
    }

    private void checkCrossDuplicates(List<Point2D> a, List<Point2D> b, String nameA, String nameB) {
        for (int i = 0; i < a.size(); i++) {
            for (int j = 0; j < b.size(); j++) {
                if (a.get(i).distanceTo(b.get(j)) < GeometryUtils.EPS) {
                    throw new MeshValidationException(ErrorCode.DUPLICATE_POINT,
                            nameA + " and " + nameB + " share a point");
                }
            }
        }
    }

    private boolean loopsIntersect(List<Point2D> a, List<Point2D> b) {
        int na = a.size();
        int nb = b.size();
        for (int i = 0; i < na; i++) {
            for (int j = 0; j < nb; j++) {
                if (GeometryUtils.segmentsIntersect(a.get(i), a.get((i + 1) % na),
                        b.get(j), b.get((j + 1) % nb))) {
                    return true;
                }
            }
        }
        return false;
    }
}
