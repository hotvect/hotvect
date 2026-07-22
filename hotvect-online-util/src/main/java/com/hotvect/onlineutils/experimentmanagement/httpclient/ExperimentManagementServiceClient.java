package com.hotvect.onlineutils.experimentmanagement.httpclient;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.net.HttpHeaders;
import com.hotvect.onlineutils.experimentmanagement.models.Slot;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP client for the existing EMS read API.
 * <p>
 * The provided base URI is the EMS service root. Slot-specific request paths are built
 * internally as {@code /slots/{slotName}/defaultVariantAndActiveExperiments}.
 */
public class ExperimentManagementServiceClient implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ExperimentManagementServiceClient.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .setSerializationInclusion(Include.NON_NULL);

    private final HttpClient client;
    private final Supplier<String> tokenSupplier;
    private final URI emsBaseUri;
    private final Duration readTimeout;

    public ExperimentManagementServiceClient(
            final URI emsBaseUri,
            final Duration connectTimeout,
            final Duration readTimeout,
            final Supplier<String> tokenSupplier) {
        this(
                emsBaseUri,
                readTimeout,
                tokenSupplier,
                HttpClient.newBuilder()
                        .connectTimeout(Objects.requireNonNull(connectTimeout, "connectTimeout must not be null"))
                        .build()
        );
    }

    ExperimentManagementServiceClient(
            final URI emsBaseUri,
            final Duration readTimeout,
            final Supplier<String> tokenSupplier,
            final HttpClient client) {
        this.emsBaseUri = Objects.requireNonNull(emsBaseUri, "emsBaseUri must not be null");
        this.readTimeout = Objects.requireNonNull(readTimeout, "readTimeout must not be null");
        this.tokenSupplier = Objects.requireNonNull(tokenSupplier, "tokenSupplier must not be null");
        this.client = Objects.requireNonNull(client, "client must not be null");
    }

    /**
     * Fetches the current default variant and active experiments payload for one slot.
     */
    public Slot getDefaultVariantAndActiveExperiments(final String slotName) throws IOException, InterruptedException {
        final HttpResponse<String> response =
                client.send(buildRequest(slotName), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Call to EMS failed with error message: " + response.body());
        }
        final Slot slot = OBJECT_MAPPER.readValue(response.body(), Slot.class);
        LOG.info(
                "Fetched EMS state for slot {}: totalNumberOfShards={}, experiments={}, userForcedAssignments={}",
                slotName,
                slot.totalNumberOfShards(),
                slot.experiments().size(),
                slot.userForcedAssignments().size());
        return slot;
    }

    private HttpRequest buildRequest(final String slotName) {
        final HttpRequest.Builder builder = HttpRequest.newBuilder()
                .GET()
                .uri(buildSlotUri(slotName))
                .timeout(readTimeout)
                .header("Accept", "application/json");
        final String token = tokenSupplier.get();
        if (token != null && !token.isBlank()) {
            builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return builder.build();
    }

    private URI buildSlotUri(final String slotName) {
        final String encodedSlotName = URLEncoder.encode(
                Objects.requireNonNull(slotName, "slotName must not be null"),
                StandardCharsets.UTF_8).replace("+", "%20");
        String normalizedBaseUri = emsBaseUri.toString();
        if (!normalizedBaseUri.endsWith("/")) {
            normalizedBaseUri += "/";
        }
        return URI.create(normalizedBaseUri + "slots/" + encodedSlotName + "/defaultVariantAndActiveExperiments");
    }

    static ObjectMapper objectMapper() {
        return OBJECT_MAPPER;
    }

    @Override
    public void close() {
        client.close();
    }
}
