package io.tokenrail.projection;

import io.tokenrail.config.TokenRailProperties;
import io.tokenrail.gateway.BlockchainGateway;
import java.util.List;
import java.math.BigInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class TransferIndexer {
    private static final Logger log = LoggerFactory.getLogger(TransferIndexer.class);
    @org.springframework.beans.factory.annotation.Autowired private io.tokenrail.config.DeploymentGuard guard;
    private final ProjectionRepository repository;
    private final BlockchainGateway chain;
    private final TokenRailProperties properties;
    private final IndexerFaults faults;

    public TransferIndexer(
            ProjectionRepository repository,
            BlockchainGateway chain,
            TokenRailProperties properties,
            IndexerFaults faults) {
        this.repository = repository;
        this.chain = chain;
        this.properties = properties;
        this.faults = faults;
    }

    @Scheduled(
            fixedDelayString = "${tokenrail.indexer-delay:1000}",
            initialDelayString = "${tokenrail.indexer-initial-delay:2000}")
    public void scheduledIndex() {
        if (!properties.indexerEnabled()) {
            return;
        }
        try {
            catchUp(properties.indexerBatchSize());
        } catch (RuntimeException exception) {
            log.warn("Indexer pass stopped: {}", exception.getMessage());
        }
    }

    public int catchUp(int maximumBlocks) {
        Checkpoint checkpoint = repository.activeCheckpoint(properties.deploymentId())
                .orElseThrow(() -> new IllegalStateException("Active projection is not initialized"));
        verifyCheckpoint(checkpoint);
        guard.verify();
        long head = chain.headBlockNumber();
        long target = Math.min(head, checkpoint.blockNumber() + Math.max(0, maximumBlocks));
        List<String> assets = repository.assets(properties.deploymentId()).stream()
                .map(Asset::address).toList();
        int processed = 0;
        for (long block = checkpoint.blockNumber() + 1; block <= target; block++) {
            String blockHash = chain.blockHash(block);
            List<TransferLog> logs = chain.transferLogs(block, assets);
            repository.applyBlock(checkpoint, block, blockHash, logs, faults::beforeBlockCommit);
            checkpoint = repository.checkpoint(
                    checkpoint.deploymentId(), checkpoint.projectionVersion());
            processed++;
        }
        return processed;
    }

    public Checkpoint rebuildTo(long targetBlock) { return rebuildTo(targetBlock, null); }

    public Checkpoint rebuildTo(long targetBlock, java.util.UUID jobId) {
        guard.verify();
        if (targetBlock < properties.deploymentBlock() || targetBlock > chain.headBlockNumber())
            throw new IllegalArgumentException("Invalid rebuild target");
        long initialBlock = Math.max(0, properties.deploymentBlock() - 1);
        Checkpoint rebuilt = repository.startRebuild(properties.deploymentId(), initialBlock,
                chain.blockHash(initialBlock), jobId);
        if (!"BUILDING".equals(rebuilt.status())) return rebuilt;
        List<String> assets = repository.assets(properties.deploymentId()).stream()
                .map(Asset::address).toList();
        for (long block = rebuilt.blockNumber() + 1; block <= targetBlock; block++) {
            String blockHash = chain.blockHash(block);
            repository.applyBlock(rebuilt, block, blockHash,
                    chain.transferLogs(block, assets), faults::beforeBlockCommit);
            rebuilt = repository.checkpoint(rebuilt.deploymentId(), rebuilt.projectionVersion());
        }
        long activeBlock = repository.activeCheckpoint(properties.deploymentId()).map(Checkpoint::blockNumber).orElse(targetBlock);
        if (activeBlock > rebuilt.blockNumber()) return rebuildTo(activeBlock, jobId);
        verifyCheckpoint(rebuilt);
        for (Position position : repository.snapshotPositions(rebuilt.deploymentId(), rebuilt.projectionVersion(), rebuilt.blockNumber(), rebuilt.blockHash())) {
            if (!position.balance().equals(chain.tokenBalanceAt(position.assetAddress(), position.walletAddress(), rebuilt.blockNumber())))
                throw new IllegalStateException("Rebuild balance validation failed");
        }
        for (Asset asset : repository.assets(rebuilt.deploymentId())) {
            BigInteger sum = repository.snapshotPositions(rebuilt.deploymentId(), rebuilt.projectionVersion(), rebuilt.blockNumber(), rebuilt.blockHash())
                    .stream().filter(p -> p.assetAddress().equalsIgnoreCase(asset.address())).map(Position::balance)
                    .reduce(BigInteger.ZERO, BigInteger::add);
            if (!sum.equals(chain.totalSupplyAt(asset.address(), rebuilt.blockNumber())))
                throw new IllegalStateException("Rebuild supply validation failed");
        }
        verifyCheckpoint(rebuilt);
        return repository.activate(rebuilt);
    }

    public Checkpoint catchUpFully() {
        long head = chain.headBlockNumber();
        Checkpoint checkpoint = repository.activeCheckpoint(properties.deploymentId()).orElseThrow();
        int remaining = Math.toIntExact(Math.max(0, head - checkpoint.blockNumber()));
        catchUp(remaining);
        return repository.activeCheckpoint(properties.deploymentId()).orElseThrow();
    }

    public Checkpoint rebuildFully() {
        return rebuildTo(chain.headBlockNumber());
    }

    private void verifyCheckpoint(Checkpoint checkpoint) {
        String actualHash;
        try {
            actualHash = chain.blockHash(checkpoint.blockNumber());
        } catch (BlockchainGateway.SnapshotUnavailableException exception) {
            repository.markDiverged(checkpoint, exception.getMessage());
            throw new ChainDivergenceException("Checkpoint block is unavailable");
        }
        if (!checkpoint.blockHash().equalsIgnoreCase(actualHash)) {
            repository.markDiverged(checkpoint, "Checkpoint block hash mismatch");
            throw new ChainDivergenceException("Checkpoint block hash mismatch");
        }
    }
}
