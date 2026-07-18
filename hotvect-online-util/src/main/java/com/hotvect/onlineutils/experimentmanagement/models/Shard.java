package com.hotvect.onlineutils.experimentmanagement.models;

import java.time.Instant;

public record Shard(
        int shardId,
        Instant createdAt) {
}
