package com.github.guoyyit.meshgenerationjava.refine;

import com.github.guoyyit.meshgenerationjava.core.BoundaryEdge;
import com.github.guoyyit.meshgenerationjava.core.Element;
import com.github.guoyyit.meshgenerationjava.core.Mesh;
import com.github.guoyyit.meshgenerationjava.geometry.GeometryUtils;
import com.github.guoyyit.meshgenerationjava.geometry.Point2D;
import com.github.guoyyit.meshgenerationjava.quality.QualityThresholds;
import com.github.guoyyit.meshgenerationjava.task.CancellationToken;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Local refinement by Rivara longest-edge bisection. The shared edge is always
 * split on both sides (recursively propagating to the neighbor's longest edge),
 * so the mesh stays conforming: no hanging nodes, orientation preserved,
 * boundary edges split on-segment with inherited markers. Splits that would
 * create degenerate slivers are refused and reported instead of silently made.
 */
public class MeshRefiner {

    /** Refines until thresholds are met or the step budget is exhausted; returns bisection count. */
    public int refine(Mesh mesh, QualityThresholds thresholds, int maxSteps, CancellationToken token) {
        thresholds.validate();
        return new Session(mesh).run(thresholds, maxSteps, token);
    }

    /** Local index of the longest edge; deterministic tie-break by lowest index. */
    public static int longestEdgeLocal(Mesh mesh, Element e) {
        int bestLocal = 0;
        double best = -1.0;
        for (int local = 0; local < 3; local++) {
            double d = mesh.point(e.node(local)).distanceTo(mesh.point(e.node((local + 1) % 3)));
            if (d > best + GeometryUtils.EPS) {
                best = d;
                bestLocal = local;
            }
        }
        return bestLocal;
    }

    private static final class Session {
        private final Mesh mesh;
        private final Map<Long, Integer> splitNodes = new HashMap<>();
        private final Map<Long, List<int[]>> edgeMap = new HashMap<>();
        private final Set<Integer> unrefinable = new HashSet<>();
        /** Bisection depth along each element's lineage; bounds angle-driven chasing. */
        private final Map<Integer, Integer> depth = new HashMap<>();

        private Session(Mesh mesh) {
            this.mesh = mesh;
        }

        /** Angle-driven refinement of one lineage stops here; edge-length refinement never does. */
        private static final int MAX_ANGLE_DEPTH = 8;

        int run(QualityThresholds thresholds, int maxSteps, CancellationToken token) {
            int steps = 0;
            while (steps < maxSteps) {
                token.checkCancelled();
                Element worst = null;
                double worstBadness = 1.0;
                for (Element e : mesh.elements()) {
                    if (unrefinable.contains(e.getId())) {
                        continue;
                    }
                    Point2D a = mesh.point(e.getN0());
                    Point2D b = mesh.point(e.getN1());
                    Point2D c = mesh.point(e.getN2());
                    double edgeBadness = GeometryUtils.maxEdgeLength(a, b, c) / thresholds.getMaxEdgeLength();
                    double angleBadness = thresholds.getMinAngleDeg() / GeometryUtils.minAngleDeg(a, b, c);
                    double badness;
                    if (edgeBadness > 1.0) {
                        badness = edgeBadness;
                    } else if (angleBadness > 1.0
                            && depth.getOrDefault(e.getId(), 0) < MAX_ANGLE_DEPTH) {
                        // Bisection of a self-similar sliver never improves its angle;
                        // the depth cap stops the chase, the checker reports it instead.
                        badness = angleBadness;
                    } else {
                        continue;
                    }
                    if (badness > worstBadness + GeometryUtils.EPS) {
                        worstBadness = badness;
                        worst = e;
                    }
                }
                if (worst == null) {
                    break;
                }
                rebuildEdgeMap();
                boolean changed = bisectRecursive(worst.getId(), new HashSet<>());
                if (!changed) {
                    unrefinable.add(worst.getId());
                }
                steps++;
            }
            return steps;
        }

        /** Returns true when at least one edge was actually split. */
        private boolean bisectRecursive(int elementId, Set<Long> visited) {
            Element e = mesh.element(elementId);
            if (e == null || !e.isAlive()) {
                return false;
            }
            int le = longestEdgeLocal(mesh, e);
            long key = Mesh.edgeKey(e.node(le), e.node((le + 1) % 3));
            List<int[]> sharers = edgeMap.get(key);
            if (sharers != null && sharers.size() == 2) {
                int otherId = sharers.get(0)[0] == elementId ? sharers.get(1)[0] : sharers.get(0)[0];
                Element other = mesh.element(otherId);
                if (other != null && other.isAlive()) {
                    int ole = longestEdgeLocal(mesh, other);
                    long otherKey = Mesh.edgeKey(other.node(ole), other.node((ole + 1) % 3));
                    if (otherKey != key && visited.add(otherKey)) {
                        bisectRecursive(otherId, visited);
                        if (!mesh.element(elementId).isAlive()) {
                            return true; // the recursion already split our edge
                        }
                    }
                }
            }
            return splitEdge(key);
        }

        private boolean splitEdge(long key) {
            List<int[]> sharers = edgeMap.get(key);
            if (sharers == null || sharers.isEmpty()) {
                return false;
            }
            int lo = (int) (key >> 32);
            int hi = (int) (key & 0xffffffffL);
            Point2D mid = mesh.point(lo).midpoint(mesh.point(hi));

            // Pre-check: refuse splits that would create degenerate slivers.
            for (int[] ref : sharers) {
                Element e = mesh.element(ref[0]);
                int local = ref[1];
                Point2D a = mesh.point(e.node(local));
                Point2D b = mesh.point(e.node((local + 1) % 3));
                Point2D c = mesh.point(e.node((local + 2) % 3));
                if (degenerate(a, mid, c) || degenerate(mid, b, c)) {
                    return false;
                }
            }

            Integer m = splitNodes.get(key);
            if (m == null) {
                m = mesh.addNode(mid.x(), mid.y());
                splitNodes.put(key, m);
            }
            BoundaryEdge be = mesh.boundaryEdgeMap().get(key);
            if (be != null) {
                mesh.removeBoundaryEdge(key);
                mesh.addBoundaryEdge(be.a(), m, be.marker());
                mesh.addBoundaryEdge(m, be.b(), be.marker());
            }
            for (int[] ref : new ArrayList<>(sharers)) {
                Element e = mesh.element(ref[0]);
                if (!e.isAlive()) {
                    continue;
                }
                int local = ref[1];
                int a = e.node(local);
                int b = e.node((local + 1) % 3);
                int c = e.node((local + 2) % 3);
                int childDepth = depth.getOrDefault(e.getId(), 0) + 1;
                removeFromEdgeMap(e);
                mesh.removeElement(e.getId());
                int e1 = mesh.addElement(a, m, c);
                int e2 = mesh.addElement(m, b, c);
                depth.put(e1, childDepth);
                depth.put(e2, childDepth);
                addToEdgeMap(mesh.element(e1));
                addToEdgeMap(mesh.element(e2));
            }
            return true;
        }

        private static final double MIN_CHILD_ANGLE_DEG = 0.5;

        private static boolean degenerate(Point2D a, Point2D b, Point2D c) {
            double longest = GeometryUtils.maxEdgeLength(a, b, c);
            double limit = 1e-10 * Math.max(1.0, longest * longest);
            if (GeometryUtils.orient(a, b, c) <= limit) {
                return true;
            }
            // Refuse to birth extreme slivers: bisection cannot repair them and
            // would chase them forever; the parent is reported as-is instead.
            return GeometryUtils.minAngleDeg(a, b, c) < MIN_CHILD_ANGLE_DEG;
        }

        private void rebuildEdgeMap() {
            edgeMap.clear();
            for (Element e : mesh.elements()) {
                addToEdgeMap(e);
            }
        }

        private void addToEdgeMap(Element e) {
            for (int local = 0; local < 3; local++) {
                long key = Mesh.edgeKey(e.node(local), e.node((local + 1) % 3));
                edgeMap.computeIfAbsent(key, k -> new ArrayList<>()).add(new int[]{e.getId(), local});
            }
        }

        private void removeFromEdgeMap(Element e) {
            for (int local = 0; local < 3; local++) {
                long key = Mesh.edgeKey(e.node(local), e.node((local + 1) % 3));
                List<int[]> list = edgeMap.get(key);
                if (list != null) {
                    Iterator<int[]> it = list.iterator();
                    while (it.hasNext()) {
                        if (it.next()[0] == e.getId()) {
                            it.remove();
                        }
                    }
                    if (list.isEmpty()) {
                        edgeMap.remove(key);
                    }
                }
            }
        }
    }
}
