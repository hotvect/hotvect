package com.hotvect.serve;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
final class ServeController {
    private final ServeApplication runtime;

    ServeController(ServeApplication runtime) {
        this.runtime = runtime;
    }

    @GetMapping("/health")
    Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @GetMapping("/api/metadata")
    ObjectNode metadata() {
        return runtime.buildMetadata();
    }
}
