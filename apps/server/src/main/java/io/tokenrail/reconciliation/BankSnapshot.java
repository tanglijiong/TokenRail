package io.tokenrail.reconciliation;

import java.math.BigInteger;
import java.util.List;

/** Values captured in the same repeatable-read transaction as the projection snapshot. */
public record BankSnapshot(@com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) BigInteger booked, List<Posting> operations) {
    public record Posting(String transactionHash, String kind, @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) BigInteger amount, boolean posted) { }
    public record Evidence(@com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) BigInteger booked, @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) BigInteger futurePostings, @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) BigInteger pendingMints, @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) BigInteger pendingBurns) {
        public @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) BigInteger atBlock() { return booked.subtract(futurePostings).add(pendingMints).subtract(pendingBurns); }
    }
}
