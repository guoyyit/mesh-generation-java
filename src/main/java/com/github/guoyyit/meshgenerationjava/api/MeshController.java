package com.github.guoyyit.meshgenerationjava.api;

import com.github.guoyyit.meshgenerationjava.generator.GenerationResult;
import com.github.guoyyit.meshgenerationjava.generator.MeshGenerationOptions;
import com.github.guoyyit.meshgenerationjava.generator.MeshGenerator;
import com.github.guoyyit.meshgenerationjava.geometry.Region;
import com.github.guoyyit.meshgenerationjava.io.MeshSerializer;
import com.github.guoyyit.meshgenerationjava.quality.QualityIssue;
import com.github.guoyyit.meshgenerationjava.task.MeshTask;
import com.github.guoyyit.meshgenerationjava.task.MeshTaskService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** REST facade over generation and task services. */
@RestController
@RequestMapping("/api")
public class MeshController {

    private final MeshTaskService taskService;
    private final MeshGenerator generator = new MeshGenerator();
    private final MeshSerializer serializer = new MeshSerializer();

    public MeshController(MeshTaskService taskService) {
        this.taskService = taskService;
    }

    /** Synchronous generation: validates input, returns mesh + quality report. */
    @PostMapping("/mesh/generate")
    public GenerateResponse generate(@RequestBody GenerateRequest request) {
        GenerationResult result = generator.generate(request.region, request.optionsOrDefault());
        return new GenerateResponse(serializer.toDto(result.mesh()), result.issues(),
                result.qualitySatisfied());
    }

    @PostMapping("/tasks")
    public Map<String, String> submitTask(@RequestBody GenerateRequest request) {
        String id = taskService.submit(request.region, request.optionsOrDefault());
        return Map.of("taskId", id);
    }

    @GetMapping("/tasks/{id}")
    public MeshTask taskStatus(@PathVariable String id) {
        return taskService.getTask(id);
    }

    @GetMapping("/tasks/{id}/result")
    public MeshSerializer.MeshDto taskResult(@PathVariable String id) {
        return serializer.toDto(taskService.getResult(id));
    }

    @DeleteMapping("/tasks/{id}")
    public Map<String, Boolean> cancelTask(@PathVariable String id) {
        return Map.of("cancelled", taskService.cancel(id));
    }

    /** Synchronous/asynchronous generation request body. */
    public static class GenerateRequest {
        public Region region;
        public MeshGenerationOptions options;

        MeshGenerationOptions optionsOrDefault() {
            return options != null ? options : new MeshGenerationOptions();
        }
    }

    public record GenerateResponse(MeshSerializer.MeshDto mesh, List<QualityIssue> issues,
                                   boolean qualitySatisfied) {
    }
}
