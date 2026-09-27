package com.github.guoyyit.meshgenerationjava.mesh.geometry;

/**
 * 二维点（不可变）。坐标比较一律使用精确相等，
 * 重复点判定由校验器负责，几何容差集中在 {@link GeometryUtils}。
 */
public record Point2(double x, double y) {

    public Point2 plus(Point2 o) {
        return new Point2(x + o.x, y + o.y);
    }

    public Point2 minus(Point2 o) {
        return new Point2(x - o.x, y - o.y);
    }

    public Point2 scale(double s) {
        return new Point2(x * s, y * s);
    }

    public Point2 midpoint(Point2 o) {
        return new Point2((x + o.x) * 0.5, (y + o.y) * 0.5);
    }

    public double dot(Point2 o) {
        return x * o.x + y * o.y;
    }

    /** z 分量（叉积）。 */
    public double cross(Point2 o) {
        return x * o.y - y * o.x;
    }

    public double distanceTo(Point2 o) {
        return Math.hypot(x - o.x, y - o.y);
    }

    public double norm() {
        return Math.hypot(x, y);
    }

    /** 坐标完全一致（按 double 位级比较）时视为同一点。 */
    public boolean sameCoordinates(Point2 o) {
        return Double.compare(x, o.x) == 0 && Double.compare(y, o.y) == 0;
    }
}
