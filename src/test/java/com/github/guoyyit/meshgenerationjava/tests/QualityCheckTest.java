package com.github.guoyyit.meshgenerationjava.tests;

import com.github.guoyyit.meshgenerationjava.core.Mesh;
import com.github.guoyyit.meshgenerationjava.quality.MeshQualityChecker;
import com.github.guoyyit.meshgenerationjava.quality.Metric;
import com.github.guoyyit.meshgenerationjava.quality.QualityIssue;
import com.github.guoyyit.meshgenerationjava.quality.QualityThresholds;
import com.github.guoyyit.meshgenerationjava.validation.MeshValidationException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Low-quality elements must be detected with location info; thresholds really apply. */
class QualityCheckTest {

    private static Mesh twoTriangleMesh() {
        // Square (0,0),(1,0),(1,1),(0,1) split into one good and one sliver triangle.
        Mesh mesh = new Mesh();
        mesh.addNode(0, 0);       // 0
        mesh.addNode(1, 0);       // 1
        mesh.addNode(1, 1);       // 2
        mesh.addNode(0.5, 0.001); // 3: sliver apex near edge 0-1
        mesh.addElement(0, 1, 2); // good right triangle
        mesh.addElement(0, 1, 3); // sliver
        mesh.addBoundaryEdge(0, 1, 0);
        mesh.addBoundaryEdge(1, 2, 0);
        mesh.addBoundaryEdge(2, 0, 0);
        mesh.addBoundaryEdge(0, 3, 0);
        mesh.addBoundaryEdge(3, 1, 0);
        return mesh;
    }

    @Test
    void sliverIsFlaggedWithLocation() {
        Mesh mesh = twoTriangleMesh();
        QualityThresholds thresholds = new QualityThresholds();
        thresholds.setMinAngleDeg(20.0);
        thresholds.setMinArea(0.01);
        List<QualityIssue> issues = new MeshQualityChecker().check(mesh, thresholds);

        assertTrue(issues.stream().anyMatch(i -> i.elementId() == 1 && i.metric() == Metric.MIN_ANGLE),
                "sliver min angle must be flagged: " + issues);
        assertTrue(issues.stream().anyMatch(i -> i.elementId() == 1 && i.metric() == Metric.MIN_AREA),
                "sliver area must be flagged: " + issues);
        // Location info: centroid of the sliver.
        QualityIssue angle = issues.stream().filter(i -> i.metric() == Metric.MIN_ANGLE).findFirst().orElseThrow();
        assertEquals(0.5, angle.centroidX(), 1e-9);
        assertEquals(1.0 / 3000.0, angle.centroidY(), 1e-6);
        assertTrue(angle.value() < angle.threshold());
        // The good triangle must not be flagged for angle.
        assertTrue(issues.stream().noneMatch(i -> i.elementId() == 0 && i.metric() == Metric.MIN_ANGLE));
    }

    @Test
    void edgeLengthThresholdApplies() {
        Mesh mesh = new Mesh();
        mesh.addNode(0, 0);
        mesh.addNode(5, 0);
        mesh.addNode(0, 1);
        mesh.addElement(0, 1, 2);
        mesh.addBoundaryEdge(0, 1, 0);
        mesh.addBoundaryEdge(1, 2, 0);
        mesh.addBoundaryEdge(2, 0, 0);

        QualityThresholds thresholds = new QualityThresholds();
        thresholds.setMinAngleDeg(1.0);
        thresholds.setMaxEdgeLength(2.0);
        List<QualityIssue> issues = new MeshQualityChecker().check(mesh, thresholds);
        assertEquals(1, issues.stream().filter(i -> i.metric() == Metric.MAX_EDGE_LENGTH).count());

        thresholds.setMaxEdgeLength(6.0);
        assertTrue(new MeshQualityChecker().check(mesh, thresholds).stream()
                .noneMatch(i -> i.metric() == Metric.MAX_EDGE_LENGTH));
    }

    @Test
    void valueExactlyAtThresholdIsNotViolation() {
        // Right triangle with legs 1: min angle = 45 deg, longest edge = sqrt(2).
        Mesh mesh = new Mesh();
        mesh.addNode(0, 0);
        mesh.addNode(1, 0);
        mesh.addNode(0, 1);
        mesh.addElement(0, 1, 2);
        mesh.addBoundaryEdge(0, 1, 0);
        mesh.addBoundaryEdge(1, 2, 0);
        mesh.addBoundaryEdge(2, 0, 0);

        QualityThresholds thresholds = new QualityThresholds();
        thresholds.setMinAngleDeg(45.0);
        thresholds.setMaxEdgeLength(Math.sqrt(2.0));
        thresholds.setMinArea(0.5);
        List<QualityIssue> issues = new MeshQualityChecker().check(mesh, thresholds);
        assertTrue(issues.isEmpty(), "boundary values must pass: " + issues);
    }

    @Test
    void illegalThresholdsRejected() {
        QualityThresholds thresholds = new QualityThresholds();
        thresholds.setMinAngleDeg(0.0);
        assertThrows(MeshValidationException.class,
                () -> new MeshQualityChecker().check(twoTriangleMesh(), thresholds));
        thresholds.setMinAngleDeg(75.0); // above the 60 deg ceiling
        assertThrows(MeshValidationException.class,
                () -> new MeshQualityChecker().check(twoTriangleMesh(), thresholds));
    }
}
