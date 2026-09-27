package com.github.guoyyit.meshgenerationjava.api;

import com.github.guoyyit.meshgenerationjava.task.MeshTaskService;
import com.github.guoyyit.meshgenerationjava.task.TaskStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/** Wires the task service with configurable store location, pool size and timeout. */
@Configuration
public class MeshConfig {

    @Bean(destroyMethod = "shutdown")
    public MeshTaskService meshTaskService(
            @Value("${mesh.task.store-dir:./mesh-store}") String storeDir,
            @Value("${mesh.task.worker-threads:4}") int workerThreads,
            @Value("${mesh.task.default-timeout-millis:60000}") long defaultTimeoutMillis) {
        MeshTaskService service = new MeshTaskService(
                new TaskStore(Path.of(storeDir)), workerThreads, defaultTimeoutMillis);
        service.recover(); // mark tasks interrupted by a previous stop
        return service;
    }
}
