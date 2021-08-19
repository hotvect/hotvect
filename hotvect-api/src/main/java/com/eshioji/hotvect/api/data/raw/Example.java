package com.eshioji.hotvect.api.data.raw;

public class Example<R> {
    private final R record;
    private final double target;

    public Example(R record, double target) {
        this.record = record;
        this.target = target;
    }

    public R getRecord() {
        return record;
    }

    public double getTarget() {
        return target;
    }
}
