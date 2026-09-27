package com.github.guoyyit.meshgenerationjava.core;

import com.github.guoyyit.meshgenerationjava.geometry.GeometryUtils;
import com.github.guoyyit.meshgenerationjava.geometry.Point2D;
import com.github.guoyyit.meshgenerationjava.validation.ErrorCode;
import com.github.guoyyit.meshgenerationjava.validation.MeshValidationException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Conforming triangular mesh. Element and node ids are stable indices;
 * removals are tombstones so ids never shift. Use {@link #compacted()} to renumber.
 */
public class Mesh {

    private final List<MeshNode> nodes = new ArrayList<>();
    private final Set<Integer> removedNodes = new HashSet<>();
    private final List<Element> elements = new ArrayList<>();
    private final List<BoundaryEdge> boundaryEdges = new ArrayList<>();
    private final Map<Long, BoundaryEdge> boundaryEdgeIndex = new LinkedHashMap<>();

    public Mesh() {
    }

    public int addNode(double x, double y) {
        int id = nodes.size();
        nodes.add(new MeshNode(id, x, y));
        return id;
    }

    /** Adds a CCW triangle; rejects degenerate orientation. */
    public int addElement(int a, int b, int c) {
        if (a == b || b == c || a == c) {
            throw new MeshValidationException(ErrorCode.DEGENERATE_GEOMETRY,
                    "element with repeated node: " + a + "," + b + "," + c);
        }
        checkAliveNode(a);
        checkAliveNode(b);
        checkAliveNode(c);
        double orient = GeometryUtils.orient(point(a), point(b), point(c));
        if (orient <= GeometryUtils.EPS) {
            throw new MeshValidationException(ErrorCode.DEGENERATE_GEOMETRY,
                    "element (" + a + "," + b + "," + c + ") is not strictly CCW, orient=" + orient);
        }
        int id = elements.size();
        elements.add(new Element(id, a, b, c));
        return id;
    }

    private void checkAliveNode(int id) {
        if (id < 0 || id >= nodes.size() || removedNodes.contains(id)) {
            throw new MeshValidationException(ErrorCode.INVALID_PARAMETER, "node " + id + " is not alive");
        }
    }

    public void addBoundaryEdge(int a, int b, int marker) {
        BoundaryEdge edge = new BoundaryEdge(a, b, marker);
        boundaryEdges.add(edge);
        boundaryEdgeIndex.put(edgeKey(a, b), edge);
    }

    public void removeElement(int elementId) {
        elements.get(elementId).kill();
    }

    public void removeBoundaryEdge(long edgeKey) {
        BoundaryEdge removed = boundaryEdgeIndex.remove(edgeKey);
        if (removed != null) {
            boundaryEdges.remove(removed);
        }
    }

    public void removeNode(int nodeId) {
        removedNodes.add(nodeId);
    }

    public MeshNode node(int id) {
        return nodes.get(id);
    }

    public Point2D point(int id) {
        MeshNode n = nodes.get(id);
        return new Point2D(n.x(), n.y());
    }

    public Element element(int id) {
        return elements.get(id);
    }

    public List<MeshNode> nodes() {
        return List.copyOf(nodes);
    }

    public List<MeshNode> aliveNodes() {
        List<MeshNode> alive = new ArrayList<>();
        for (MeshNode n : nodes) {
            if (!removedNodes.contains(n.id())) {
                alive.add(n);
            }
        }
        return alive;
    }

    /** Alive elements only. */
    public List<Element> elements() {
        List<Element> alive = new ArrayList<>();
        for (Element e : elements) {
            if (e.isAlive()) {
                alive.add(e);
            }
        }
        return alive;
    }

    public List<BoundaryEdge> boundaryEdges() {
        return List.copyOf(boundaryEdges);
    }

    /** edgeKey -> list of [elementId, localEdgeIndex]. */
    public Map<Long, List<int[]>> buildEdgeMap() {
        Map<Long, List<int[]>> map = new HashMap<>();
        for (Element e : elements) {
            if (!e.isAlive()) {
                continue;
            }
            for (int local = 0; local < 3; local++) {
                long key = edgeKey(e.node(local), e.node((local + 1) % 3));
                map.computeIfAbsent(key, k -> new ArrayList<>()).add(new int[]{e.getId(), local});
            }
        }
        return map;
    }

    public Map<Long, BoundaryEdge> boundaryEdgeMap() {
        return new LinkedHashMap<>(boundaryEdgeIndex);
    }

    public Set<Integer> boundaryNodeIds() {
        Set<Integer> ids = new HashSet<>();
        for (BoundaryEdge e : boundaryEdges) {
            ids.add(e.a());
            ids.add(e.b());
        }
        return ids;
    }

    public static long edgeKey(int a, int b) {
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        return ((long) lo << 32) | (hi & 0xffffffffL);
    }

    public double totalArea() {
        double sum = 0.0;
        for (Element e : elements()) {
            sum += GeometryUtils.triangleArea(point(e.getN0()), point(e.getN1()), point(e.getN2()));
        }
        return sum;
    }

    public int nodeCount() {
        return nodes.size() - removedNodes.size();
    }

    public int elementCount() {
        int count = 0;
        for (Element e : elements) {
            if (e.isAlive()) {
                count++;
            }
        }
        return count;
    }

    public Mesh copy() {
        Mesh copy = new Mesh();
        copy.nodes.addAll(this.nodes);
        copy.removedNodes.addAll(this.removedNodes);
        for (Element e : this.elements) {
            Element clone = new Element(e.getId(), e.getN0(), e.getN1(), e.getN2());
            if (!e.isAlive()) {
                clone.kill();
            }
            copy.elements.add(clone);
        }
        copy.boundaryEdges.addAll(this.boundaryEdges);
        copy.boundaryEdgeIndex.putAll(this.boundaryEdgeIndex);
        return copy;
    }

    /** Deep copy with tombstones dropped and ids renumbered compactly. */
    public Mesh compacted() {
        Mesh out = new Mesh();
        int[] nodeMap = new int[nodes.size()];
        for (int i = 0; i < nodes.size(); i++) {
            if (removedNodes.contains(i)) {
                nodeMap[i] = -1;
            } else {
                MeshNode n = nodes.get(i);
                nodeMap[i] = out.addNode(n.x(), n.y());
            }
        }
        for (Element e : elements) {
            if (e.isAlive()) {
                out.addElement(nodeMap[e.getN0()], nodeMap[e.getN1()], nodeMap[e.getN2()]);
            }
        }
        for (BoundaryEdge be : boundaryEdges) {
            out.addBoundaryEdge(nodeMap[be.a()], nodeMap[be.b()], be.marker());
        }
        return out;
    }

    /** Structural problems (hanging edges, bad orientation, duplicates); empty when consistent. */
    public List<String> checkConsistency() {
        List<String> problems = new ArrayList<>();
        for (Element e : elements()) {
            double orient = GeometryUtils.orient(point(e.getN0()), point(e.getN1()), point(e.getN2()));
            if (orient <= GeometryUtils.EPS) {
                problems.add("element " + e.getId() + " has non-positive orientation");
            }
            for (int local = 0; local < 3; local++) {
                if (removedNodes.contains(e.node(local))) {
                    problems.add("element " + e.getId() + " references removed node " + e.node(local));
                }
            }
        }
        // Duplicate element node sets.
        Set<List<Integer>> seen = new HashSet<>();
        for (Element e : elements()) {
            List<Integer> key = List.of(e.getN0(), e.getN1(), e.getN2()).stream().sorted().toList();
            if (!seen.add(key)) {
                problems.add("duplicate element with nodes " + key);
            }
        }
        // Edge conformity: every edge is shared by 1 (boundary) or 2 (interior) elements.
        Map<Long, List<int[]>> edgeMap = buildEdgeMap();
        for (Map.Entry<Long, List<int[]>> entry : edgeMap.entrySet()) {
            int sharers = entry.getValue().size();
            boolean isBoundary = boundaryEdgeIndex.containsKey(entry.getKey());
            if (sharers > 2) {
                problems.add("edge " + entry.getKey() + " shared by " + sharers + " elements");
            } else if (sharers == 1 && !isBoundary) {
                problems.add("interior edge " + entry.getKey() + " has a single element (hanging edge)");
            } else if (sharers == 2 && isBoundary) {
                problems.add("boundary edge " + entry.getKey() + " shared by two elements");
            }
        }
        for (Map.Entry<Long, BoundaryEdge> entry : boundaryEdgeIndex.entrySet()) {
            List<int[]> sharers = edgeMap.get(entry.getKey());
            if (sharers == null || sharers.size() != 1) {
                problems.add("boundary edge " + entry.getKey() + " not backed by exactly one element");
            }
            BoundaryEdge be = entry.getValue();
            if (removedNodes.contains(be.a()) || removedNodes.contains(be.b())) {
                problems.add("boundary edge references removed node");
            }
        }
        return new ArrayList<>(new TreeSet<>(problems));
    }
}
