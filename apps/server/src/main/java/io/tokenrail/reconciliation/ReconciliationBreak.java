package io.tokenrail.reconciliation;

import java.math.BigInteger;
import java.util.UUID;

public record ReconciliationBreak(
        UUID breakId,
        String type,
        String walletAddress,
        String assetAddress,
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) BigInteger onchainAmount,
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) BigInteger offchainAmount,
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) BigInteger delta,
        String resolutionStatus, UUID resolvedByReconciliationId) {
}
