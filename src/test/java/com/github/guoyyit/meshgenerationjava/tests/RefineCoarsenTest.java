package com.github.guoyyit.meshgenerationjava.tests;

import com.github.guoyyit.meshgenerationjava.mesh.model.Mesh;
import com.github.guoyyit.meshgenerationjava.mesh.model.MeshElement;
import com.github.guoyyit.meshgenerationjava.mesh.model.PlanarRegion;
import com.github.guoyyit.meshgenerationjava.mesh.quality.QualityThresholds;
import com.github.guoyyit.meshgenerationjava.mesh.refine.MeshCoarsener;
import com.github.guoyyit.meshgenerationjava.mesh.refine.MeshRefiner;
import com.github.guoyyit.meshgenerationjava.mesh.triangulate.EarClippingTriangulator;
import com.github.guoyyit.meshgenerationjava.mesh.validate.RegionValidator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefineCoarsenTest {

    private static Mesh meshOf(PlanarRegion region, long seed) {
        return EarClippingTriangulator.triangulate(RegionValidator.validate(region), seed);
    }

    @Test
    void refineSingleElementKeepsConformity() {
        PlanarRegion region = MeshTestSupport.squareWithHole(4.0, 2.0);
        Mesh mesh = meshOf(region, 0L);
        int before = mesh.elements().size();
        long target = mesh.elements().keySet().iterator().next();

        int splits = MeshRefiner.refineElements(mesh, List.of(target), 1);
        assertTrue(splits >= 1);
        assertTrue(mesh.elements().size() > before);

        MeshTestSupport.assertConsistent(mesh);
        MeshTestSupport.assertAreaConserved(mesh, region);
        MeshTestSupport.assertBoundaryPreserved(mesh, region);
        MeshTestSupport.assertAllCounterClockwise(mesh);
    }

    @Test
    void refineAllElementsMultipleLevels() {
        PlanarRegion region = MeshTestSupport.square(2.0);
        Mesh mesh = meshOf(region, 1L);
        MeshRefiner.refineElements(mesh, List.copyOf(mesh.elements().keySet()), 3);
        MeshTestSupport.assertConsistent(mesh);
        MeshTestSupport.assertAreaConserved(mesh, region);
        MeshTestSupport.assertBoundaryPreserved(mesh, region);
        MeshTestSupport.assertAllCounterClockwise(mesh);
        assertTrue(mesh.elements().size() >= 16, "3 levels should grow the mesh substantially");
    }

    @Test
    void boundarySplitKeepsBoundaryGeometry() {
        PlanarRegion region = MeshTestSupport.square(2.0);
        Mesh mesh = meshOf(region, 0L);
        // 细分所有单元若干轮，强制边界边被二分
        MeshRefiner.refineElements(mesh, List.copyOf(mesh.elements().keySet()), 4);
        MeshTestSupport.assertBoundaryPreserved(mesh, region);
        MeshTestSupport.assertConsistent(mesh);
        // 边界边数量增加但周长不变
        assertTrue(mesh.boundaryEdges().size() > 4);
    }

    @Test
    void coarsenFullyRevertsRefinement() {
        PlanarRegion region = MeshTestSupport.square(2.0);
        Mesh mesh = meshOf(region, 0L);
        int initialElements = mesh.elements().size();
        int initialNodes = mesh.nodes().size();

        MeshRefiner.refineElements(mesh, List.copyOf(mesh.elements().keySet()), 2);
        MeshTestSupport.assertConsistent(mesh);

        int reverted = MeshCoarsener.coarsen(mesh, null, QualityThresholds.none());
        assertTrue(reverted > 0);
        assertEquals(initialElements, mesh.elements().size(), "full coarsen restores element count");
        assertEquals(initialNodes, mesh.nodes().size(), "full coarsen restores node count");
        MeshTestSupport.assertConsistent(mesh);
        MeshTestSupport.assertBoundaryPreserved(mesh, region);
    }

    @Test
    void coarsenRespectsQualityGuard() {
        PlanarRegion region = MeshTestSupport.square(2.0);
        Mesh mesh = meshOf(region, 0L);
        MeshRefiner.refineElements(mesh, List.copyOf(mesh.elements().keySet()), 2);
        int refinedCount = mesh.elements().size();

        // 极严的阈值：任何回滚都会违规 -> 一个事件也不允许回滚
        QualityThresholds strict = new QualityThresholds(59.0, null, null, null);
        int reverted = MeshCoarsener.coarsen(mesh, null, strict);
        assertEquals(0, reverted, "quality guard must block unsafe coarsening");
        assertEquals(refinedCount, mesh.elements().size());
        MeshTestSupport.assertConsistent(mesh);
    }

    @Test
    void coarsenOnlyRequestedRegion() {
        PlanarRegion region = MeshTestSupport.square(2.0);
        Mesh mesh = meshOf(region, 0L);
        List<Long> initial = List.copyOf(mesh.elements().keySet());
        MeshRefiner.refineElements(mesh, initial, 1);
        int refinedCount = mesh.elements().size();

        // 只粗化第一个初始单元的后代
        java.util.Set<Long> targets = new java.util.TreeSet<>();
        for (MeshElement e : mesh.elements().values()) {
            targets.add(e.id());
        }
        // 找到由 initial[0] 细分产生的子单元
        long parent = initial.get(0);
        java.util.Set<Long> children = new java.util.TreeSet<>();
        for (var event : mesh.splitEvents()) {
            boolean removesParent = event.removedElements().stream().anyMatch(e -> e.id() == parent);
            if (removesParent) {
                event.addedElements().forEach(e -> children.add(e.id()));
            }
        }
        int reverted = MeshCoarsener.coarsen(mesh, children, QualityThresholds.none());
        assertTrue(reverted >= 1);
        assertTrue(mesh.elements().size() < refinedCount);
        assertTrue(mesh.hasElement(parent), "parent element must be restored");
        MeshTestSupport.assertConsistent(mesh);
        MeshTestSupport.assertBoundaryPreserved(mesh, region);
    }

    @Test
    void refineCoarsenCycleStaysDeterministic() {
        PlanarRegion region = MeshTestSupport.squareWithHole(4.0, 2.0);
        Mesh a = meshOf(region, 5L);
        Mesh b = meshOf(region, 5L);
        List<Long> ids = List.copyOf(a.elements().keySet());
        MeshRefiner.refineElements(a, ids, 2);
        MeshRefiner.refineElements(b, ids, 2);
        assertEquals(a.snapshot(), b.snapshot());
        MeshCoarsener.coarsen(a, null, QualityThresholds.none());
        MeshCoarsener.coarsen(b, null, QualityThresholds.none());
        assertEquals(a.snapshot(), b.snapshot());
    }
}
