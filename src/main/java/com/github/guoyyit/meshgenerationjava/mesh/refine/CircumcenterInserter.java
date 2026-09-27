package com.github.guoyyit.meshgenerationjava.mesh.refine;

import com.github.guoyyit.meshgenerationjava.mesh.error.MeshErrorCode;
import com.github.guoyyit.meshgenerationjava.mesh.error.MeshException;
import com.github.guoyyit.meshgenerationjava.mesh.geometry.GeometryUtils;
import com.github.guoyyit.meshgenerationjava.mesh.geometry.Point2;
import com.github.guoyyit.meshgenerationjava.mesh.model.BoundaryEdge;
import com.github.guoyyit.meshgenerationjava.mesh.model.Mesh;
import com.github.guoyyit.meshgenerationjava.mesh.model.MeshElement;
import com.github.guoyyit.meshgenerationjava.mesh.model.SplitEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Ruppert/Chew 式外接圆圆心插入细化：
 * <ol>
 *   <li>低质量单元的外接圆圆心侵占区域边界边时，改为中点二分该边界边；</li>
 *   <li>否则用 Bowyer-Watson 空腔重剖插入圆心，新点为内部节点；</li>
   * <li>插入被拒绝（圆外在域外、空腔退化/非星形）时返回 false，调用方退回最长边二分。</li>
 * </ol>
 * 所有操作记录为可逆 {@link SplitEvent}（空腔/新单元 + 新节点），供粗化统一回滚。
 */
public final class CircumcenterInserter {

    private CircumcenterInserter() {
    }

    /**
     * 尝试用圆心插入改进单元。返回操作类型："insert" / "boundary" / "none"。
     */
    public static String tryImprove(Mesh mesh, long elementId) {
        if (!mesh.hasElement(elementId)) {
            return "none";
        }
        MeshElement e = mesh.element(elementId);
        Point2 center = GeometryUtils.circumcenter(
                mesh.point(e.n0()), mesh.point(e.n1()), mesh.point(e.n2()));
        if (center == null) {
            return "none";
        }
        // 1) 侵占检查：确定性顺序（边界边插入序）
        List<BoundaryEdge> boundaries = mesh.boundaryEdges();
        for (int i = 0; i < boundaries.size(); i++) {
            BoundaryEdge be = boundaries.get(i);
            if (GeometryUtils.insideDiametralCircle(mesh.point(be.a()), mesh.point(be.b()), center)) {
                return MeshRefiner.bisectBoundaryEdge(mesh, be.a(), be.b()) ? "boundary" : "none";
            }
        }
        // 2) Bowyer-Watson 插入（从当前单元出发走点定位）
        return insertCircumcenter(mesh, elementId, center) ? "insert" : "none";
    }

    // ---------------------------------------------------------------- 空腔

