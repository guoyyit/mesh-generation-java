package com.github.guoyyit.meshgenerationjava.mesh.refine;

import com.github.guoyyit.meshgenerationjava.mesh.geometry.GeometryUtils;
import com.github.guoyyit.meshgenerationjava.mesh.geometry.Point2;
import com.github.guoyyit.meshgenerationjava.mesh.model.BoundaryEdge;
import com.github.guoyyit.meshgenerationjava.mesh.model.Mesh;
import com.github.guoyyit.meshgenerationjava.mesh.model.MeshElement;
import com.github.guoyyit.meshgenerationjava.mesh.model.MeshNode;
import com.github.guoyyit.meshgenerationjava.mesh.model.SplitEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.TreeSet;
import java.util.Set;

/**
 * Rivara 最长边二分细分：沿单元最长边插入中点，并同时二分共享该边的邻居，
 * 通过"最长边传播"保证网格始终协调（无悬挂节点/悬挂边）。
 * 每次二分记录 {@link SplitEvent}，供粗化严格逆序回滚。
 */
public final class MeshRefiner {

    private MeshRefiner() {
    }

    /**
     * 对给定单元集合细分 levels 轮；每轮作用于上一轮子单元。
     * 返回实际执行的二分次数。
     */
    public static int refineElements(Mesh mesh, Collection<Long> elementIds, int levels) {
        if (levels < 1) {
            return 0;
        }
        Set<Long> targets = new TreeSet<>(elementIds);
        int splits = 0;
        for (int level = 0; level < levels && !targets.isEmpty(); level++) {
            Set<Long> next = new TreeSet<>();
            for (long id : targets) {
                if (!mesh.hasElement(id)) {
                    continue;
                }
                int eventsBefore = mesh.splitEvents().size();
                splits += bisect(mesh, id);
                List<SplitEvent> events = mesh.splitEvents();
                for (int i = eventsBefore; i < events.size(); i++) {
                    SplitEvent ev = events.get(i);
                    for (MeshElement removed : ev.removedElements()) {
                        if (removed.id() == id) {
                            for (MeshElement added : ev.addedElements()) {
                                next.add(added.id());
                            }
                        }
                    }
                }
            }
            targets = next;
        }
        return splits;
    }

    /**
     * 对单个单元执行一次 Rivara 二分（含必要的传播）。
     * 返回执行的二分次数；单元已不存在或继续二分会产生退化单元时返回 0。
     */
    public static int bisect(Mesh mesh, long elementId) {
        int splits = 0;
        Deque<Long> stack = new ArrayDeque<>();
        stack.push(elementId);
        while (!stack.isEmpty()) {
            long cur = stack.peek();
            if (!mesh.hasElement(cur)) {
                stack.pop();
                continue;
            }
            MeshElement e = mesh.element(cur);
            long[] edge = longestEdge(mesh, e);
            Long neighborId = mesh.neighborAcross(edge[0], edge[1], cur);
            if (neighborId == null) {
                if (!splitPair(mesh, cur, null, edge[0], edge[1])) {
                    return splits;
                }
                splits++;
                stack.pop();
                continue;
            }
            MeshElement neighbor = mesh.element(neighborId);
            long[] nEdge = longestEdge(mesh, neighbor);
            if (sameEdge(edge, nEdge)) {
                if (!splitPair(mesh, cur, neighborId, edge[0], edge[1])) {
                    return splits;
                }
                splits++;
                stack.pop();
            } else {
                stack.push(neighborId);
            }
        }
        return splits;
    }

    /**
     * 强制沿边界边 (a,b) 二分其邻接单元（边界侵占处理）。
     * 边界边只有一个邻接单元，无需传播。返回是否执行。
     */
    public static boolean bisectBoundaryEdge(Mesh mesh, long a, long b) {
        if (mesh.boundaryIdOfEdge(a, b) == -1) {
            return false;
        }
        Long tId = mesh.neighborAcross(a, b, -1L);
        if (tId == null) {
            return false;
        }
        return splitPair(mesh, tId, null, a, b);
    }

    /** 单元最长边（顶点 id 对）；等长时按单元内边序取先者，保证确定性。 */
    public static long[] longestEdge(Mesh mesh, MeshElement e) {
        long[] v = e.vertices();
        long[][] edges = {{v[0], v[1]}, {v[1], v[2]}, {v[2], v[0]}};
        long[] best = edges[0];
        double bestLen = -1.0;
        for (long[] edge : edges) {
            Point2 p = mesh.point(edge[0]);
            Point2 q = mesh.point(edge[1]);
            double len = p.distanceTo(q);
            if (len > bestLen) {
                bestLen = len;
                best = edge;
            }
        }
        return best;
    }

    private static boolean sameEdge(long[] e1, long[] e2) {
        return (e1[0] == e2[0] && e1[1] == e2[1]) || (e1[0] == e2[1] && e1[1] == e2[0]);
    }

    /**
     * 同时二分共享边 (a,b) 两侧单元，保持协调性。
     * 返回 false 表示不执行：继续二分会使任一子单元面积低于容差。
     */
    private static boolean splitPair(Mesh mesh, long tId, Long nId, long a, long b) {
        MeshElement te = mesh.element(tId);
        MeshElement ne = nId == null ? null : mesh.element(nId);
        double childArea = GeometryUtils.triangleArea(
                mesh.point(te.n0()), mesh.point(te.n1()), mesh.point(te.n2())) * 0.5;
        if (ne != null) {
            childArea = Math.min(childArea, GeometryUtils.triangleArea(
                    mesh.point(ne.n0()), mesh.point(ne.n1()), mesh.point(ne.n2())) * 0.5);
        }
        if (childArea < GeometryUtils.MIN_ELEMENT_AREA) {
            return false;
        }

        Point2 pa = mesh.point(a);
        Point2 pb = mesh.point(b);
        int boundaryId = mesh.boundaryIdOfEdge(a, b);
        Point2 midPoint = pa.midpoint(pb);
        long mid = mesh.addNode(midPoint.x(), midPoint.y(), boundaryId);

        Mesh.BoundarySplit bs = mesh.splitBoundaryEdge(a, b, mid);

        List<MeshElement> removed = new ArrayList<>();
        List<MeshElement> added = new ArrayList<>();

        removed.add(mesh.element(tId));
        long apex1 = apexOf(mesh.element(tId), a, b);
        mesh.removeElement(tId);
        long c1 = mesh.addElement(a, mid, apex1);
        long c2 = mesh.addElement(mid, b, apex1);
        added.add(mesh.element(c1));
        added.add(mesh.element(c2));

        if (nId != null) {
            removed.add(mesh.element(nId));
            long apex2 = apexOf(mesh.element(nId), a, b);
            mesh.removeElement(nId);
            long c3 = mesh.addElement(a, mid, apex2);
            long c4 = mesh.addElement(mid, b, apex2);
            added.add(mesh.element(c3));
            added.add(mesh.element(c4));
        }

        List<BoundaryEdge> removedBoundary = bs == null ? List.of() : List.of(bs.removed());
        List<BoundaryEdge> addedBoundary = bs == null ? List.of() : List.of(bs.first(), bs.second());
        mesh.addSplitEvent(new SplitEvent(mesh.nextSplitEventId(),
                List.copyOf(removed), List.copyOf(added), mid,
                removedBoundary, addedBoundary));
        return true;
    }

    private static long apexOf(MeshElement e, long a, long b) {
        for (long v : e.vertices()) {
            if (v != a && v != b) {
                return v;
            }
        }
        throw new IllegalStateException("element " + e.id() + " has no apex for edge (" + a + "," + b + ")");
    }
}
