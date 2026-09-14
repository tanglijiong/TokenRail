package io.tokenrail.gateway;

import java.math.BigInteger;
import java.util.UUID;

public record ChainTransaction(
        UUID transactionId,
        UUID operationId,
        String signerAddress,
        BigInteger nonce,
        String rawTransaction,
        String transactionHash,
        String status) {
}
