package com.hotvect.catboost;

import java.util.Arrays;

public enum TaskType {
    LEARN_TO_RANK("learn_to_rank"),
    REGRESSION("regression"),
    CLASSIFICATION("classification");

    private final String taskType;

    TaskType(String taskType) {
        this.taskType = taskType;
    }

    public static TaskType fromString(String taskType) {
        for (TaskType type : TaskType.values()) {
            if (type.taskType.equals(taskType)) {
                return type;
            }
        }
        throw new IllegalArgumentException("No task type found for " + taskType +" Available types:" + Arrays.toString(TaskType.values()));
    }
}
