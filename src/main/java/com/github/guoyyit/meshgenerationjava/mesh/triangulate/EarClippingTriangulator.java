package com.github.guoyyit.meshgenerationjava.mesh.triangulate;

import com.github.guoyyit.meshgenerationjava.mesh.error.MeshErrorCode;
import com.github.guoyyit.meshgenerationjava.mesh.error.MeshException;
import com.github.guoyyit.meshgenerationjava.mesh.geometry.GeometryUtils;
import com.github.guoyyit.meshgenerationjava.mesh.geometry.Point2;
import com.github.guoyyit.meshgenerationjava.mesh.model.BoundaryEdge;
import com.github.guoyyit.meshgenerationjava.mesh.model.Mesh;
import com.github.guoyyit.meshgenerationjava.mesh.model.PlanarRegion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * 耳切法约束三角化：
 * <ol>
 *   <li>内孔通过可见桥接边并入外边界，形成单一弱简单环；</li>
 *   <li>对环做耳切，退化"耳"（桥接产生的重复节点）仅剪除不产生单元；</li>
 *   <li>边界边逐条登记，保证边界被网格边精确保持。</li>
 * </ol>
 * 相同输入与随机种子下结果逐项一致；种子仅影响耳扫描起点。
 */
public final class EarClippingTriangulator {

    private EarClippingTriangulator() {
    }

    /** 环顶点：nodeId 指向网格节点（桥接处不同环位置共享同一节点）。 */
    private record LoopVertex(long nodeId, Point2 p) {
    }

    public static Mesh triangulate(PlanarRegion region, long seed) {
        Mesh mesh = new Mesh();

        List<Point2> outer = region.outer();
        List<Long> outerIds = new ArrayList<>();
        for (Point2 p : outer) {
            outerIds.add(mesh.addNode(p.x(), p.y(), 0));
        }
        List<LoopVertex> loop = new ArrayList<>();
        for (int i = 0; i < outer.size(); i++) {
            loop.add(new LoopVertex(outerIds.get(i), outer.get(i)));
        }
        for (int i = 0; i < outer.size(); i++) {
            mesh.addBoundaryEdge(new BoundaryEdge(
                    outerIds.get(i), outerIds.get((i + 1) % outer.size()), 0));
        }

        List<List<LoopVertex>> remainingHoles = new ArrayList<>();
        for (int h = 0; h < region.holes().size(); h++) {
            List<Point2> hole = region.holes().get(h);
            int boundaryId = h + 1;
            List<Long> ids = new ArrayList<>();
            for (Point2 p : hole) {
                ids.add(mesh.addNode(p.x(), p.y(), boundaryId));
            }
            List<LoopVertex> holeLoop = new ArrayList<>();
            for (int i = 0; i < hole.size(); i++) {
                holeLoop.add(new LoopVertex(ids.get(i), hole.get(i)));
            }
            remainingHoles.add(holeLoop);
            for (int i = 0; i < hole.size(); i++) {
                mesh.addBoundaryEdge(new BoundaryEdge(
                        ids.get(i), ids.get((i + 1) % hole.size()), boundaryId));
            }
        }

        for (List<LoopVertex> hole : remainingHoles) {
            bridgeHole(loop, hole);
        }

        earClip(mesh, loop, seed);
        return mesh;
    }

    // ---------------------------------------------------------------- 桥接

    /** 选取最近且可见的 (环顶点, 孔顶点) 对，将孔拼入环。 */
    private static void bridgeHole(List<LoopVertex> loop, List<LoopVertex> hole) {
        record Candidate(double dist, int holeIdx, int loopIdx) {
        }
        List<Candidate> candidates = new ArrayList<>();
        for (int hi = 0; hi < hole.size(); hi++) {
            for (int li = 0; li < loop.size(); li++) {
                candidates.add(new Candidate(
                        hole.get(hi).p().distanceTo(loop.get(li).p()), hi, li));
            }
        }
        candidates.sort(Comparator.comparingDouble(Candidate::dist)
                .thenComparingInt(Candidate::holeIdx)
                .thenComparingInt(Candidate::loopIdx));

        for (Candidate c : candidates) {
            if (visible(loop, hole, c.loopIdx(), c.holeIdx())) {
                splice(loop, hole, c.loopIdx(), c.holeIdx());
                return;
            }
        }
        throw new MeshException(MeshErrorCode.INCONSISTENT_MESH,
                "no visible bridge pair found between hole and boundary");
    }

    /** 桥接段不得触碰任何边（自身端点关联边除外）。 */
    private static boolean visible(List<LoopVertex> loop, List<LoopVertex> hole,
                                   int loopIdx, int holeIdx) {
        Point2 a = loop.get(loopIdx).p();
        Point2 b = hole.get(holeIdx).p();
        int n = loop.size();
        for (int i = 0; i < n; i++) {
            if (i == loopIdx || (i + 1) % n == loopIdx) {
                continue;
            }
            if (GeometryUtils.segmentsTouch(a, b, loop.get(i).p(), loop.get((i + 1) % n).p())) {
                return false;
            }
        }
        int m = hole.size();
        for (int i = 0; i < m; i++) {
            if (i == holeIdx || (i + 1) % m == holeIdx) {
                continue;
            }
            if (GeometryUtils.segmentsTouch(a, b, hole.get(i).p(), hole.get((i + 1) % m).p())) {
                return false;
            }
        }
        return true;
    }

