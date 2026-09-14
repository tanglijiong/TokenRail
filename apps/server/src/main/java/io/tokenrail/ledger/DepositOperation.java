package io.tokenrail.ledger;

import java.math.BigInteger;
import java.util.UUID;

public record DepositOperation(
        UUID operationId,
        String deploymentId,
        String participantId,
        String walletAddress,
        String signerKeyFile,
        String businessId,
        OperationKind kind,
        BigInteger amount,
        OperationStatus status,
        String transactionHash,
        long claimVersion) {
}
