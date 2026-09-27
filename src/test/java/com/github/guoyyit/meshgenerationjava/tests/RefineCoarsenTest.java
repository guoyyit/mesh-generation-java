package com.github.guoyyit.meshgenerationjava.tests;

import com.github.guoyyit.meshgenerationjava.core.BoundaryEdge;
import com.github.guoyyit.meshgenerationjava.core.Mesh;
import com.github.guoyyit.meshgenerationjava.generator.MeshGenerationOptions;
import com.github.guoyyit.meshgenerationjava.generator.MeshGenerator;
import com.github.guoyyit.meshgenerationjava.geometry.Point2D;
import com.github.guoyyit.meshgenerationjava.geometry.Region;
import com.github.guoyyit.meshgenerationjava.quality.MeshQualityChecker;
import com.github.guoyyit.meshgenerationjava.quality.Metric;
import com.github.guoyyit.meshgenerationjava.quality.QualityThresholds;
import com.github.guoyyit.meshgenerationjava.refine.MeshCoarsener;
import com.github.guoyyit.meshgenerationjava.validation.MeshValidationException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Refinement and coarsening must keep conformity, orientation and the exact boundary. */
class RefineCoarsenTest {

    private static Region square(double size) {
        return new Region(List.of(
                new Point2D(0, 0), new Point2D(size, 0),
                new Point2D(size, size), new Point2D(0, size)), List.of());
    }

    private static MeshGenerationOptions options(double maxEdge) {
        MeshGenerationOptions options = new MeshGenerationOptions();
        options.setSeed(11L);
        options.setMaxEdgeLength(maxEdge);
        options.setMinAngleDeg(3.0);
        return options;
    }

    @Test
    void refinementEnforcesMaxEdgeLengthWithoutHangingNodes() {
        Mesh mesh = new MeshGenerator().generate(square(2.0), options(0.4)).mesh();
        assertTrue(mesh.checkConsistency().isEmpty(),
                "hanging edges after refinement: " + mesh.checkConsistency());

        QualityThresholds thresholds = new QualityThresholds();
        thresholds.setMinAngleDeg(0.01); // isolate the edge-length metric
        thresholds.setMaxEdgeLength(0.4);
        List<com.github.guoyyit.meshgenerationjava.quality.QualityIssue> edgeIssues =
                new MeshQualityChecker().check(mesh, thresholds).stream()
                        .filter(i -> i.metric() == Metric.MAX_EDGE_LENGTH).toList();
        assertTrue(edgeIssues.isEmpty(), "edge length violations remain: " + edgeIssues);
        assertEquals(4.0, mesh.totalArea(), 1e-9);
    }

    @Test
    void coarseningReducesDensityAndKeepsBoundaryExact() {
        Region region = square(2.0);
        Mesh mesh = new MeshGenerator().generate(region, options(0.25)).mesh();
        int elementsBefore = mesh.elementCount();
        int boundaryEdgesBefore = mesh.boundaryEdges().size();
        double boundaryLengthBefore = boundaryLength(mesh);

        int collapsed = new MeshCoarsener().coarsen(mesh, 0.3, 10);

        assertTrue(collapsed > 0, "expected some collapses");
        assertTrue(mesh.elementCount() < elementsBefore, "element count must shrink");
        assertTrue(mesh.checkConsistency().isEmpty(),
                "hanging edges after coarsening: " + mesh.checkConsistency());
        // Boundary untouched: same edges, same total length, area conserved.
        assertEquals(boundaryEdgesBefore, mesh.boundaryEdges().size());
        assertEquals(boundaryLengthBefore, boundaryLength(mesh), 1e-9);
        assertEquals(4.0, mesh.totalArea(), 1e-9);
    }

    @Test
    void coarsenThenRefineStaysConsistent() {
        Mesh mesh = new MeshGenerator().generate(square(2.0), options(0.5)).mesh();
        new MeshCoarsener().coarsen(mesh, 0.4, 5);
        assertTrue(mesh.checkConsistency().isEmpty());
        // Refine again after coarsening: still conforming.
        QualityThresholds thresholds = new QualityThresholds();
        thresholds.setMinAngleDeg(3.0);
        thresholds.setMaxEdgeLength(0.3);
        new com.github.guoyyit.meshgenerationjava.refine.MeshRefiner()
                .refine(mesh, thresholds, 5000, com.github.guoyyit.meshgenerationjava.task.CancellationToken.none());
        assertTrue(mesh.checkConsistency().isEmpty());
        assertEquals(4.0, mesh.totalArea(), 1e-9);
    }

    @Test
    void starRegionWithHoleSurvivesRefineAndCoarsen() {
        // Strongly concave outer boundary plus a diamond hole.
        java.util.List<Point2D> outer = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            double angle = Math.PI / 2 + i * Math.PI / 5;
            double r = (i % 2 == 0) ? 5.0 : 2.2;
            outer.add(new Point2D(r * Math.cos(angle), r * Math.sin(angle)));
        }
        Region region = new Region(outer, List.of(List.of(
                new Point2D(0, -0.8), new Point2D(0.8, 0),
                new Point2D(0, 0.8), new Point2D(-0.8, 0))));
        Mesh mesh = new MeshGenerator().generate(region, options(0.6)).mesh();
        assertTrue(mesh.checkConsistency().isEmpty(),
                "problems: " + mesh.checkConsistency());
        assertEquals(region.area(), mesh.totalArea(), 1e-9);

        new MeshCoarsener().coarsen(mesh, 0.4, 10);
        assertTrue(mesh.checkConsistency().isEmpty(),
                "problems after coarsening: " + mesh.checkConsistency());
        assertEquals(region.area(), mesh.totalArea(), 1e-9);
        // Both boundary loops still fully covered by marked edges.
        long outerEdges = mesh.boundaryEdges().stream().filter(e -> e.marker() == 0).count();
        long holeEdges = mesh.boundaryEdges().stream().filter(e -> e.marker() == 1).count();
        assertTrue(outerEdges >= 10 && holeEdges >= 4, "boundary loops must stay covered");
    }

    @Test
    void coarsenRejectsIllegalParameters() {
        Mesh mesh = new MeshGenerator().generate(square(1.0), options(0.5)).mesh();
        assertThrows(MeshValidationException.class, () -> new MeshCoarsener().coarsen(mesh, 0.0, 1));
        assertThrows(MeshValidationException.class, () -> new MeshCoarsener().coarsen(mesh, 0.5, 0));
    }

    private static double boundaryLength(Mesh mesh) {
        double sum = 0.0;
        for (BoundaryEdge be : mesh.boundaryEdges()) {
            sum += mesh.point(be.a()).distanceTo(mesh.point(be.b()));
        }
        return sum;
    }
}
