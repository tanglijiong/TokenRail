package io.tokenrail.projection;

import java.math.BigInteger;
import java.util.List;

public record TransferLog(
        long blockNumber,
        String blockHash,
        String transactionHash,
        long transactionIndex,
        long logIndex,
        String contractAddress,
        List<String> topics,
        String data,
        String from,
        String to,
        BigInteger amount) {
}