    /** 在 loopIdx 处把孔序列（从 holeIdx 起绕一圈）拼入环。 */
    private static void splice(List<LoopVertex> loop, List<LoopVertex> hole,
                               int loopIdx, int holeIdx) {
        List<LoopVertex> merged = new ArrayList<>();
        int m = hole.size();
        for (int i = 0; i <= loopIdx; i++) {
            merged.add(loop.get(i));
        }
        for (int k = 0; k < m; k++) {
            merged.add(hole.get((holeIdx + k) % m));
        }
        merged.add(hole.get(holeIdx));
        merged.add(loop.get(loopIdx));
        for (int i = loopIdx + 1; i < loop.size(); i++) {
            merged.add(loop.get(i));
        }
        loop.clear();
        loop.addAll(merged);
    }

    // ---------------------------------------------------------------- 耳切

    private static void earClip(Mesh mesh, List<LoopVertex> poly, long seed) {
        int startOffset = 0;
        if (poly.size() > 1) {
            startOffset = new Random(seed).nextInt(poly.size());
        }
        int i = startOffset % Math.max(poly.size(), 1);
        int scannedSinceClip = 0;
        boolean silentClipUsed = false;

        while (poly.size() > 3) {
            int n = poly.size();
            if (scannedSinceClip >= n) {
                if (!silentClipUsed && trySilentClip(poly)) {
                    silentClipUsed = true;
                    scannedSinceClip = 0;
                    i = i % Math.max(poly.size(), 1);
                    continue;
                }
                throw new MeshException(MeshErrorCode.INCONSISTENT_MESH,
                        "ear clipping stalled: no convex ear found (loop size " + n + ")");
            }
            LoopVertex prev = poly.get((i + n - 1) % n);
            LoopVertex curr = poly.get(i);
            LoopVertex next = poly.get((i + 1) % n);

            boolean distinct = curr.nodeId() != prev.nodeId()
                    && curr.nodeId() != next.nodeId()
                    && prev.nodeId() != next.nodeId();
            boolean convex = GeometryUtils.orientSign(prev.p(), curr.p(), next.p()) == 1;

            if (distinct && convex
                    && GeometryUtils.triangleArea(prev.p(), curr.p(), next.p())
                    >= GeometryUtils.MIN_ELEMENT_AREA
                    && noVertexStrictlyInside(poly, prev, curr, next)) {
                mesh.addElement(prev.nodeId(), curr.nodeId(), next.nodeId());
                poly.remove(i);
                scannedSinceClip = 0;
                silentClipUsed = false;
                i = i % Math.max(poly.size(), 1);
            } else {
                scannedSinceClip++;
                i = (i + 1) % n;
            }
        }

        if (poly.size() == 3) {
            LoopVertex a = poly.get(0);
            LoopVertex b = poly.get(1);
            LoopVertex c = poly.get(2);
            boolean distinct = a.nodeId() != b.nodeId() && b.nodeId() != c.nodeId()
                    && a.nodeId() != c.nodeId();
            if (distinct
                    && GeometryUtils.triangleArea(a.p(), b.p(), c.p())
                    >= GeometryUtils.MIN_ELEMENT_AREA) {
                mesh.addElement(a.nodeId(), b.nodeId(), c.nodeId());
            } else if (distinct) {
                throw new MeshException(MeshErrorCode.INCONSISTENT_MESH,
                        "final triangle degenerate with distinct nodes");
            }
        }
    }

    /** 桥接缝隙闭合处的重复节点对：剪除一份拷贝（不产生单元）。 */
    private static boolean trySilentClip(List<LoopVertex> poly) {
        int n = poly.size();
        for (int i = 0; i < n; i++) {
            LoopVertex prev = poly.get((i + n - 1) % n);
            LoopVertex curr = poly.get(i);
            LoopVertex next = poly.get((i + 1) % n);
            if (curr.nodeId() == next.nodeId() || curr.nodeId() == prev.nodeId()) {
                poly.remove(i);
                return true;
            }
        }
        return false;
    }

    private static boolean noVertexStrictlyInside(List<LoopVertex> poly,
                                                  LoopVertex a, LoopVertex b, LoopVertex c) {
        for (LoopVertex v : poly) {
            if (v == a || v == b || v == c) {
                continue;
            }
            Point2 p = v.p();
            if (GeometryUtils.orientSign(a.p(), b.p(), p) == 1
                    && GeometryUtils.orientSign(b.p(), c.p(), p) == 1
                    && GeometryUtils.orientSign(c.p(), a.p(), p) == 1) {
                return false;
            }
        }
        return true;
    }
}
