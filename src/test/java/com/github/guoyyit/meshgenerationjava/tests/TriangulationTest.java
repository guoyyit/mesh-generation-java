package com.github.guoyyit.meshgenerationjava.tests;

import com.github.guoyyit.meshgenerationjava.mesh.model.Mesh;
import com.github.guoyyit.meshgenerationjava.mesh.model.MeshSnapshot;
import com.github.guoyyit.meshgenerationjava.mesh.model.PlanarRegion;
import com.github.guoyyit.meshgenerationjava.mesh.triangulate.EarClippingTriangulator;
import com.github.guoyyit.meshgenerationjava.mesh.validate.RegionValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TriangulationTest {

    private static Mesh triangulate(PlanarRegion region, long seed) {
        return EarClippingTriangulator.triangulate(RegionValidator.validate(region), seed);
    }

    @Test
    void squareProducesTwoTriangles() {
        Mesh mesh = triangulate(MeshTestSupport.square(2.0), 0L);
        assertEquals(2, mesh.elements().size());
        MeshTestSupport.assertConsistent(mesh);
        MeshTestSupport.assertAreaConserved(mesh, MeshTestSupport.square(2.0));
        MeshTestSupport.assertBoundaryPreserved(mesh, MeshTestSupport.square(2.0));
        MeshTestSupport.assertAllCounterClockwise(mesh);
    }

    @Test
    void squareWithHoleKeepsBoundaryExact() {
        PlanarRegion region = MeshTestSupport.squareWithHole(4.0, 2.0);
        Mesh mesh = triangulate(region, 42L);
        assertFalse(mesh.elements().isEmpty());
        MeshTestSupport.assertConsistent(mesh);
        MeshTestSupport.assertAreaConserved(mesh, region);
        MeshTestSupport.assertBoundaryPreserved(mesh, region);
        MeshTestSupport.assertAllCounterClockwise(mesh);
        // 外边界 4 条 + 孔 4 条边界边
        assertEquals(8, mesh.boundaryEdges().size());
    }

    @Test
    void concaveLShapeTriangulates() {
        PlanarRegion region = MeshTestSupport.lShape();
        Mesh mesh = triangulate(region, 7L);
        MeshTestSupport.assertConsistent(mesh);
        MeshTestSupport.assertAreaConserved(mesh, region);
        MeshTestSupport.assertBoundaryPreserved(mesh, region);
    }

    @Test
    void sameSeedSameResult() {
        PlanarRegion region = MeshTestSupport.squareWithHole(4.0, 2.0);
        MeshSnapshot a = triangulate(region, 123L).snapshot();
        MeshSnapshot b = triangulate(region, 123L).snapshot();
        assertEquals(a, b, "same seed must produce identical mesh");
    }

    @Test
    void differentSeedsStillValid() {
        PlanarRegion region = MeshTestSupport.squareWithHole(4.0, 2.0);
        for (long seed = 0; seed < 8; seed++) {
            Mesh mesh = triangulate(region, seed);
            MeshTestSupport.assertConsistent(mesh);
            MeshTestSupport.assertAreaConserved(mesh, region);
            MeshTestSupport.assertBoundaryPreserved(mesh, region);
        }
    }

    @Test
    void collinearBoundaryPointIsKept() {
        // 底边中点多一个共线点：边界点必须全部成为网格节点且边界边被保持
        PlanarRegion region = new PlanarRegion(java.util.List.of(
                MeshTestSupport.p(0, 0), MeshTestSupport.p(1, 0), MeshTestSupport.p(2, 0),
                MeshTestSupport.p(2, 1), MeshTestSupport.p(0, 1)), java.util.List.of());
        Mesh mesh = triangulate(region, 0L);
        MeshTestSupport.assertConsistent(mesh);
        MeshTestSupport.assertAreaConserved(mesh, region);
        MeshTestSupport.assertBoundaryPreserved(mesh, region);
        assertEquals(5, mesh.boundaryEdges().size());
        assertTrue(mesh.nodes().values().stream().anyMatch(
                n -> n.x() == 1.0 && n.y() == 0.0), "collinear boundary point must be a node");
    }
}
