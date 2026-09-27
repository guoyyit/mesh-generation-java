package com.github.guoyyit.meshgenerationjava.core;

/** Oriented boundary edge; marker identifies the source boundary loop (0 = outer, 1..n = holes). */
public record BoundaryEdge(int a, int b, int marker) {
}
