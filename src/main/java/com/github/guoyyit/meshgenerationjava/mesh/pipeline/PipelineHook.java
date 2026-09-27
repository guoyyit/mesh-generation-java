package com.github.guoyyit.meshgenerationjava.mesh.pipeline;

import com.github.guoyyit.meshgenerationjava.mesh.model.Mesh;

import java.util.concurrent.CancellationException;

/**
 * 管线回调：取消/超时检查与步骤边界检查点落盘。
 * 每个步骤之间都会调用，保证取消及时生效且恢复粒度为完整步骤。
 */
public interface PipelineHook {

    /** 已请求取消或超时则抛出 CancellationException，绝不留下半成品结果。 */
    void checkCancelled() throws CancellationException;

    /**
     * 一个完整步骤结束：三角化完成时 step=-1；之后每完成一轮细化 step 递增。
     */
    void afterStep(int step, Mesh mesh);

    /** 默认空钩子。 */
    PipelineHook NOOP = new PipelineHook() {
        @Override
        public void checkCancelled() {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("interrupted");
            }
        }

        @Override
        public void afterStep(int step, Mesh mesh) {
        }
    };
}
