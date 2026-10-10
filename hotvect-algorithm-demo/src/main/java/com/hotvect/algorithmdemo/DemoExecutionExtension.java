package com.hotvect.algorithmdemo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hotvect.serve.ContractViolationException;
import com.hotvect.serve.JsonFieldSupport;
import com.hotvect.serve.RequestBodyTooLargeException;
import com.hotvect.serve.ServerExtension;
import com.hotvect.serve.ServeApplication;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/** Headless offline-example endpoint used by the local algorithm demo command. */
@RestController
final class DemoExecutionExtension implements ServerExtension {
    private static final Logger log = LoggerFactory.getLogger(DemoExecutionExtension.class);
    private static final ObjectMapper OM = new ObjectMapper();

    private final ActionMetadataLookup actionMetadata;
    private OfflineExampleExecutor offlineExamples;
    private ServeApplication runtime;

    DemoExecutionExtension(ActionMetadataLookup actionMetadata) {
        this.actionMetadata = Objects.requireNonNull(actionMetadata);
    }

    @Override
    public void initialize(ServeApplication runtime) {
        this.runtime = Objects.requireNonNull(runtime);
        offlineExamples = new OfflineExampleExecutor(runtime, actionMetadata);
    }

    @PostMapping("/predict")
    ResponseEntity<JsonNode> predict(
            HttpServletRequest request,
            @RequestParam(name = "algorithm_runtime_id", required = false) String rawAlgorithmRuntimeId) {
        ObjectNode exampleNode;
        try {
            JsonNode body = OM.readTree(runtime.readRequestBodyBytes(request));
            if (!(body instanceof ObjectNode objectNode)) {
                return response(
                        HttpStatus.BAD_REQUEST,
                        ServeApplication.error("Request body must be a JSON object", null));
            }
            exampleNode = objectNode.deepCopy();
        } catch (RequestBodyTooLargeException error) {
            return response(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    ServeApplication.error(error.getMessage(), error.getDetails()));
        } catch (Exception error) {
            return response(
                    HttpStatus.BAD_REQUEST,
                    ServeApplication.error("Invalid JSON request body", error.getMessage()));
        }

        String algorithmRuntimeId = JsonFieldSupport.blankToNull(rawAlgorithmRuntimeId);
        try {
            String expectedExampleId = JsonFieldSupport
                    .nonEmptyStringField(exampleNode, "example_id")
                    .orElse(null);
            JsonInStringSupport.collapseVirtualJsonFields(exampleNode);
            return response(HttpStatus.OK, offlineExamples.runExample(
                    exampleNode,
                    expectedExampleId,
                    algorithmRuntimeId));
        } catch (ContractViolationException error) {
            return response(
                    HttpStatus.BAD_REQUEST,
                    ServeApplication.error(error.getMessage(), error.getDetails()));
        } catch (Exception error) {
            log.error("Unhandled demo execution error", error);
            return response(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    ServeApplication.error("Unhandled error", error.getMessage()));
        }
    }

    private static ResponseEntity<JsonNode> response(HttpStatus status, JsonNode body) {
        return ResponseEntity.status(status).body(body);
    }
}
