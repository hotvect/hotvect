// Generated EMS client binding. Do not edit manually.
package com.hotvect.onlineutils.experimentmanagement.generated;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Generated contract for the EMS serving-state read operation. */
public final class GetDefaultVariantAndActiveExperimentsOperation {
    public static final String HTTP_METHOD = "GET";
    public static final Class<SlotActiveInfo> RESPONSE_TYPE = SlotActiveInfo.class;
    private static final String PATH_TEMPLATE = "slots/{slotName}/defaultVariantAndActiveExperiments";

    private GetDefaultVariantAndActiveExperimentsOperation() {
    }

    /** Resolves the exact operation URI for one slot name. */
    public static URI resolve(URI baseUri, String slotName) {
        String encodedSlotName = URLEncoder.encode(
                Objects.requireNonNull(slotName, "slotName must not be null"),
                StandardCharsets.UTF_8).replace("+", "%20");
        String path = PATH_TEMPLATE.replace("{slotName}", encodedSlotName);
        String normalizedBase = Objects.requireNonNull(baseUri, "baseUri must not be null").toString();
        if (!normalizedBase.endsWith("/")) {
            normalizedBase += "/";
        }
        return URI.create(normalizedBase + path);
    }
}
