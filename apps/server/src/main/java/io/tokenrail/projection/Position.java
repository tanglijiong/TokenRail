package io.tokenrail.projection;

import java.math.BigInteger;

public record Position(
        String walletAddress,
        String assetAddress,
        String symbol,
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) BigInteger balance,
        long blockNumber,
        String blockHash,
        int projectionVersion) {
}
