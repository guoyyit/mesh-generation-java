package com.github.guoyyit.meshgenerationjava.mesh.refine;

import com.github.guoyyit.meshgenerationjava.mesh.model.BoundaryEdge;
import com.github.guoyyit.meshgenerationjava.mesh.model.FlipEvent;
import com.github.guoyyit.meshgenerationjava.mesh.model.Mesh;
import com.github.guoyyit.meshgenerationjava.mesh.model.MeshElement;
import com.github.guoyyit.meshgenerationjava.mesh.model.SplitEvent;
import com.github.guoyyit.meshgenerationjava.mesh.quality.ElementQuality;
import com.github.guoyyit.meshgenerationjava.mesh.quality.QualityChecker;
import com.github.guoyyit.meshgenerationjava.mesh.quality.QualityThresholds;
import com.github.guoyyit.meshgenerationjava.mesh.quality.QualityViolation;
import com.github.guoyyit.meshgenerationjava.mesh.triangulate.DelaunayFlipper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 网格粗化：把细分（{@link SplitEvent}）与翻边（{@link FlipEvent}）视为统一操作日志，
 * 严格按操作 id 逆序回滚。
 * <ul>
 *   <li>只回滚子单元仍全部存在的事件（其后代/后续操作必须先回滚）；</li>
 *   <li>细分中点必须仅被该事件产生的单元引用，避免误删共享节点；</li>
 *   <li>恢复引入的旧边不得与现有边冲突（防止重叠单元）；</li>
 *   <li>边界半边仍在边界表中，回滚后边界几何保持不变（三点共线）；</li>
 *   <li>恢复的单元必须通过质量阈值，否则拒绝回滚（阈值真实生效）。</li>
 * </ul>
 */
public final class MeshCoarsener {

    private MeshCoarsener() {
    }

    private sealed interface Op permits SplitOp, FlipOp {
        long id();
    }

    private record SplitOp(SplitEvent event) implements Op {
        @Override
        public long id() {
            return event.id();
        }
    }

    private record FlipOp(FlipEvent event) implements Op {
        @Override
        public long id() {
            return event.id();
        }
    }

