package com.github.guoyyit.meshgenerationjava.service;

/**
 * 任务生命周期。COMPLETED 为唯一"已提交结果可读"状态；
 * 其余终态（FAILED/CANCELLED/TIMEOUT/INTERRUPTED）均无结果文件。
 */
public enum TaskStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    TIMEOUT,
    INTERRUPTED;

    public boolean isTerminal() {
        return this != PENDING && this != RUNNING;
    }
}
