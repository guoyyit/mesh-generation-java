package com.github.guoyyit.meshgenerationjava.mesh.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 网格节点。boundaryId >= 0 表示位于对应边界上（0=外边界，1..k=内孔），-1 为内部节点。
 */
public record MeshNode(long id, double x, double y, int boundaryId) {

    public static final int INTERIOR = -1;

    @JsonIgnore
    public boolean isBoundary() {
        return boundaryId != INTERIOR;
    }
}