    /**
     * 粗化与给定单元集合相关的可回滚操作。
     *
     * @param requestedIds 待粗化单元；null 表示整网；协同事件会一并纳入闭包
     * @param guard        回滚后单元必须满足的质量阈值
     * @return 实际回滚的操作数
     */
    public static int coarsen(Mesh mesh, Collection<Long> requestedIds, QualityThresholds guard) {
        Set<Long> closure = requestedIds == null ? null : expandCluster(mesh, new TreeSet<>(requestedIds));
        int reverted = 0;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Op op : opsDescending(mesh)) {
                if (op instanceof SplitOp split && canRevertSplit(mesh, split.event(), closure, guard)) {
                    revertSplit(mesh, split.event());
                    reverted++;
                    changed = true;
                } else if (op instanceof FlipOp flip
                        && canRevertFlip(mesh, flip.event(), closure, guard)) {
                    DelaunayFlipper.revert(mesh, flip.event());
                    reverted++;
                    changed = true;
                }
                if (changed) {
                    if (closure != null) {
                        closure = expandCluster(mesh, closure);
                    }
                    break;
                }
            }
        }
        return reverted;
    }

    private static List<Op> opsDescending(Mesh mesh) {
        List<Op> ops = new ArrayList<>();
        mesh.splitEvents().forEach(e -> ops.add(new SplitOp(e)));
        mesh.flipEvents().forEach(e -> ops.add(new FlipOp(e)));
        ops.sort(Comparator.comparingLong(Op::id).reversed());
        return ops;
    }

    /** 把"与目标单元同属一次协同操作"的单元纳入闭包。 */
    private static Set<Long> expandCluster(Mesh mesh, Set<Long> seeds) {
        Set<Long> closure = new TreeSet<>(seeds);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (SplitEvent event : mesh.splitEvents()) {
                if (touchesAny(event.addedElements(), closure)) {
                    for (MeshElement added : event.addedElements()) {
                        if (closure.add(added.id())) {
                            changed = true;
                        }
                    }
                }
            }
        }
        return closure;
    }

    private static boolean touchesAny(List<MeshElement> elements, Set<Long> ids) {
        for (MeshElement e : elements) {
            if (ids.contains(e.id())) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- 细分回滚

    private static boolean canRevertSplit(Mesh mesh, SplitEvent event,
                                          Set<Long> closure, QualityThresholds guard) {
        for (MeshElement added : event.addedElements()) {
            if (!mesh.hasElement(added.id())) {
                return false;
            }
        }
        if (closure != null && !closure.containsAll(addedIds(event))) {
            return false;
        }
        Set<Long> addedIds = addedIds(event);
        if (!addedIds.equals(mesh.elementsOfNode(event.midNodeId()))) {
            return false; // 中点被后续操作引用
        }
        for (BoundaryEdge half : event.addedBoundaryEdges()) {
            if (mesh.boundaryIdOfEdge(half.a(), half.b()) != half.boundaryId()) {
                return false;
            }
        }
        // 恢复的旧边不得与现有边冲突
        long[] splitEdge = splitEdgeOf(event);
        if (splitEdge != null
                && (mesh.neighborAcross(splitEdge[0], splitEdge[1], -1L) != null
                || mesh.boundaryIdOfEdge(splitEdge[0], splitEdge[1]) != -1)) {
            return false;
        }
        return restoredPass(mesh, event.removedElements(), guard);
    }

    /** 被二分的边：两个被移除单元的公共边；边界二分时取被移除边界边。 */
    private static long[] splitEdgeOf(SplitEvent event) {
        if (event.removedElements().size() == 2) {
            Set<Long> common = new HashSet<>();
            for (long v : event.removedElements().get(0).vertices()) {
                if (event.removedElements().get(1).containsNode(v)) {
                    common.add(v);
                }
            }
            if (common.size() == 2) {
                Long[] arr = common.toArray(new Long[0]);
                return new long[]{arr[0], arr[1]};
            }
            return null;
        }
        if (!event.removedBoundaryEdges().isEmpty()) {
            BoundaryEdge be = event.removedBoundaryEdges().get(0);
            return new long[]{be.a(), be.b()};
        }
        return null;
    }

    private static void revertSplit(Mesh mesh, SplitEvent event) {
        for (MeshElement added : event.addedElements()) {
            mesh.removeElement(added.id());
        }
        for (MeshElement parent : event.removedElements()) {
            mesh.restoreElement(parent);
        }
        if (!event.removedBoundaryEdges().isEmpty()) {
            BoundaryEdge restored = event.removedBoundaryEdges().get(0);
            BoundaryEdge first = event.addedBoundaryEdges().get(0);
            BoundaryEdge second = event.addedBoundaryEdges().get(1);
            mesh.mergeBoundaryEdges(restored, first, second);
        }
        mesh.removeNode(event.midNodeId());
        mesh.removeSplitEvent(event);
    }

    // ---------------------------------------------------------------- 翻边回滚

    private static boolean canRevertFlip(Mesh mesh, FlipEvent event,
                                         Set<Long> closure, QualityThresholds guard) {
        for (MeshElement added : event.addedElements()) {
            if (!mesh.hasElement(added.id())) {
                return false;
            }
        }
        if (closure != null) {
            Set<Long> ids = new HashSet<>();
            event.addedElements().forEach(e -> ids.add(e.id()));
            if (!closure.containsAll(ids)) {
                return false;
            }
        }
        // 恢复的原对角线不得与现有边冲突
        long[] diagonal = sharedEdge(event.removedElements().get(0),
                event.removedElements().get(1));
        if (diagonal != null
                && (mesh.neighborAcross(diagonal[0], diagonal[1], -1L) != null
                || mesh.boundaryIdOfEdge(diagonal[0], diagonal[1]) != -1)) {
            return false;
        }
        return restoredPass(mesh, event.removedElements(), guard);
    }

    private static long[] sharedEdge(MeshElement x, MeshElement y) {
        List<Long> common = new ArrayList<>();
        for (long v : x.vertices()) {
            if (y.containsNode(v)) {
                common.add(v);
            }
        }
        return common.size() == 2 ? new long[]{common.get(0), common.get(1)} : null;
    }

    // ---------------------------------------------------------------- 公共

    private static Set<Long> addedIds(SplitEvent event) {
        Set<Long> ids = new HashSet<>();
        for (MeshElement added : event.addedElements()) {
            ids.add(added.id());
        }
        return ids;
    }

    private static boolean restoredPass(Mesh mesh, List<MeshElement> restored,
                                        QualityThresholds guard) {
        for (MeshElement parent : restored) {
            ElementQuality q = QualityChecker.measure(mesh, parent);
            List<QualityViolation> violations = QualityChecker.check(q, guard);
            if (!violations.isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
