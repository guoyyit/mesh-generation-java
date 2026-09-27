package com.github.guoyyit.meshgenerationjava.mesh.quality;

import com.github.guoyyit.meshgenerationjava.mesh.geometry.GeometryUtils;
import com.github.guoyyit.meshgenerationjava.mesh.geometry.Point2;
import com.github.guoyyit.meshgenerationjava.mesh.model.Mesh;
import com.github.guoyyit.meshgenerationjava.mesh.model.MeshElement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 按边长、角度、面积阈值检测低质量单元，并给出单元 id 与形心定位。
 */
public final class QualityChecker {

    private QualityChecker() {
    }

    public static ElementQuality measure(Mesh mesh, MeshElement e) {
        Point2 a = mesh.point(e.n0());
        Point2 b = mesh.point(e.n1());
        Point2 c = mesh.point(e.n2());
        double ab = a.distanceTo(b);
        double bc = b.distanceTo(c);
        double ca = c.distanceTo(a);
        double area = GeometryUtils.triangleArea(a, b, c);
        double[] angles = GeometryUtils.triangleAnglesDeg(a, b, c);
        double minAngle = Math.min(angles[0], Math.min(angles[1], angles[2]));
        double maxEdge = Math.max(ab, Math.max(bc, ca));
        double perimeter = ab + bc + ca;
        double radiusRatio = 0.0;
        if (maxEdge > 0.0 && area > 0.0) {
            double rIn = 2.0 * area / perimeter;
            double rCirc = ab * bc * ca / (4.0 * area);
            radiusRatio = rCirc > 0.0 ? 2.0 * rIn / rCirc : 0.0;
        }
        return new ElementQuality(
                e.id(), area,
                new double[]{ab, bc, ca}, angles,
                minAngle, maxEdge, radiusRatio,
                (a.x() + b.x() + c.x()) / 3.0, (a.y() + b.y() + c.y()) / 3.0);
    }

    /** 单单元越界检查（按 metric 名稳定排序返回）。 */
    public static List<QualityViolation> check(ElementQuality q, QualityThresholds t) {
        List<QualityViolation> out = new ArrayList<>();
        if (t.minAngleDeg() != null && q.minAngleDeg() < t.minAngleDeg()) {
            out.add(new QualityViolation(q.elementId(), "minAngle",
                    q.minAngleDeg(), t.minAngleDeg(), q.centroidX(), q.centroidY()));
        }
        if (t.maxEdgeLength() != null && q.maxEdgeLength() > t.maxEdgeLength()) {
            out.add(new QualityViolation(q.elementId(), "maxEdgeLength",
                    q.maxEdgeLength(), t.maxEdgeLength(), q.centroidX(), q.centroidY()));
        }
        if (t.minArea() != null && q.area() < t.minArea()) {
            out.add(new QualityViolation(q.elementId(), "minArea",
                    q.area(), t.minArea(), q.centroidX(), q.centroidY()));
        }
        if (t.maxArea() != null && q.area() > t.maxArea()) {
            out.add(new QualityViolation(q.elementId(), "maxArea",
                    q.area(), t.maxArea(), q.centroidX(), q.centroidY()));
        }
        return out;
    }

    /** 全网评估：越界列表按 (elementId, metric) 排序，保证确定性。 */
    public static QualityReport evaluate(Mesh mesh, QualityThresholds thresholds) {
        List<QualityViolation> violations = new ArrayList<>();
        double minAngle = Double.POSITIVE_INFINITY;
        double maxEdge = 0.0;
        double minArea = Double.POSITIVE_INFINITY;
        double maxArea = 0.0;
        double minRatio = Double.POSITIVE_INFINITY;
        for (MeshElement e : mesh.elements().values()) {
            ElementQuality q = measure(mesh, e);
            minAngle = Math.min(minAngle, q.minAngleDeg());
            maxEdge = Math.max(maxEdge, q.maxEdgeLength());
            minArea = Math.min(minArea, q.area());
            maxArea = Math.max(maxArea, q.area());
            minRatio = Math.min(minRatio, q.radiusRatio());
            violations.addAll(check(q, thresholds));
        }
        violations.sort(Comparator.comparingLong(QualityViolation::elementId)
                .thenComparing(QualityViolation::metric));
        if (mesh.elements().isEmpty()) {
            minAngle = 0.0;
            minArea = 0.0;
            minRatio = 0.0;
        }
        return new QualityReport(mesh.elements().size(),
                minAngle, maxEdge, minArea, maxArea, minRatio, List.copyOf(violations));
    }
}
