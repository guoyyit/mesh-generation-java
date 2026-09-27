package com.github.guoyyit.meshgenerationjava.tests;

import com.github.guoyyit.meshgenerationjava.mesh.geometry.GeometryUtils;
import com.github.guoyyit.meshgenerationjava.mesh.geometry.Point2;
import com.github.guoyyit.meshgenerationjava.mesh.model.BoundaryEdge;
import com.github.guoyyit.meshgenerationjava.mesh.model.Mesh;
import com.github.guoyyit.meshgenerationjava.mesh.model.MeshElement;
import com.github.guoyyit.meshgenerationjava.mesh.model.MeshSnapshot;
import com.github.guoyyit.meshgenerationjava.mesh.model.PlanarRegion;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 测试共享：区域构造与网格不变量断言。
 */
final class MeshTestSupport {

    private MeshTestSupport() {
    }

    static Point2 p(double x, double y) {
        return new Point2(x, y);
    }

    static PlanarRegion square(double size) {
        return new PlanarRegion(List.of(
                p(0, 0), p(size, 0), p(size, size), p(0, size)), List.of());
    }

    static PlanarRegion squareWithHole(double size, double holeSize) {
        double c = (size - holeSize) / 2.0;
        return new PlanarRegion(List.of(
                p(0, 0), p(size, 0), p(size, size), p(0, size)),
                List.of(List.of(
                        p(c, c), p(c, c + holeSize), p(c + holeSize, c + holeSize), p(c + holeSize, c))));
    }

    static PlanarRegion lShape() {
        return new PlanarRegion(List.of(
                p(0, 0), p(4, 0), p(4, 2), p(2, 2), p(2, 4), p(0, 4)), List.of());
    }

    static double regionArea(PlanarRegion region) {
        double area = Math.abs(GeometryUtils.signedArea(region.outer()));
        for (List<Point2> hole : region.holes()) {
            area -= Math.abs(GeometryUtils.signedArea(hole));
        }
        return area;
    }

    /** 无悬挂节点/重叠单元/朝向错误/边界缺失。 */
    static void assertConsistent(Mesh mesh) {
        List<String> problems = mesh.findInconsistencies();
        assertTrue(problems.isEmpty(), () -> "mesh inconsistent: " + problems);
    }

    static void assertConsistent(MeshSnapshot snapshot) {
        assertConsistent(Mesh.fromSnapshot(snapshot));
    }

    /** 单元面积总和 == 区域面积（无重叠、无空洞的强校验）。 */
    static void assertAreaConserved(Mesh mesh, PlanarRegion region) {
        double sum = 0.0;
        for (MeshElement e : mesh.elements().values()) {
            sum += GeometryUtils.triangleArea(
                    mesh.point(e.n0()), mesh.point(e.n1()), mesh.point(e.n2()));
        }
        assertEquals(regionArea(region), sum, 1e-8, "element areas must sum to region area");
    }

    /** 每条边界边都精确落在某条原始边界线段上，且边界边总长 == 区域周长。 */
    static void assertBoundaryPreserved(Mesh mesh, PlanarRegion region) {
        List<List<Point2>> boundaries = new ArrayList<>();
        boundaries.add(region.outer());
        boundaries.addAll(region.holes());

        double perimeter = 0.0;
        for (List<Point2> b : boundaries) {
            for (int i = 0; i < b.size(); i++) {
                perimeter += b.get(i).distanceTo(b.get((i + 1) % b.size()));
            }
        }

        double edgeSum = 0.0;
        for (BoundaryEdge be : mesh.boundaryEdges()) {
            Point2 a = mesh.point(be.a());
            Point2 b = mesh.point(be.b());
            edgeSum += a.distanceTo(b);
            List<Point2> boundary = boundaries.get(be.boundaryId());
            boolean onBoundary = false;
            for (int i = 0; i < boundary.size(); i++) {
                Point2 s = boundary.get(i);
                Point2 t = boundary.get((i + 1) % boundary.size());
                if (GeometryUtils.onSegment(s, t, a) && GeometryUtils.onSegment(s, t, b)) {
                    onBoundary = true;
                    break;
                }
            }
            assertTrue(onBoundary, () -> "boundary edge " + be + " not on original boundary");
        }
        assertEquals(perimeter, edgeSum, 1e-8, "boundary edges must cover the perimeter exactly");
    }

    /** 全部单元 CCW。 */
    static void assertAllCounterClockwise(Mesh mesh) {
        for (MeshElement e : mesh.elements().values()) {
            assertTrue(GeometryUtils.orient(
                    mesh.point(e.n0()), mesh.point(e.n1()), mesh.point(e.n2())) > 0,
                    () -> "element " + e.id() + " not CCW");
        }
    }
}
