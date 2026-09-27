package com.github.guoyyit.meshgenerationjava.web;

import com.github.guoyyit.meshgenerationjava.mesh.pipeline.GenerationResult;
import com.github.guoyyit.meshgenerationjava.service.MeshProperties;
import com.github.guoyyit.meshgenerationjava.service.MeshTaskManager;
import com.github.guoyyit.meshgenerationjava.web.dto.CoarsenRequest;
import com.github.guoyyit.meshgenerationjava.web.dto.CreateTaskRequest;
import com.github.guoyyit.meshgenerationjava.web.dto.RefineRequest;
import com.github.guoyyit.meshgenerationjava.web.dto.TaskStatusResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 网格生成 REST API。
 */
@RestController
@RequestMapping("/api/mesh/tasks")
public class MeshController {

    private final MeshTaskManager manager;
    private final MeshProperties properties;

    public MeshController(MeshTaskManager manager, MeshProperties properties) {
        this.manager = manager;
        this.properties = properties;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> create(@RequestBody CreateTaskRequest request) {
        String id = manager.submit(request.toGenerationRequest(properties.getMaxElements()));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("taskId", id, "status", "PENDING"));
    }

    @GetMapping
    public List<TaskStatusResponse> list() {
        return manager.list().stream().map(TaskStatusResponse::from).toList();
    }

    @GetMapping("/{id}")
    public TaskStatusResponse status(@PathVariable String id) {
        return TaskStatusResponse.from(manager.status(id));
    }

    @GetMapping("/{id}/result")
    public GenerationResult result(@PathVariable String id) {
        return manager.result(id);
    }

    @PostMapping("/{id}/cancel")
    public TaskStatusResponse cancel(@PathVariable String id) {
        manager.cancel(id);
        return TaskStatusResponse.from(manager.status(id));
    }

    @PostMapping("/{id}/refine")
    public GenerationResult refine(@PathVariable String id, @RequestBody RefineRequest request) {
        return manager.refine(id, request.ids(), request.levelCount());
    }

    @PostMapping("/{id}/coarsen")
    public GenerationResult coarsen(@PathVariable String id,
                                    @RequestBody(required = false) CoarsenRequest request) {
        return manager.coarsen(id, request == null ? null : request.elementIds());
    }
}
