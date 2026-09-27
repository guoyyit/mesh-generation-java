package com.github.guoyyit.meshgenerationjava.mesh.model;

import java.util.List;

/**
 * 一次内部边 Delaunay 翻边事件（可逆）：移除两个单元、沿另一对角线新增两个单元。
 */
public record FlipEvent(long id, List<MeshElement> removedElements, List<MeshElement> addedElements) {
}