    private static boolean insertCircumcenter(Mesh mesh, long seedElement, Point2 p) {
        Long seed = locateByWalk(mesh, seedElement, p);
        if (seed == null) {
            return false;
        }
        // BFS 收集外接圆包含 p 的单元；空腔不得跨过区域边界边
        Set<Long> cavity = new LinkedHashSet<>();
        Deque<Long> stack = new ArrayDeque<>();
        stack.push(seed);
        while (!stack.isEmpty()) {
            long id = stack.pop();
            if (cavity.contains(id) || !mesh.hasElement(id)) {
                continue;
            }
            MeshElement t = mesh.element(id);
            if (!GeometryUtils.insideCircumcircle(
                    mesh.point(t.n0()), mesh.point(t.n1()), mesh.point(t.n2()), p)) {
                continue;
            }
            cavity.add(id);
            long[] v = t.vertices();
            for (int i = 0; i < 3; i++) {
                long x = v[i];
                long y = v[(i + 1) % 3];
                if (mesh.boundaryIdOfEdge(x, y) != -1) {
                    continue; // 不跨区域边界
                }
                Long nbr = mesh.neighborAcross(x, y, id);
                if (nbr != null) {
                    stack.push(nbr);
                }
            }
        }
        if (cavity.isEmpty()) {
            return false;
        }

        // 空腔边界：属于空腔单元、但另一侧单元不在空腔（或为区域边界）的边
        Map<Mesh.EdgeKey, long[]> boundaryOfCavity = new LinkedHashMap<>();
        for (long id : cavity) {
            MeshElement t = mesh.element(id);
            long[] v = t.vertices();
            for (int i = 0; i < 3; i++) {
                long x = v[i];
                long y = v[(i + 1) % 3];
                Long nbr = mesh.neighborAcross(x, y, id);
                if (nbr == null || !cavity.contains(nbr)) {
                    boundaryOfCavity.put(Mesh.EdgeKey.of(x, y), new long[]{x, y});
                }
            }
        }

        // 校验新单元：非退化、且不与区域边界交叉
        for (long[] edge : boundaryOfCavity.values()) {
            Point2 a = mesh.point(edge[0]);
            Point2 b = mesh.point(edge[1]);
            if (GeometryUtils.triangleArea(a, b, p) < GeometryUtils.MIN_ELEMENT_AREA) {
                return false;
            }
            if (crossesRegionBoundary(mesh, p, edge[0], edge[1])) {
                return false;
            }
        }
        // 新点不得与现有节点重合
        for (MeshNodeView nv : cavityVertexViews(mesh, cavity)) {
            if (nv.p().distanceTo(p) < GeometryUtils.MIN_ELEMENT_AREA) {
                return false;
            }
        }

        double oldArea = 0.0;
        for (long id : cavity) {
            MeshElement t = mesh.element(id);
            oldArea += GeometryUtils.triangleArea(
                    mesh.point(t.n0()), mesh.point(t.n1()), mesh.point(t.n2()));
        }
        double newArea = 0.0;
        for (long[] edge : boundaryOfCavity.values()) {
            newArea += GeometryUtils.triangleArea(mesh.point(edge[0]), mesh.point(edge[1]), p);
        }
        if (Math.abs(oldArea - newArea) > 1e-8 * Math.max(1.0, oldArea)) {
            return false; // 空腔对 p 非星形，会产生重叠或空洞
        }

        // 执行：删空腔、加新节点与单元，记录可逆事件
        List<MeshElement> removed = new ArrayList<>();
        for (long id : cavity) {
            removed.add(mesh.element(id));
        }
        for (long id : cavity) {
            mesh.removeElement(id);
        }
        long nodeId = mesh.addNode(p.x(), p.y(), -1);
        List<MeshElement> added = new ArrayList<>();
        for (long[] edge : boundaryOfCavity.values()) {
            long tid = mesh.addElement(edge[0], edge[1], nodeId);
            added.add(mesh.element(tid));
        }
        mesh.addSplitEvent(new SplitEvent(mesh.nextSplitEventId(),
                List.copyOf(removed), List.copyOf(added), nodeId, List.of(), List.of()));
        return true;
    }

    /** 从空腔任一边到 p 的连线是否与区域边界交叉（端点关联的边除外）。 */
    private static boolean crossesRegionBoundary(Mesh mesh, Point2 p, long a, long b) {
        for (BoundaryEdge be : mesh.boundaryEdges()) {
            if (be.a() == a || be.a() == b || be.b() == a || be.b() == b) {
                continue;
            }
            Point2 u = mesh.point(be.a());
            Point2 v = mesh.point(be.b());
            if (GeometryUtils.segmentsTouch(mesh.point(a), p, u, v)
                    || GeometryUtils.segmentsTouch(mesh.point(b), p, u, v)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从起始单元出发走向包含 p 的单元；走出区域边界或非 Delaunay 网格上循环时返回 null。
     */
    private static Long locateByWalk(Mesh mesh, long start, Point2 p) {
        long current = start;
        for (int hops = 0; hops < 10_000; hops++) {
            if (!mesh.hasElement(current)) {
                return null;
            }
            MeshElement e = mesh.element(current);
            Point2 a = mesh.point(e.n0());
            Point2 b = mesh.point(e.n1());
            Point2 c = mesh.point(e.n2());
            int s1 = GeometryUtils.orientSign(a, b, p);
            int s2 = GeometryUtils.orientSign(b, c, p);
            int s3 = GeometryUtils.orientSign(c, a, p);
            if (s1 >= 0 && s2 >= 0 && s3 >= 0) {
                return current;
            }
            long x;
            long y;
            if (s1 < 0) {
                x = e.n0();
                y = e.n1();
            } else if (s2 < 0) {
                x = e.n1();
                y = e.n2();
            } else {
                x = e.n2();
                y = e.n0();
            }
            if (mesh.boundaryIdOfEdge(x, y) != -1) {
                return null; // p 落在区域外

            }
            Long nbr = mesh.neighborAcross(x, y, current);
            if (nbr == null) {
                return null;
            }
            current = nbr;
        }
        return null;
    }

    private record MeshNodeView(long id, Point2 p) {
    }

    private static List<MeshNodeView> cavityVertexViews(Mesh mesh, Set<Long> cavity) {
        TreeSet<Long> ids = new TreeSet<>();
        for (long id : cavity) {
            for (long v : mesh.element(id).vertices()) {
                ids.add(v);
            }
        }
        List<MeshNodeView> views = new ArrayList<>();
        for (long id : ids) {
            views.add(new MeshNodeView(id, mesh.point(id)));
        }
        return views;
    }
}
