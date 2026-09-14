package io.tokenrail.reconciliation;

import java.util.List;
import java.util.UUID;

public record ReconciliationReport(
        UUID reconciliationId,
        String deploymentId,
        int projectionVersion,
        long blockNumber,
        String blockHash,
        Long headBlock,
        String status,
        long lagBlocks,
        String detail,
        List<ReconciliationBreak> breaks, BankSnapshot.Evidence bankEvidence,
        List<ReconciliationRepository.BreakDraft> evidence) {
}
