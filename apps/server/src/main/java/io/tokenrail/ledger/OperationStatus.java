package io.tokenrail.ledger;

public enum OperationStatus {
    QUEUED,
    SUBMITTED,
    SUCCEEDED,
    RESERVED,
    MINT_PENDING,
    COMPLETED,
    BURN_PENDING,
    BURN_CONFIRMED,
    CREDITED,
    REVERTED
}
