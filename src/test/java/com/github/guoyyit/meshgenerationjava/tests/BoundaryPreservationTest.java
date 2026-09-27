package com.github.guoyyit.meshgenerationjava.tests;

import com.github.guoyyit.meshgenerationjava.core.BoundaryEdge;
import com.github.guoyyit.meshgenerationjava.core.Mesh;
import com.github.guoyyit.meshgenerationjava.generator.GenerationResult;
import com.github.guoyyit.meshgenerationjava.generator.MeshGenerationOptions;
import com.github.guoyyit.meshgenerationjava.generator.MeshGenerator;
import com.github.guoyyit.meshgenerationjava.geometry.GeometryUtils;
import com.github.guoyyit.meshgenerationjava.geometry.Point2D;
import com.github.guoyyit.meshgenerationjava.geometry.Region;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Boundary edges must exactly cover the input loops; mesh must be conforming. */
class BoundaryPreservationTest {

    private static Region squareWithHole() {
        List<Point2D> outer = List.of(
                new Point2D(0, 0), new Point2D(4, 0), new Point2D(4, 4), new Point2D(0, 4));
        List<Point2D> hole = List.of(
                new Point2D(1, 1), new Point2D(3, 1), new Point2D(3, 3), new Point2D(1, 3));
        return new Region(outer, List.of(hole));
    }

    @Test
    void generatedMeshPreservesBoundaryAndTopology() {
        Region region = squareWithHole();
        MeshGenerationOptions options = new MeshGenerationOptions();
        options.setSeed(7L);
        options.setMaxEdgeLength(0.8);
        options.setMinAngleDeg(5.0);
        GenerationResult result = new MeshGenerator().generate(region, options);
        Mesh mesh = result.mesh();

        // Topology: no hanging edges, all elements CCW, no duplicates.
        assertTrue(mesh.checkConsistency().isEmpty(),
                "consistency problems: " + mesh.checkConsistency());

        // Area conservation: no overlaps, no gaps.
        assertEquals(region.area(), mesh.totalArea(), 1e-9);

        // Every mesh boundary edge lies exactly on some input boundary segment.
        List<Point2D> outer = region.getOuter();
        List<Point2D> hole = region.getHoles().get(0);
        for (BoundaryEdge be : mesh.boundaryEdges()) {
            Point2D a = mesh.point(be.a());
            Point2D b = mesh.point(be.b());
            if (be.marker() == 0) {
                assertTrue(onLoop(a, outer) && onLoop(b, outer),
                        "outer edge not on boundary: " + be);
            } else {
                assertEquals(1, be.marker());
                assertTrue(onLoop(a, hole) && onLoop(b, hole),
                        "hole edge not on boundary: " + be);
            }
        }

        // Boundary coverage: total boundary edge length equals loop perimeters.
        double outerLen = 0.0;
        double holeLen = 0.0;
        for (BoundaryEdge be : mesh.boundaryEdges()) {
            double len = mesh.point(be.a()).distanceTo(mesh.point(be.b()));
            if (be.marker() == 0) {
                outerLen += len;
            } else {
                holeLen += len;
            }
        }
        assertEquals(16.0, outerLen, 1e-9);
        assertEquals(8.0, holeLen, 1e-9);
    }

    @Test
    void concaveRegionMeshesCorrectly() {
        // L-shaped region.
        Region region = new Region(List.of(
                new Point2D(0, 0), new Point2D(3, 0), new Point2D(3, 1),
                new Point2D(1, 1), new Point2D(1, 3), new Point2D(0, 3)), List.of());
        MeshGenerationOptions options = new MeshGenerationOptions();
        options.setSeed(1L);
        Mesh mesh = new MeshGenerator().generate(region, options).mesh();
        assertTrue(mesh.checkConsistency().isEmpty(), "problems: " + mesh.checkConsistency());
        assertEquals(region.area(), mesh.totalArea(), 1e-9);
    }

    private static boolean onLoop(Point2D p, List<Point2D> loop) {
        return GeometryUtils.pointOnPolygonBoundary(p, loop);
    }
}
