package com.github.guoyyit.meshgenerationjava.mesh.model;

import com.github.guoyyit.meshgenerationjava.mesh.error.MeshErrorCode;
import com.github.guoyyit.meshgenerationjava.mesh.error.MeshException;
import com.github.guoyyit.meshgenerationjava.mesh.geometry.GeometryUtils;
import com.github.guoyyit.meshgenerationjava.mesh.geometry.Point2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 可变三角形网格。维护以下不变量：
 * <ul>
 *   <li>所有单元为 CCW 朝向且面积大于容差；</li>
 *   <li>无悬挂节点：每条内部边恰好被两个单元共享；</li>
 *   <li>边界边列表与单元边界一一对应。</li>
 * </ul>
 * 邻接关系随增删增量维护，细分/粗化必须通过
 * {@link com.github.guoyyit.meshgenerationjava.mesh.refine.MeshRefiner} 等算法类执行。
 */
public final class Mesh {

    /** 无序边键。 */
    public record EdgeKey(long lo, long hi) {
        public static EdgeKey of(long a, long b) {
            return a < b ? new EdgeKey(a, b) : new EdgeKey(b, a);
        }
    }

    /** 边界边被二分的结果，供细分事件记录。 */
    public record BoundarySplit(BoundaryEdge removed, BoundaryEdge first, BoundaryEdge second) {
    }

    private final Map<Long, MeshNode> nodes = new LinkedHashMap<>();
    private final Map<Long, MeshElement> elements = new LinkedHashMap<>();
    private final List<BoundaryEdge> boundaryEdges = new ArrayList<>();
    private final List<SplitEvent> splitEvents = new ArrayList<>();
    private final List<FlipEvent> flipEvents = new ArrayList<>();

    private final Map<EdgeKey, List<Long>> edgeToElements = new LinkedHashMap<>();
    private final Map<Long, Set<Long>> nodeToElements = new LinkedHashMap<>();
    private final Map<EdgeKey, Integer> boundaryEdgeIndex = new LinkedHashMap<>();

    private long nextNodeId;
    private long nextElementId;
    private long nextSplitEventId;

    // ---------------------------------------------------------------- 节点

    public long addNode(double x, double y, int boundaryId) {
        long id = nextNodeId++;
        nodes.put(id, new MeshNode(id, x, y, boundaryId));
        nodeToElements.put(id, new LinkedHashSet<>());
        return id;
    }

    public void removeNode(long id) {
        Set<Long> used = nodeToElements.get(id);
        if (used != null && !used.isEmpty()) {
            throw new MeshException(MeshErrorCode.INCONSISTENT_MESH,
                    "cannot remove node " + id + ": still referenced by elements " + used);
        }
        nodes.remove(id);
        nodeToElements.remove(id);
    }

    public MeshNode node(long id) {
        MeshNode n = nodes.get(id);
        if (n == null) {
            throw new MeshException(MeshErrorCode.INCONSISTENT_MESH, "unknown node " + id);
        }
        return n;
    }

    public Point2 point(long id) {
        MeshNode n = node(id);
        return new Point2(n.x(), n.y());
    }

    public Map<Long, MeshNode> nodes() {
        return Collections.unmodifiableMap(nodes);
    }

    // ---------------------------------------------------------------- 单元

    /**
     * 新增单元（自动分配 id）。若顶点为顺时针则交换为 CCW；
     * 面积低于容差时抛出 {@link MeshErrorCode#DEGENERATE_ELEMENT}。
     */
    public long addElement(long n0, long n1, long n2) {
        double area2 = GeometryUtils.orient(point(n0), point(n1), point(n2));
        if (Math.abs(area2) * 0.5 < GeometryUtils.MIN_ELEMENT_AREA) {
            throw new MeshException(MeshErrorCode.DEGENERATE_ELEMENT,
                    "element area below tolerance",
                    Map.of("n0", n0, "n1", n1, "n2", n2, "area", Math.abs(area2) * 0.5));
        }
        long a = n0, b = n1, c = n2;
        if (area2 < 0) {
            b = n2;
            c = n1;
        }
        long id = nextElementId++;
        insertElement(new MeshElement(id, a, b, c));
        return id;
    }

    /** 按原 id 恢复单元（仅供粗化回滚使用，单元必须已是 CCW）。 */
    public void restoreElement(MeshElement e) {
        if (elements.containsKey(e.id())) {
            throw new MeshException(MeshErrorCode.INCONSISTENT_MESH,
                    "element id clash on restore: " + e.id());
        }
        insertElement(e);
    }

    private void insertElement(MeshElement e) {
        elements.put(e.id(), e);
        long[] v = e.vertices();
        for (int i = 0; i < 3; i++) {
            long a = v[i];
            long b = v[(i + 1) % 3];
            edgeToElements.computeIfAbsent(EdgeKey.of(a, b), k -> new ArrayList<>()).add(e.id());
            nodeToElements.computeIfAbsent(a, k -> new LinkedHashSet<>()).add(e.id());
        }
    }

