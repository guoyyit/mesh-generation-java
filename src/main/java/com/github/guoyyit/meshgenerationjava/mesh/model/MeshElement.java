package com.github.guoyyit.meshgenerationjava.mesh.model;

/**
 * 三角形单元，顶点恒为逆时针（CCW）朝向。构造时不做检查，
 * 朝向不变量由 {@link Mesh#addElement} 统一维护。
 */
public record MeshElement(long id, long n0, long n1, long n2) {

    public long[] vertices() {
        return new long[]{n0, n1, n2};
    }

    public boolean containsNode(long nodeId) {
        return n0 == nodeId || n1 == nodeId || n2 == nodeId;
    }
}
