package com.jingyicare.jingyi_icis_engine.utils;

public class Pair<L, R> {
    public Pair(L left, R right) {
        this.left = left;
        this.right = right;
    }

    public L getFirst() {
        return left;
    }

    public R getSecond() {
        return right;
    }

    private final L left;
    private final R right;
}