    public void removeElement(long id) {
        MeshElement e = elements.remove(id);
        if (e == null) {
            return;
        }
        long[] v = e.vertices();
        for (int i = 0; i < 3; i++) {
            long a = v[i];
            long b = v[(i + 1) % 3];
            EdgeKey key = EdgeKey.of(a, b);
            List<Long> list = edgeToElements.get(key);
            if (list != null) {
                list.remove(id);
                if (list.isEmpty()) {
                    edgeToElements.remove(key);
                }
            }
            Set<Long> set = nodeToElements.get(a);
            if (set != null) {
                set.remove(id);
            }
        }
    }

    public MeshElement element(long id) {
        MeshElement e = elements.get(id);
        if (e == null) {
            throw new MeshException(MeshErrorCode.INCONSISTENT_MESH, "unknown element " + id);
        }
        return e;
    }

    public boolean hasElement(long id) {
        return elements.containsKey(id);
    }

    public Map<Long, MeshElement> elements() {
        return Collections.unmodifiableMap(elements);
    }

    /** 共享边 (a,b) 的另一单元 id；无邻居返回 null。 */
    public Long neighborAcross(long a, long b, long exceptElementId) {
        List<Long> list = edgeToElements.get(EdgeKey.of(a, b));
        if (list == null) {
            return null;
        }
        for (Long id : list) {
            if (id != exceptElementId) {
                return id;
            }
        }
        return null;
    }

    /** 引用某节点的全部单元 id（插入序）。 */
    public Set<Long> elementsOfNode(long nodeId) {
        Set<Long> set = nodeToElements.get(nodeId);
        return set == null ? Set.of() : Collections.unmodifiableSet(set);
    }

    // ---------------------------------------------------------------- 边界边

    public void addBoundaryEdge(BoundaryEdge edge) {
        boundaryEdges.add(edge);
        boundaryEdgeIndex.put(EdgeKey.of(edge.a(), edge.b()), edge.boundaryId());
    }

    public List<BoundaryEdge> boundaryEdges() {
        return Collections.unmodifiableList(boundaryEdges);
    }

    /** 若 (a,b) 是边界边则返回其边界 id，否则 -1。 */
    public int boundaryIdOfEdge(long a, long b) {
        return boundaryEdgeIndex.getOrDefault(EdgeKey.of(a, b), MeshNode.INTERIOR);
    }

    /**
     * 将边界边 (a,b) 二分：移除原有向边，插入 (a,mid)、(mid,b)（保持原方向）。
     * 非边界边返回 null。
     */
    public BoundarySplit splitBoundaryEdge(long a, long b, long mid) {
        EdgeKey key = EdgeKey.of(a, b);
        Integer boundaryId = boundaryEdgeIndex.get(key);
        if (boundaryId == null) {
            return null;
        }
        BoundaryEdge removed = null;
        for (int i = 0; i < boundaryEdges.size(); i++) {
            BoundaryEdge be = boundaryEdges.get(i);
            if (EdgeKey.of(be.a(), be.b()).equals(key)) {
                removed = be;
                boundaryEdges.remove(i);
                break;
            }
        }
        BoundaryEdge first = new BoundaryEdge(removed.a(), mid, boundaryId);
        BoundaryEdge second = new BoundaryEdge(mid, removed.b(), boundaryId);
        boundaryEdges.add(first);
        boundaryEdges.add(second);
        boundaryEdgeIndex.remove(key);
        boundaryEdgeIndex.put(EdgeKey.of(first.a(), first.b()), boundaryId);
        boundaryEdgeIndex.put(EdgeKey.of(second.a(), second.b()), boundaryId);
        return new BoundarySplit(removed, first, second);
    }

    /** 逆操作：把两条边界半边合并回原始边（供粗化回滚）。 */
    public void mergeBoundaryEdges(BoundaryEdge restored, BoundaryEdge removedFirst, BoundaryEdge removedSecond) {
        boundaryEdges.remove(removedFirst);
        boundaryEdges.remove(removedSecond);
        boundaryEdgeIndex.remove(EdgeKey.of(removedFirst.a(), removedFirst.b()));
        boundaryEdgeIndex.remove(EdgeKey.of(removedSecond.a(), removedSecond.b()));
        boundaryEdges.add(restored);
        boundaryEdgeIndex.put(EdgeKey.of(restored.a(), restored.b()), restored.boundaryId());
    }

    // ---------------------------------------------------------------- 细分事件

    public long nextSplitEventId() {
        return nextSplitEventId;
    }

    public void addSplitEvent(SplitEvent event) {
        splitEvents.add(event);
        nextSplitEventId = Math.max(nextSplitEventId, event.id() + 1);
    }

    public void removeSplitEvent(SplitEvent event) {
        splitEvents.remove(event);
    }

    public List<SplitEvent> splitEvents() {
        return Collections.unmodifiableList(splitEvents);
    }

