package com.github.guoyyit.meshgenerationjava.mesh.geometry;

import java.util.List;

/**
 * 平面几何谓词与度量。所有容差集中在此类，便于统一审计。
 */
public final class GeometryUtils {

    /** 方向判定的相对容差。 */
    public static final double ORIENTATION_EPS = 1e-12;

    /** 多边形最小面积（绝对值）。 */
    public static final double MIN_POLYGON_AREA = 1e-12;

    /** 单元最小面积（绝对值）。 */
    public static final double MIN_ELEMENT_AREA = 1e-14;

    private GeometryUtils() {
    }

    /** 有向面积的两倍：>0 表示 a->b->c 逆时针。 */
    public static double orient(Point2 a, Point2 b, Point2 c) {
        return (b.x() - a.x()) * (c.y() - a.y()) - (b.y() - a.y()) * (c.x() - a.x());
    }

    /** 带容差的方向符号：1=CCW，-1=CW，0=近似共线。 */
    public static int orientSign(Point2 a, Point2 b, Point2 c) {
        double v = orient(a, b, c);
        double scale = Math.max(1.0,
                Math.max(a.distanceTo(b), Math.max(b.distanceTo(c), a.distanceTo(c))));
        double tol = ORIENTATION_EPS * scale * scale;
        if (v > tol) {
            return 1;
        }
        if (v < -tol) {
            return -1;
        }
        return 0;
    }

    /** 多边形有向面积（正=CCW）。 */
    public static double signedArea(List<Point2> polygon) {
        double sum = 0.0;
        int n = polygon.size();
        for (int i = 0; i < n; i++) {
            Point2 p = polygon.get(i);
            Point2 q = polygon.get((i + 1) % n);
            sum += p.x() * q.y() - q.x() * p.y();
        }
        return sum * 0.5;
    }

    /**
     * 两开线段是否严格相交（不含端点接触）。
     */
    public static boolean segmentsCrossProperly(Point2 a, Point2 b, Point2 c, Point2 d) {
        int o1 = orientSign(a, b, c);
        int o2 = orientSign(a, b, d);
        int o3 = orientSign(c, d, a);
        int o4 = orientSign(c, d, b);
        return o1 != 0 && o2 != 0 && o3 != 0 && o4 != 0
                && o1 != o2 && o3 != o4;
    }

    /**
     * 两线段是否有任何公共点（含端点接触与共线重叠）。
     */
    public static boolean segmentsTouch(Point2 a, Point2 b, Point2 c, Point2 d) {
        if (segmentsCrossProperly(a, b, c, d)) {
            return true;
        }
        return onSegment(a, b, c) || onSegment(a, b, d) || onSegment(c, d, a) || onSegment(c, d, b);
    }

    /** p 是否位于线段 ab 上（含端点，带容差）。 */
    public static boolean onSegment(Point2 a, Point2 b, Point2 p) {
        if (orientSign(a, b, p) != 0) {
            return false;
        }
        double dot = (p.x() - a.x()) * (p.x() - b.x()) + (p.y() - a.y()) * (p.y() - b.y());
        double len2 = Math.max(a.distanceTo(b) * a.distanceTo(b), 1e-30);
        return dot <= ORIENTATION_EPS * len2;
    }

    /** 射线法点-in-多边形：1 内，0 边界上，-1 外。 */
    public static int pointInPolygon(Point2 p, List<Point2> polygon) {
        int n = polygon.size();
        boolean inside = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            Point2 pi = polygon.get(i);
            Point2 pj = polygon.get(j);
            if (onSegment(pj, pi, p)) {
                return 0;
            }
            boolean crosses = (pi.y() > p.y()) != (pj.y() > p.y());
            if (crosses) {
                double xInt = pj.x() + (p.y() - pj.y()) * (pi.x() - pj.x()) / (pi.y() - pj.y());
                if (xInt > p.x()) {
                    inside = !inside;
                }
            }
        }
        return inside ? 1 : -1;
    }

    /** 三角形面积（绝对值）。 */
    public static double triangleArea(Point2 a, Point2 b, Point2 c) {
        return Math.abs(orient(a, b, c)) * 0.5;
    }

    /** 三角形内角（度），返回长度为 3 的数组，对应顶点 a、b、c。 */
    public static double[] triangleAnglesDeg(Point2 a, Point2 b, Point2 c) {
        return new double[]{angleAt(b, a, c), angleAt(a, b, c), angleAt(a, c, b)};
    }

    /** 三角形外接圆圆心；退化（共线）时返回 null。 */
    public static Point2 circumcenter(Point2 a, Point2 b, Point2 c) {
        double d = 2.0 * (a.x() * (b.y() - c.y()) + b.x() * (c.y() - a.y())
                + c.x() * (a.y() - b.y()));
        double scale = Math.max(1.0,
                Math.max(a.distanceTo(b), Math.max(b.distanceTo(c), a.distanceTo(c))));
        if (Math.abs(d) < ORIENTATION_EPS * scale * scale) {
            return null;
        }
        double a2 = a.x() * a.x() + a.y() * a.y();
        double b2 = b.x() * b.x() + b.y() * b.y();
        double c2 = c.x() * c.x() + c.y() * c.y();
        double ux = (a2 * (b.y() - c.y()) + b2 * (c.y() - a.y()) + c2 * (a.y() - b.y())) / d;
        double uy = (a2 * (c.x() - b.x()) + b2 * (a.x() - c.x()) + c2 * (b.x() - a.x())) / d;
        if (!Double.isFinite(ux) || !Double.isFinite(uy)) {
            return null;
        }
        return new Point2(ux, uy);
    }

    /** p 是否严格位于 CCW 三角形 (a,b,c) 的外接圆内（边上/圆外返回 false）。 */
    public static boolean insideCircumcircle(Point2 a, Point2 b, Point2 c, Point2 p) {
        double ax = a.x() - p.x();
        double ay = a.y() - p.y();
        double bx = b.x() - p.x();
        double by = b.y() - p.y();
        double cx = c.x() - p.x();
        double cy = c.y() - p.y();
        double det = (ax * ax + ay * ay) * (bx * cy - cx * by)
                - (bx * bx + by * by) * (ax * cy - cx * ay)
                + (cx * cx + cy * cy) * (ax * by - bx * ay);
        double scale = Math.max(1.0,
                Math.max(a.distanceTo(b), Math.max(b.distanceTo(c), a.distanceTo(c))));
        return det > ORIENTATION_EPS * scale * scale * scale * scale;
    }

    /** p 是否严格位于以 uv 为直径的圆内（即角 upv 为钝角）。 */
    public static boolean insideDiametralCircle(Point2 u, Point2 v, Point2 p) {
        double dot = (u.x() - p.x()) * (v.x() - p.x()) + (u.y() - p.y()) * (v.y() - p.y());
        double len2 = Math.max(u.distanceTo(v) * u.distanceTo(v), 1e-30);
        return dot < -ORIENTATION_EPS * len2;
    }

    private static double angleAt(Point2 u, Point2 vertex, Point2 w) {
        Point2 v1 = u.minus(vertex);
        Point2 v2 = w.minus(vertex);
        double denom = v1.norm() * v2.norm();
        if (denom <= 0.0) {
            return 0.0;
        }
        double cos = v1.dot(v2) / denom;
        cos = Math.max(-1.0, Math.min(1.0, cos));
        return Math.toDegrees(Math.acos(cos));
    }
}
