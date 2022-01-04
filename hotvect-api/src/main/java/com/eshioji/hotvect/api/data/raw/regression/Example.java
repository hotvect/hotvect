package com.eshioji.hotvect.api.data.raw.regression;

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

    @Override
    public String toString() {
        return "Example{" +
                "record=" + record +
                ", target=" + target +
                '}';
    }
}
