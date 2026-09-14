package io.tokenrail.reconciliation;

import io.tokenrail.config.TokenRailProperties;
import io.tokenrail.gateway.BlockchainGateway;
import io.tokenrail.ledger.WorkflowRepository;
import io.tokenrail.projection.Asset;
import io.tokenrail.projection.Checkpoint;
import io.tokenrail.projection.Position;
import io.tokenrail.projection.ProjectionRepository;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ReconciliationService {
    private final ProjectionRepository projections;
    private final ReconciliationRepository reports;
    private final WorkflowRepository ledger;
    private final BlockchainGateway chain;
    private final TokenRailProperties properties;
    private final ReconciliationFaults faults;

    public ReconciliationService(
            ProjectionRepository projections,
            ReconciliationRepository reports,
            WorkflowRepository ledger,
            BlockchainGateway chain,
            TokenRailProperties properties,
            ReconciliationFaults faults) {
        this.projections = projections;
        this.reports = reports;
        this.ledger = ledger;
        this.chain = chain;
        this.properties = properties;
        this.faults = faults;
    }

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private record Snapshot(Checkpoint checkpoint, List<Position> positions, BankSnapshot bank) { }

    public ReconciliationReport reconcile() { return reconcileAt(null); }

    public ReconciliationReport reconcileAt(Long requestedBlock) { return reconcileAt(requestedBlock, null); }

    public ReconciliationReport reconcileAt(Long requestedBlock, UUID reportId) {
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.setReadOnly(true);
        Snapshot snapshot = tx.execute(ignored -> {
            Checkpoint active = projections.activeCheckpoint(properties.deploymentId()).orElseThrow();
            long block = requestedBlock == null ? active.blockNumber() : requestedBlock;
            if (block < properties.deploymentBlock() || block > active.blockNumber())
                throw new IllegalArgumentException("Requested snapshot is outside indexed history");
            String hash = projections.snapshotHash(active.deploymentId(), active.projectionVersion(), block);
            Checkpoint checkpoint = new Checkpoint(active.deploymentId(), active.projectionVersion(), block, hash, active.status());
            return new Snapshot(checkpoint, projections.snapshotPositions(checkpoint.deploymentId(),
                    checkpoint.projectionVersion(), block, hash), ledger.bankSnapshot());
        });
        Checkpoint checkpoint = snapshot.checkpoint();
        long head = chain.headBlockNumber();
        long lag = Math.max(0, head - checkpoint.blockNumber());
        if (requestedBlock == null && lag > 0)
            return reports.save(reportId, checkpoint, head, "LAG", lag, "Projection is behind chain head", List.of());
        List<ReconciliationRepository.BreakDraft> checks = new ArrayList<>();
        try {
            faults.beforeHistoricalRead();
            if (!checkpoint.blockHash().equalsIgnoreCase(chain.blockHash(checkpoint.blockNumber())))
                return reports.save(reportId, checkpoint, head, "CHAIN_DIVERGENCE", lag, "Snapshot hash mismatch", List.of());
            for (Position position : snapshot.positions()) {
                BigInteger onchain = chain.tokenBalanceAt(position.assetAddress(), position.walletAddress(), checkpoint.blockNumber());
                checks.add(new ReconciliationRepository.BreakDraft("POSITION_MISMATCH", position.walletAddress(),
                        position.assetAddress(), onchain, position.balance(), onchain.subtract(position.balance())));
            }
            for (Asset asset : projections.assets(properties.deploymentId())) {
                BigInteger sum = snapshot.positions().stream().filter(p -> p.assetAddress().equalsIgnoreCase(asset.address()))
                        .map(Position::balance).reduce(BigInteger.ZERO, BigInteger::add);
                BigInteger total = chain.totalSupplyAt(asset.address(), checkpoint.blockNumber());
                checks.add(new ReconciliationRepository.BreakDraft("POSITION_TOTAL_MISMATCH", null, asset.address(), total, sum, total.subtract(sum)));
            }
            BigInteger future = BigInteger.ZERO, mints = BigInteger.ZERO, burns = BigInteger.ZERO;
            for (BankSnapshot.Posting posting : snapshot.bank().operations()) {
                if (posting.transactionHash() == null) {
                    if (posting.posted()) throw new BlockchainGateway.SnapshotUnavailableException("Posted bank entry has no transaction hash");
                    continue;
                }
                var receipt = chain.receipt(posting.transactionHash());
                if (receipt.isEmpty() || !receipt.get().isStatusOK()) {
                    if (posting.posted()) throw new BlockchainGateway.SnapshotUnavailableException("Posted bank entry lacks a successful receipt");
                    continue;
                }
                var mined = receipt.get();
                if (!mined.getBlockHash().equalsIgnoreCase(chain.blockHash(mined.getBlockNumber().longValueExact())))
                    throw new BlockchainGateway.SnapshotUnavailableException("Bank receipt is no longer canonical");
                boolean mint = "CONVERSION".equals(posting.kind());
                if (mined.getBlockNumber().longValueExact() > checkpoint.blockNumber()) {
                    if (posting.posted()) future = future.add(mint ? posting.amount() : posting.amount().negate());
                } else if (!posting.posted()) {
                    if (mint) mints = mints.add(posting.amount()); else burns = burns.add(posting.amount());
                }
            }
            BankSnapshot.Evidence bank = new BankSnapshot.Evidence(snapshot.bank().booked(), future, mints, burns);
            BigInteger supply = chain.totalSupplyAt(properties.depositTokenAddress(), checkpoint.blockNumber());
            checks.add(new ReconciliationRepository.BreakDraft("BACKING_MISMATCH", null, properties.depositTokenAddress(),
                    supply, bank.atBlock(), supply.subtract(bank.atBlock())));
            if (!checkpoint.blockHash().equalsIgnoreCase(chain.blockHash(checkpoint.blockNumber())))
                return reports.save(reportId, checkpoint, head, "CHAIN_DIVERGENCE", lag, "Chain changed during reconciliation", List.of());
            var breaks = checks.stream().filter(item -> item.delta().signum() != 0).toList();
            String status = breaks.stream().anyMatch(item -> item.type().startsWith("POSITION_"))
                    ? "POSITION_MISMATCH" : breaks.isEmpty() ? "MATCH" : "BACKING_MISMATCH";
            return reports.saveVerified(checkpoint, head, status, checks, bank, reportId);
        } catch (BlockchainGateway.SnapshotUnavailableException | ReconciliationFaults.SnapshotFault exception) {
            return reports.save(reportId, checkpoint, head, "SNAPSHOT_UNAVAILABLE", lag, exception.getMessage(), List.of());
        }
    }

    public ReconciliationReport get(UUID id) { return reports.get(id); }
}
