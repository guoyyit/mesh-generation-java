package com.github.guoyyit.meshgenerationjava.mesh.model;

/**
 * 边界边：a->b 为沿边界正向（外边界 CCW，内孔 CW）的有向边。
 */
public record BoundaryEdge(long a, long b, int boundaryId) {
}
