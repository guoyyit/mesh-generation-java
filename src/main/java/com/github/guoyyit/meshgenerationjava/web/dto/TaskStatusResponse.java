package com.github.guoyyit.meshgenerationjava.web.dto;

import com.github.guoyyit.meshgenerationjava.service.TaskRecord;

import java.time.Instant;

/**
 * 任务状态视图（不含请求体细节）。
 */
public record TaskStatusResponse(
        String id,
        String status,
        int currentStep,
        String errorCode,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt) {

    public static TaskStatusResponse from(TaskRecord record) {
        return new TaskStatusResponse(record.id(), record.status().name(),
                record.currentStep(), record.errorCode(), record.errorMessage(),
                record.createdAt(), record.updatedAt());
    }
}
