package io.tokenrail.projection;

public record Asset(String address, String symbol, int decimals, long deploymentBlock) {
}
