package io.tokenrail.reconciliation;

import io.tokenrail.api.NotFoundException;
import io.tokenrail.projection.Checkpoint;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class ReconciliationRepository {
    public record BreakDraft(
            String type,
            String walletAddress,
            String assetAddress,
            @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) BigInteger onchainAmount,
            @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) BigInteger offchainAmount,
            @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) BigInteger delta) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public ReconciliationRepository(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
    }

    public ReconciliationReport save(
            Checkpoint checkpoint,
            Long headBlock,
            String status,
            long lagBlocks,
            String detail,
            List<BreakDraft> drafts) {
        return save(null, checkpoint, headBlock, status, lagBlocks, detail, drafts);
    }

    public ReconciliationReport save(UUID reportId, Checkpoint checkpoint, Long headBlock, String status,
            long lagBlocks, String detail, List<BreakDraft> drafts) {
        return saveInternal(checkpoint, headBlock, status, lagBlocks, detail, drafts, List.of(), null, reportId);
    }

    public ReconciliationReport saveVerified(Checkpoint checkpoint, Long head, String status,
            List<BreakDraft> checks, BankSnapshot.Evidence bank, UUID reportId) {
        return saveInternal(checkpoint, head, status, 0, "Fixed-block reconciliation with recorded bank evidence",
                checks.stream().filter(c -> c.delta().signum() != 0).toList(), checks, bank, reportId);
    }

    private ReconciliationReport saveInternal(Checkpoint checkpoint, Long headBlock, String status,
            long lagBlocks, String detail, List<BreakDraft> drafts, List<BreakDraft> checks, BankSnapshot.Evidence bank, UUID requestedId) {
        UUID reportId = requestedId == null ? UUID.randomUUID() : requestedId;
        transactions.executeWithoutResult(ignored -> {
            jdbc.execute("select pg_advisory_xact_lock(" + (long) reportId.hashCode() + ")");
            if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from reconciliation_run where reconciliation_id = ?)", Boolean.class, reportId))) return;
            jdbc.update("""
                    insert into reconciliation_run(reconciliation_id, deployment_id,
                        projection_version, block_number, block_hash, head_block,
                        status, lag_blocks, detail) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, reportId, checkpoint.deploymentId(), checkpoint.projectionVersion(),
                    checkpoint.blockNumber(), checkpoint.blockHash(), headBlock,
                    status, lagBlocks, detail);
            for (BreakDraft draft : drafts) {
                jdbc.update("""
                        insert into reconciliation_break(break_id, reconciliation_id, break_type,
                            wallet_address, asset_address, onchain_amount, offchain_amount, delta)
                        values (?, ?, ?, ?, ?, ?, ?, ?)
                        """, UUID.randomUUID(), reportId, draft.type(), draft.walletAddress(),
                        draft.assetAddress(), decimal(draft.onchainAmount()),
                        decimal(draft.offchainAmount()), decimal(draft.delta()));
            }
            if (bank != null) jdbc.update("""
                    update reconciliation_run set booked_backing = ?, future_postings = ?, pending_mints = ?, pending_burns = ?
                    where reconciliation_id = ?
                    """, decimal(bank.booked()), decimal(bank.futurePostings()), decimal(bank.pendingMints()), decimal(bank.pendingBurns()), reportId);
            for (BreakDraft check : checks) {
                jdbc.update("insert into reconciliation_evidence values (?, ?, ?, ?, ?, ?, ?)", UUID.randomUUID(), reportId,
                        check.type(), check.walletAddress(), check.assetAddress(), decimal(check.onchainAmount()), decimal(check.offchainAmount()));
                if (check.delta().signum() == 0) jdbc.update("""
                        update reconciliation_break b set resolution_status = 'RESOLVED', resolved_by_reconciliation_id = ?
                        from reconciliation_run r where b.reconciliation_id = r.reconciliation_id
                          and r.deployment_id = ? and r.block_number = ? and r.block_hash = ?
                          and b.break_type = ? and b.wallet_address is not distinct from cast(? as varchar)
                          and b.asset_address = ? and b.resolution_status = 'OPEN'
                        """, reportId, checkpoint.deploymentId(), checkpoint.blockNumber(), checkpoint.blockHash(),
                        check.type(), check.walletAddress(), check.assetAddress());
            }
        });
        return get(reportId);
    }

    public ReconciliationReport get(UUID reportId) {
        List<ReconciliationReport> reports = jdbc.query("""
                select reconciliation_id, deployment_id, projection_version, block_number,
                    block_hash, head_block, status, lag_blocks, detail
                from reconciliation_run where reconciliation_id = ?
                """, (result, row) -> new ReconciliationReport(
                result.getObject(1, UUID.class), result.getString(2), result.getInt(3),
                result.getLong(4), result.getString(5),
                result.getObject(6) == null ? null : result.getLong(6), result.getString(7),
                result.getLong(8), result.getString(9), List.of(), null, List.of()), reportId);
        if (reports.isEmpty()) {
            throw new NotFoundException("Reconciliation report not found");
        }
        ReconciliationReport report = reports.getFirst();
        return new ReconciliationReport(
                report.reconciliationId(), report.deploymentId(), report.projectionVersion(),
                report.blockNumber(), report.blockHash(), report.headBlock(), report.status(),
                report.lagBlocks(), report.detail(), breaks(reportId), bankEvidence(reportId), evidence(reportId));
    }

    public List<ReconciliationBreak> breaks(UUID reportId) {
        return jdbc.query("""
                select break_id, break_type, wallet_address, asset_address,
                    onchain_amount, offchain_amount, delta, resolution_status, resolved_by_reconciliation_id
                from reconciliation_break where reconciliation_id = ? order by break_id
                """, (result, row) -> new ReconciliationBreak(
                result.getObject(1, UUID.class), result.getString(2), result.getString(3),
                result.getString(4), integer(result.getBigDecimal(5)),
                integer(result.getBigDecimal(6)), integer(result.getBigDecimal(7)),
                result.getString(8), result.getObject(9, UUID.class)), reportId);
    }

    private BankSnapshot.Evidence bankEvidence(UUID id) {
        return jdbc.query("select booked_backing, future_postings, pending_mints, pending_burns from reconciliation_run where reconciliation_id = ?",
                (r, n) -> r.getBigDecimal(1) == null ? null : new BankSnapshot.Evidence(integer(r.getBigDecimal(1)),
                        integer(r.getBigDecimal(2)), integer(r.getBigDecimal(3)), integer(r.getBigDecimal(4))), id).getFirst();
    }

    private List<BreakDraft> evidence(UUID id) {
        return jdbc.query("select check_type, wallet_address, asset_address, onchain_amount, offchain_amount from reconciliation_evidence where reconciliation_id = ? order by check_type, asset_address, wallet_address",
                (r, n) -> new BreakDraft(r.getString(1), r.getString(2), r.getString(3), integer(r.getBigDecimal(4)),
                        integer(r.getBigDecimal(5)), integer(r.getBigDecimal(4)).subtract(integer(r.getBigDecimal(5)))), id);
    }

    public int openBreakCount(String deploymentId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from reconciliation_break b join reconciliation_run r
                    on r.reconciliation_id = b.reconciliation_id
                where r.deployment_id = ? and b.resolution_status = 'OPEN'
                """, Integer.class, deploymentId);
        return count == null ? 0 : count;
    }

    private static BigDecimal decimal(BigInteger value) {
        return value == null ? null : new BigDecimal(value);
    }

    private static BigInteger integer(BigDecimal value) {
        return value == null ? null : value.toBigIntegerExact();
    }
}
