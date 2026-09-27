package com.github.guoyyit.meshgenerationjava.mesh.triangulate;

import com.github.guoyyit.meshgenerationjava.mesh.geometry.GeometryUtils;
import com.github.guoyyit.meshgenerationjava.mesh.geometry.Point2;
import com.github.guoyyit.meshgenerationjava.mesh.model.FlipEvent;
import com.github.guoyyit.meshgenerationjava.mesh.model.Mesh;
import com.github.guoyyit.meshgenerationjava.mesh.model.MeshElement;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * 三角化后处理：对内部边做局部 Delaunay 翻边（max-min 角度准则），
 * 消除耳切法产生的狭长三角形，使角度类质量阈值在实践中可达。
 *
 * <p>确定性：每轮按 (lo,hi) 排序遍历全部内部边，严格角度改进才翻转，
 * 角度向量字典序严格上升保证有限轮内收敛。
 */
public final class DelaunayFlipper {

    private static final int MAX_PASSES = 60;
    private static final double ANGLE_EPS_DEG = 1e-9;

    private DelaunayFlipper() {
    }

    /** 返回执行的翻边次数。 */
    public static int improve(Mesh mesh) {
        int total = 0;
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            int flips = 0;
            for (long[] edge : interiorEdges(mesh)) {
                if (tryFlip(mesh, edge[0], edge[1])) {
                    flips++;
                }
            }
            total += flips;
            if (flips == 0) {
                break;
            }
        }
        return total;
    }

    /** 全部内部边（去重、排序）。 */
    private static List<long[]> interiorEdges(Mesh mesh) {
        TreeSet<Mesh.EdgeKey> keys = new TreeSet<>(
                (x, y) -> x.lo() != y.lo() ? Long.compare(x.lo(), y.lo())
                        : Long.compare(x.hi(), y.hi()));
        for (MeshElement e : mesh.elements().values()) {
            long[] v = e.vertices();
            for (int i = 0; i < 3; i++) {
                long a = v[i];
                long b = v[(i + 1) % 3];
                if (mesh.boundaryIdOfEdge(a, b) == -1) {
                    keys.add(Mesh.EdgeKey.of(a, b));
                }
            }
        }
        List<long[]> edges = new ArrayList<>();
        for (Mesh.EdgeKey k : keys) {
            edges.add(new long[]{k.lo(), k.hi()});
        }
        return edges;
    }

    private static boolean tryFlip(Mesh mesh, long a, long b) {
        Long t1Id = mesh.neighborAcross(a, b, -1L);
        if (t1Id == null) {
            return false;
        }
        Long t2Id = mesh.neighborAcross(a, b, t1Id);
        if (t2Id == null) {
            return false; // 边界边不翻
        }
        MeshElement t1 = mesh.element(t1Id);
        MeshElement t2 = mesh.element(t2Id);
        long c = apexOf(t1, a, b);
        long d = apexOf(t2, a, b);
        if (c == d) {
            return false;
        }
        Point2 pa = mesh.point(a);
        Point2 pb = mesh.point(b);
        Point2 pc = mesh.point(c);
        Point2 pd = mesh.point(d);

        // 四边形必须凸：a、b 分居 cd 两侧
        int s1 = GeometryUtils.orientSign(pc, pd, pa);
        int s2 = GeometryUtils.orientSign(pc, pd, pb);
        if (s1 == 0 || s2 == 0 || s1 == s2) {
            return false;
        }
        // cd 不得已是网格中的边（避免重叠单元）
        if (mesh.boundaryIdOfEdge(c, d) != -1 || mesh.neighborAcross(c, d, -1L) != null) {
            return false;
        }
        // 新单元面积必须高于容差
        double area1 = GeometryUtils.triangleArea(pc, pd, pa);
        double area2 = GeometryUtils.triangleArea(pd, pc, pb);
        if (area1 < GeometryUtils.MIN_ELEMENT_AREA || area2 < GeometryUtils.MIN_ELEMENT_AREA) {
            return false;
        }
        double oldMin = minAngle(mesh, t1, t2);
        double newMin = Math.min(
                minAngle(pc, pd, pa), minAngle(pd, pc, pb));
        if (newMin <= oldMin + ANGLE_EPS_DEG) {
            return false;
        }
        mesh.flip(a, b, c, d);
        return true;
    }

    /** 回滚一次翻边事件（供粗化逆序回放）。 */
    public static void revert(Mesh mesh, FlipEvent event) {
        if (event.addedElements().size() != 2 || event.removedElements().size() != 2) {
            throw new IllegalArgumentException("invalid flip event");
        }
        for (MeshElement added : event.addedElements()) {
            mesh.removeElement(added.id());
        }
        for (MeshElement old : event.removedElements()) {
            mesh.restoreElement(old);
        }
        mesh.removeFlipEvent(event);
    }

    private static double minAngle(Mesh mesh, MeshElement t1, MeshElement t2) {
        return Math.min(minAngleOf(mesh, t1), minAngleOf(mesh, t2));
    }

    private static double minAngleOf(Mesh mesh, MeshElement e) {
        return minAngle(mesh.point(e.n0()), mesh.point(e.n1()), mesh.point(e.n2()));
    }

    private static double minAngle(Point2 a, Point2 b, Point2 c) {
        double[] angles = GeometryUtils.triangleAnglesDeg(a, b, c);
        return Math.min(angles[0], Math.min(angles[1], angles[2]));
    }

    private static long apexOf(MeshElement e, long a, long b) {
        for (long v : e.vertices()) {
            if (v != a && v != b) {
                return v;
            }
        }
        throw new IllegalStateException("no apex");
    }
}
