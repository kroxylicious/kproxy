/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.kms.provider.aws.kms.model;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ScheduleKeyDeletionResponse(@JsonProperty(value = "KeyState") String keyState,
                                          @JsonProperty(value = "PendingWindowInDays") int pendingWindowInDays) {

    public ScheduleKeyDeletionResponse {
        Objects.requireNonNull(keyState);
    }
}
