package com.github.guoyyit.meshgenerationjava.geometry;

import java.util.List;

/** Deterministic geometric predicates and measurements. */
public final class GeometryUtils {

    /** Tolerance for geometric predicates. */
    public static final double EPS = 1e-12;

    private GeometryUtils() {
    }

    /** Twice the signed area of triangle (a,b,c); positive when CCW. */
    public static double orient(Point2D a, Point2D b, Point2D c) {
        return (b.x() - a.x()) * (c.y() - a.y()) - (b.y() - a.y()) * (c.x() - a.x());
    }

    public static double triangleArea(Point2D a, Point2D b, Point2D c) {
        return Math.abs(orient(a, b, c)) * 0.5;
    }

    public static double polygonSignedArea(List<Point2D> polygon) {
        double sum = 0.0;
        int n = polygon.size();
        for (int i = 0; i < n; i++) {
            Point2D p = polygon.get(i);
            Point2D q = polygon.get((i + 1) % n);
            sum += p.x() * q.y() - q.x() * p.y();
        }
        return sum * 0.5;
    }

    /** True when segments p1p2 and q1q2 intersect (touching counts). */
    public static boolean segmentsIntersect(Point2D p1, Point2D p2, Point2D q1, Point2D q2) {
        double d1 = orient(q1, q2, p1);
        double d2 = orient(q1, q2, p2);
        double d3 = orient(p1, p2, q1);
        double d4 = orient(p1, p2, q2);
        if (((d1 > EPS && d2 < -EPS) || (d1 < -EPS && d2 > EPS))
                && ((d3 > EPS && d4 < -EPS) || (d3 < -EPS && d4 > EPS))) {
            return true;
        }
        return (Math.abs(d1) <= EPS && pointOnSegment(p1, q1, q2))
                || (Math.abs(d2) <= EPS && pointOnSegment(p2, q1, q2))
                || (Math.abs(d3) <= EPS && pointOnSegment(q1, p1, p2))
                || (Math.abs(d4) <= EPS && pointOnSegment(q2, p1, p2));
    }

    public static boolean pointOnSegment(Point2D p, Point2D a, Point2D b) {
        if (Math.abs(orient(a, b, p)) > EPS) {
            return false;
        }
        double dot = (p.x() - a.x()) * (b.x() - a.x()) + (p.y() - a.y()) * (b.y() - a.y());
        if (dot < -EPS) {
            return false;
        }
        double lenSq = (b.x() - a.x()) * (b.x() - a.x()) + (b.y() - a.y()) * (b.y() - a.y());
        return dot <= lenSq + EPS;
    }

    /** Strictly-inside ray-casting test; points on the boundary return false. */
    public static boolean pointInPolygon(Point2D p, List<Point2D> polygon) {
        if (pointOnPolygonBoundary(p, polygon)) {
            return false;
        }
        boolean inside = false;
        int n = polygon.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            Point2D a = polygon.get(i);
            Point2D b = polygon.get(j);
            if ((a.y() > p.y()) != (b.y() > p.y())
                    && p.x() < (b.x() - a.x()) * (p.y() - a.y()) / (b.y() - a.y()) + a.x()) {
                inside = !inside;
            }
        }
        return inside;
    }

    public static boolean pointOnPolygonBoundary(Point2D p, List<Point2D> polygon) {
        int n = polygon.size();
        for (int i = 0; i < n; i++) {
            if (pointOnSegment(p, polygon.get(i), polygon.get((i + 1) % n))) {
                return true;
            }
        }
        return false;
    }

    /** Smallest interior angle of triangle (a,b,c) in degrees. */
    public static double minAngleDeg(Point2D a, Point2D b, Point2D c) {
        double la = b.distanceTo(c);
        double lb = a.distanceTo(c);
        double lc = a.distanceTo(b);
        double min = Double.POSITIVE_INFINITY;
        min = Math.min(min, angleDeg(lb, lc, la)); // at a
        min = Math.min(min, angleDeg(la, lc, lb)); // at b
        min = Math.min(min, angleDeg(la, lb, lc)); // at c
        return min;
    }

    /** Angle (degrees) opposite side `opposite`, between sides `adj1` and `adj2`. */
    private static double angleDeg(double adj1, double adj2, double opposite) {
        if (adj1 <= EPS || adj2 <= EPS) {
            return 0.0;
        }
        double cos = (adj1 * adj1 + adj2 * adj2 - opposite * opposite) / (2.0 * adj1 * adj2);
        cos = Math.max(-1.0, Math.min(1.0, cos));
        return Math.toDegrees(Math.acos(cos));
    }

    public static double maxEdgeLength(Point2D a, Point2D b, Point2D c) {
        return Math.max(a.distanceTo(b), Math.max(b.distanceTo(c), c.distanceTo(a)));
    }
}
