package com.github.guoyyit.meshgenerationjava.core;

/** Triangular element, nodes ordered counter-clockwise. Immutable except liveness. */
public class Element {

    private final int id;
    private final int n0;
    private final int n1;
    private final int n2;
    private boolean alive = true;

    public Element(int id, int n0, int n1, int n2) {
        this.id = id;
        this.n0 = n0;
        this.n1 = n1;
        this.n2 = n2;
    }

    public int getId() {
        return id;
    }

    public int getN0() {
        return n0;
    }

    public int getN1() {
        return n1;
    }

    public int getN2() {
        return n2;
    }

    public boolean isAlive() {
        return alive;
    }

    public void kill() {
        this.alive = false;
    }

    /** Node id at local corner 0,1,2. */
    public int node(int local) {
        return switch (local) {
            case 0 -> n0;
            case 1 -> n1;
            default -> n2;
        };
    }

    public boolean contains(int nodeId) {
        return n0 == nodeId || n1 == nodeId || n2 == nodeId;
    }
}
