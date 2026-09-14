package io.tokenrail.projection;

public record Checkpoint(
        String deploymentId,
        int projectionVersion,
        long blockNumber,
        String blockHash,
        String status) {
}
