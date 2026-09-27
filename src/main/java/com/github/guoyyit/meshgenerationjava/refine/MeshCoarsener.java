package com.github.guoyyit.meshgenerationjava.refine;

import com.github.guoyyit.meshgenerationjava.core.Element;
import com.github.guoyyit.meshgenerationjava.core.Mesh;
import com.github.guoyyit.meshgenerationjava.core.MeshNode;
import com.github.guoyyit.meshgenerationjava.geometry.GeometryUtils;
import com.github.guoyyit.meshgenerationjava.validation.ErrorCode;
import com.github.guoyyit.meshgenerationjava.validation.MeshValidationException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Local coarsening by safe edge collapse. Only interior edges are collapsed
 * (the boundary endpoint always survives), every collapse is validated against
 * element inversion and duplication, so the mesh stays conforming, oriented
 * and boundary-exact. Deterministic: shortest collapsible edge first.
 */
public class MeshCoarsener {

    /** Collapses interior edges shorter than minEdgeLength; returns collapse count. */
    public int coarsen(Mesh mesh, double minEdgeLength, int maxPasses) {
        if (Double.isNaN(minEdgeLength) || minEdgeLength <= 0.0) {
            throw new MeshValidationException(ErrorCode.INVALID_PARAMETER,
                    "minEdgeLength must be > 0, got " + minEdgeLength);
        }
        if (maxPasses <= 0) {
            throw new MeshValidationException(ErrorCode.INVALID_PARAMETER,
                    "maxPasses must be > 0, got " + maxPasses);
        }
        int total = 0;
        for (int pass = 0; pass < maxPasses; pass++) {
            int before = total;
            while (true) {
                long candidate = findCollapseCandidate(mesh, minEdgeLength);
                if (candidate < 0) {
                    break;
                }
                collapse(mesh, candidate);
                total++;
            }
            if (total == before) {
                break; // converged: no more collapsible edges
            }
        }
        return total;
    }

    /** Shortest collapsible interior edge key, or -1 when none exists. */
    private long findCollapseCandidate(Mesh mesh, double minEdgeLength) {
        Map<Long, List<int[]>> edgeMap = mesh.buildEdgeMap();
        Set<Integer> boundaryNodes = mesh.boundaryNodeIds();
        Set<Integer> alive = aliveNodeIds(mesh);
        double bestLength = Double.POSITIVE_INFINITY;
        long bestKey = -1;
        for (Map.Entry<Long, List<int[]>> entry : edgeMap.entrySet()) {
            if (entry.getValue().size() != 2) {
                continue; // boundary edge or non-conforming: never collapse
            }
            long key = entry.getKey();
            int lo = (int) (key >> 32);
            int hi = (int) (key & 0xffffffffL);
            if (!alive.contains(lo) || !alive.contains(hi)) {
                continue;
            }
            boolean loBoundary = boundaryNodes.contains(lo);
            boolean hiBoundary = boundaryNodes.contains(hi);
            if (loBoundary && hiBoundary) {
                continue; // collapsing would move the boundary
            }
            int u = loBoundary ? hi : (hiBoundary ? lo : hi); // removed endpoint
            int v = loBoundary ? lo : (hiBoundary ? hi : lo); // surviving endpoint
            double length = mesh.point(lo).distanceTo(mesh.point(hi));
            if (length >= minEdgeLength) {
                continue; // only edges below the threshold are coarsened
            }
            if (length < bestLength - GeometryUtils.EPS
                    || (Math.abs(length - bestLength) <= GeometryUtils.EPS && key < bestKey)) {
                if (collapseValid(mesh, u, v)) {
                    bestLength = length;
                    bestKey = key;
                }
            }
        }
        return bestKey;
    }

    /** True when replacing u by v keeps every affected element strictly CCW and unique. */
    private boolean collapseValid(Mesh mesh, int u, int v) {
        Set<List<Integer>> existing = new HashSet<>();
        for (Element e : mesh.elements()) {
            if (!e.contains(u)) {
                existing.add(sortedTriple(e.getN0(), e.getN1(), e.getN2()));
            }
        }
        for (Element e : mesh.elements()) {
            if (!e.contains(u) || e.contains(v)) {
                continue; // untouched, or dies with the collapsed edge
            }
            int a = e.getN0() == u ? v : e.getN0();
            int b = e.getN1() == u ? v : e.getN1();
            int c = e.getN2() == u ? v : e.getN2();
            if (a == b || b == c || a == c) {
                return false;
            }
            if (GeometryUtils.orient(mesh.point(a), mesh.point(b), mesh.point(c)) <= GeometryUtils.EPS) {
                return false; // would invert or degenerate
            }
            if (existing.contains(sortedTriple(a, b, c))) {
                return false; // would duplicate an element
            }
        }
        return true;
    }

    private void collapse(Mesh mesh, long key) {
        Set<Integer> boundaryNodes = mesh.boundaryNodeIds();
        int lo = (int) (key >> 32);
        int hi = (int) (key & 0xffffffffL);
        boolean loBoundary = boundaryNodes.contains(lo);
        boolean hiBoundary = boundaryNodes.contains(hi);
        int u = loBoundary ? hi : (hiBoundary ? lo : hi);
        int v = loBoundary ? lo : (hiBoundary ? hi : lo);
        List<Element> star = new ArrayList<>();
        for (Element e : mesh.elements()) {
            if (e.contains(u)) {
                star.add(e);
            }
        }
        for (Element e : star) {
            mesh.removeElement(e.getId());
            if (!e.contains(v)) {
                int a = e.getN0() == u ? v : e.getN0();
                int b = e.getN1() == u ? v : e.getN1();
                int c = e.getN2() == u ? v : e.getN2();
                mesh.addElement(a, b, c);
            }
        }
        mesh.removeNode(u);
    }

    private static Set<Integer> aliveNodeIds(Mesh mesh) {
        Set<Integer> ids = new HashSet<>();
        for (MeshNode n : mesh.aliveNodes()) {
            ids.add(n.id());
        }
        return ids;
    }

    private static List<Integer> sortedTriple(int a, int b, int c) {
        List<Integer> triple = new ArrayList<>(List.of(a, b, c));
        triple.sort(null);
        return triple;
    }
}
