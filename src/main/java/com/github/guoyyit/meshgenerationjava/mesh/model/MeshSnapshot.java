package com.github.guoyyit.meshgenerationjava.mesh.model;

import java.util.List;

/**
 * 网格的不可变快照，用于持久化（检查点/最终结果）与跨线程传递。
 */
public record MeshSnapshot(
        List<MeshNode> nodes,
        List<MeshElement> elements,
        List<BoundaryEdge> boundaryEdges,
        List<SplitEvent> splitEvents,
        List<FlipEvent> flipEvents,
        long nextNodeId,
        long nextElementId,
        long nextSplitEventId) {
}
