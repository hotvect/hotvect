package com.eshioji.hotvect.api.data.regression;

public class Example<RECORD> {
    private final RECORD record;
    private final double target;

    public Example(RECORD record, double target) {
        this.record = record;
        this.target = target;
    }

    public RECORD getRecord() {
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
