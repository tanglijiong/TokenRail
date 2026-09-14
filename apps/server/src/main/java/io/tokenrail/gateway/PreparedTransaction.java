package io.tokenrail.gateway;

public record PreparedTransaction(String rawTransaction, String transactionHash) {
}
