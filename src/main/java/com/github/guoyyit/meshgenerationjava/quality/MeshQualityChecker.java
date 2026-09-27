package com.github.guoyyit.meshgenerationjava.quality;

import com.github.guoyyit.meshgenerationjava.core.Element;
import com.github.guoyyit.meshgenerationjava.core.Mesh;
import com.github.guoyyit.meshgenerationjava.geometry.GeometryUtils;
import com.github.guoyyit.meshgenerationjava.geometry.Point2D;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Detects low-quality elements; every violation is reported, never silently dropped. */
public class MeshQualityChecker {

    /** Relative slack so values exactly at a threshold are not flagged by float noise. */
    private static final double TOLERANCE = 1e-9;

    /**
     * Returns one issue per (element, violated threshold), located at the element
     * centroid, sorted deterministically by element id then metric.
     */
    public List<QualityIssue> check(Mesh mesh, QualityThresholds thresholds) {
        thresholds.validate();
        List<QualityIssue> issues = new ArrayList<>();
        for (Element e : mesh.elements()) {
            Point2D a = mesh.point(e.getN0());
            Point2D b = mesh.point(e.getN1());
            Point2D c = mesh.point(e.getN2());
            double cx = (a.x() + b.x() + c.x()) / 3.0;
            double cy = (a.y() + b.y() + c.y()) / 3.0;

            double minAngle = GeometryUtils.minAngleDeg(a, b, c);
            if (minAngle < thresholds.getMinAngleDeg() * (1.0 - TOLERANCE)) {
                issues.add(new QualityIssue(e.getId(), Metric.MIN_ANGLE, minAngle,
                        thresholds.getMinAngleDeg(), cx, cy,
                        "element " + e.getId() + " min angle " + minAngle
                                + " < " + thresholds.getMinAngleDeg()));
            }
            double maxEdge = GeometryUtils.maxEdgeLength(a, b, c);
            if (maxEdge > thresholds.getMaxEdgeLength() * (1.0 + TOLERANCE)) {
                issues.add(new QualityIssue(e.getId(), Metric.MAX_EDGE_LENGTH, maxEdge,
                        thresholds.getMaxEdgeLength(), cx, cy,
                        "element " + e.getId() + " max edge " + maxEdge
                                + " > " + thresholds.getMaxEdgeLength()));
            }
            double area = GeometryUtils.triangleArea(a, b, c);
            if (area < thresholds.getMinArea() * (1.0 - TOLERANCE)) {
                issues.add(new QualityIssue(e.getId(), Metric.MIN_AREA, area,
                        thresholds.getMinArea(), cx, cy,
                        "element " + e.getId() + " area " + area
                                + " < " + thresholds.getMinArea()));
            }
        }
        issues.sort(Comparator.comparingInt(QualityIssue::elementId)
                .thenComparing(issue -> issue.metric().name()));
        return issues;
    }
}
