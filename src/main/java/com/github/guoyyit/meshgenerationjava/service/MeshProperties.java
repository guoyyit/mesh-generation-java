package com.github.guoyyit.meshgenerationjava.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 可配置项（前缀 mesh.*）：
 * <ul>
 *   <li>mesh.store-dir：任务与结果文件存储目录</li>
 *   <li>mesh.worker-threads：并发生成线程池大小</li>
 *   <li>mesh.task-timeout：单任务默认超时（0 表示不限制）</li>
 *   <li>mesh.auto-resume：进程重启后是否自动恢复未完成任务</li>
 *   <li>mesh.max-elements：单任务单元数硬上限</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "mesh")
public class MeshProperties {

    private String storeDir = "./mesh-store";
    private int workerThreads = 4;
    private Duration taskTimeout = Duration.ofMinutes(5);
    private boolean autoResume = true;
    private int maxElements = 100_000;

    public String getStoreDir() {
        return storeDir;
    }

    public void setStoreDir(String storeDir) {
        this.storeDir = storeDir;
    }

    public int getWorkerThreads() {
        return workerThreads;
    }

    public void setWorkerThreads(int workerThreads) {
        this.workerThreads = workerThreads;
    }

    public Duration getTaskTimeout() {
        return taskTimeout;
    }

    public void setTaskTimeout(Duration taskTimeout) {
        this.taskTimeout = taskTimeout;
    }

    public boolean isAutoResume() {
        return autoResume;
    }

    public void setAutoResume(boolean autoResume) {
        this.autoResume = autoResume;
    }

    public int getMaxElements() {
        return maxElements;
    }

    public void setMaxElements(int maxElements) {
        this.maxElements = maxElements;
    }
}