    public List<FlipEvent> flipEvents() {
        return Collections.unmodifiableList(flipEvents);
    }

    /**
     * 记录式翻边：删除共享内部边 (a,b) 两侧单元，沿另一对角线 (c,d) 新增两个 CCW 单元，
     * 并记录可逆 {@link FlipEvent}。调用方负责几何准则（凸四边形、严格角度改进等）。
     */
    public void flip(long a, long b, long c, long d) {
        Long t1Id = neighborAcross(a, b, -1L);
        Long t2Id = t1Id == null ? null : neighborAcross(a, b, t1Id);
        if (t1Id == null || t2Id == null) {
            throw new MeshException(MeshErrorCode.INCONSISTENT_MESH,
                    "flip requires an interior edge with two adjacent elements");
        }
        MeshElement t1 = element(t1Id);
        MeshElement t2 = element(t2Id);
        removeElement(t1Id);
        removeElement(t2Id);
        long n1 = addElement(c, d, a);
        long n2 = addElement(d, c, b);
        addFlipEvent(new FlipEvent(nextSplitEventId(),
                List.of(t1, t2), List.of(element(n1), element(n2))));
    }

    public void addFlipEvent(FlipEvent event) {
        flipEvents.add(event);
        nextSplitEventId = Math.max(nextSplitEventId, event.id() + 1);
    }

    public void removeFlipEvent(FlipEvent event) {
        flipEvents.remove(event);
    }

    // ---------------------------------------------------------------- 快照

    public MeshSnapshot snapshot() {
        return new MeshSnapshot(
                List.copyOf(nodes.values()),
                List.copyOf(elements.values()),
                List.copyOf(boundaryEdges),
                List.copyOf(splitEvents),
                List.copyOf(flipEvents),
                nextNodeId, nextElementId, nextSplitEventId);
    }

    public static Mesh fromSnapshot(MeshSnapshot s) {
        Mesh mesh = new Mesh();
        mesh.nextNodeId = s.nextNodeId();
        mesh.nextElementId = s.nextElementId();
        mesh.nextSplitEventId = s.nextSplitEventId();
        for (MeshNode n : s.nodes()) {
            mesh.nodes.put(n.id(), n);
            mesh.nodeToElements.put(n.id(), new LinkedHashSet<>());
        }
        for (MeshElement e : s.elements()) {
            mesh.insertElement(e);
        }
        for (BoundaryEdge be : s.boundaryEdges()) {
            mesh.boundaryEdges.add(be);
            mesh.boundaryEdgeIndex.put(EdgeKey.of(be.a(), be.b()), be.boundaryId());
        }
        mesh.splitEvents.addAll(s.splitEvents());
        if (s.flipEvents() != null) {
            mesh.flipEvents.addAll(s.flipEvents());
        }
        return mesh;
    }

    // ---------------------------------------------------------------- 一致性检查

    /**
     * 检查全部内部不变量，返回问题列表（空 = 一致）。
     * 覆盖：悬挂边、边界缺失、单元朝向、退化面积、未知节点引用。
     */
    public List<String> findInconsistencies() {
        List<String> problems = new ArrayList<>();
        for (MeshElement e : elements.values()) {
            for (long v : e.vertices()) {
                if (!nodes.containsKey(v)) {
                    problems.add("element " + e.id() + " references unknown node " + v);
                }
            }
            double area = GeometryUtils.triangleArea(
                    point(e.n0()), point(e.n1()), point(e.n2()));
            if (area < GeometryUtils.MIN_ELEMENT_AREA) {
                problems.add("element " + e.id() + " degenerate, area=" + area);
            }
            if (GeometryUtils.orient(point(e.n0()), point(e.n1()), point(e.n2())) < 0) {
                problems.add("element " + e.id() + " is clockwise");
            }
        }
        for (Map.Entry<EdgeKey, List<Long>> en : edgeToElements.entrySet()) {
            int count = en.getValue().size();
            boolean boundary = boundaryEdgeIndex.containsKey(en.getKey());
            if (count > 2) {
                problems.add("edge " + en.getKey() + " shared by " + count + " elements (overlapping)");
            }
            if (boundary && count != 1) {
                problems.add("boundary edge " + en.getKey() + " adjacent to " + count + " elements");
            }
            if (!boundary && count != 2) {
                problems.add("interior edge " + en.getKey() + " adjacent to " + count
                        + " elements (hanging or free)");
            }
        }
        for (BoundaryEdge be : boundaryEdges) {
            List<Long> adjacent = edgeToElements.get(EdgeKey.of(be.a(), be.b()));
            if (adjacent == null || adjacent.size() != 1) {
                problems.add("boundary edge " + be + " not backed by exactly one element");
            }
        }
        for (Map.Entry<Long, Set<Long>> en : nodeToElements.entrySet()) {
            if (en.getValue().isEmpty()) {
                problems.add("node " + en.getKey() + " is not referenced by any element (orphan)");
            }
        }
        return problems;
    }
}
