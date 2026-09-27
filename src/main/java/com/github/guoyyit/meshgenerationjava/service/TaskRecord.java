package com.github.guoyyit.meshgenerationjava.service;

import com.github.guoyyit.meshgenerationjava.mesh.pipeline.GenerationRequest;

import java.time.Instant;

/**
 * 任务元数据（持久化到 task.json）。currentStep 记录最近完成的细化轮次，
 * 与检查点文件一起支撑"不重复、不跳过"的恢复语义。
 */
public record TaskRecord(
        String id,
        TaskStatus status,
        GenerationRequest request,
        int currentStep,
        String errorCode,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt) {

    public TaskRecord withStatus(TaskStatus newStatus, int step, String code, String message) {
        return new TaskRecord(id, newStatus, request, step, code, message, createdAt, Instant.now());
    }
}
