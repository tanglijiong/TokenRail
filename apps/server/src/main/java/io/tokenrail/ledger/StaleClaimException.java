package io.tokenrail.ledger;

public class StaleClaimException extends RuntimeException {
    public StaleClaimException() { super("Workflow lease expired or was acquired by another worker"); }
}
