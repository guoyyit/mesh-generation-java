package com.github.guoyyit.meshgenerationjava.mesh.model;

import java.util.List;

/**
 * 一次二分细分事件：记录被移除/新增的单元与边界边，
 * 使粗化可以严格逆序回滚，保证拓扑一致性。
 */
public record SplitEvent(
        long id,
        List<MeshElement> removedElements,
        List<MeshElement> addedElements,
        long midNodeId,
        List<BoundaryEdge> removedBoundaryEdges,
        List<BoundaryEdge> addedBoundaryEdges) {
}
