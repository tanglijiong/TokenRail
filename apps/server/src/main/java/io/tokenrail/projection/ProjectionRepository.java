package io.tokenrail.projection;

import io.tokenrail.api.NotFoundException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class ProjectionRepository {
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.core.env.Environment environment;
    private void requireTestProfile() {
        if (!environment.matchesProfiles("reliability-test", "local-demo"))
            throw new IllegalStateException("Fault tools require an explicit local-demo or reliability-test profile");
    }

    private static final String ZERO_ADDRESS = "0x0000000000000000000000000000000000000000";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public ProjectionRepository(
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public void registerAsset(
            String deploymentId, String address, String symbol, int decimals, long deploymentBlock) {
        jdbc.update("""
                insert into asset(deployment_id, contract_address, symbol, decimals, deployment_block)
                values (?, ?, ?, ?, ?)
                on conflict (deployment_id, contract_address) do update set
                    symbol = excluded.symbol, decimals = excluded.decimals,
                    deployment_block = excluded.deployment_block
                """, deploymentId, normalize(address), symbol, decimals, deploymentBlock);
    }

    public Checkpoint initializeProjection(
            String deploymentId, int version, long initialBlock, String initialHash) {
        jdbc.update("""
                insert into indexer_checkpoint(deployment_id, projection_version,
                    block_number, block_hash, status)
                values (?, ?, ?, ?, 'ACTIVE')
                on conflict (deployment_id, projection_version) do nothing
                """, deploymentId, version, initialBlock, normalize(initialHash));
        return checkpoint(deploymentId, version);
    }

    public Optional<Checkpoint> activeCheckpoint(String deploymentId) {
        return jdbc.query("""
                        select deployment_id, projection_version, block_number, block_hash, status
                        from indexer_checkpoint where deployment_id = ? and status = 'ACTIVE'
                        """, (result, row) -> new Checkpoint(
                                result.getString(1), result.getInt(2), result.getLong(3),
                                result.getString(4), result.getString(5)), deploymentId)
                .stream().findFirst();
    }

    public Checkpoint checkpoint(String deploymentId, int version) {
        return jdbc.query("""
                        select deployment_id, projection_version, block_number, block_hash, status
                        from indexer_checkpoint where deployment_id = ? and projection_version = ?
                        """, (result, row) -> new Checkpoint(
                                result.getString(1), result.getInt(2), result.getLong(3),
                                result.getString(4), result.getString(5)), deploymentId, version)
                .stream().findFirst().orElseThrow(() -> new NotFoundException("Projection not found"));
    }

    public List<Asset> assets(String deploymentId) {
        return jdbc.query("""
                select contract_address, symbol, decimals, deployment_block from asset
                where deployment_id = ? order by contract_address
                """, (result, row) -> new Asset(result.getString(1), result.getString(2),
                result.getInt(3), result.getLong(4)), deploymentId);
    }

    public void applyBlock(
            Checkpoint expected,
            long blockNumber,
            String blockHash,
            List<TransferLog> logs,
            Runnable beforeCommit) {
        transactions.executeWithoutResult(ignored -> {
            jdbc.queryForObject("select deployment_id from deployment where deployment_id = ? for update", String.class, expected.deploymentId());
            Checkpoint locked = jdbc.query("""
                    select deployment_id, projection_version, block_number, block_hash, status
                    from indexer_checkpoint where deployment_id = ? and projection_version = ?
                    for update
                    """, (result, row) -> new Checkpoint(
                    result.getString(1), result.getInt(2), result.getLong(3),
                    result.getString(4), result.getString(5)),
                    expected.deploymentId(), expected.projectionVersion()).getFirst();
            if (!("ACTIVE".equals(locked.status()) || "BUILDING".equals(locked.status())) || locked.blockNumber() + 1 != blockNumber) {
                throw new IllegalStateException("Projection checkpoint changed while indexing");
            }
            for (TransferLog log : logs.stream().sorted(java.util.Comparator
                    .comparingLong(TransferLog::transactionIndex)
                    .thenComparingLong(TransferLog::logIndex)).toList()) {
                if (log.blockNumber() != blockNumber || !log.blockHash().equalsIgnoreCase(blockHash)) {
                    throw new IllegalStateException("Log does not belong to the requested block");
                }
                applyTransfer(locked, log);
            }
            jdbc.update("""
                    delete from position_snapshot where deployment_id = ? and projection_version = ?
                        and block_number = ?
                    """, locked.deploymentId(), locked.projectionVersion(), blockNumber);
            jdbc.update("""
                    insert into position_snapshot(deployment_id, projection_version, block_number,
                        block_hash, wallet_address, asset_address, balance)
                    select deployment_id, projection_version, ?, ?, wallet_address, asset_address, balance
                    from offchain_position where deployment_id = ? and projection_version = ?
                    """, blockNumber, normalize(blockHash), locked.deploymentId(), locked.projectionVersion());
            jdbc.update("""
                    update indexer_checkpoint set block_number = ?, block_hash = ?, updated_at = now()
                    where deployment_id = ? and projection_version = ? and status in ('ACTIVE', 'BUILDING')
                    """, blockNumber, normalize(blockHash), locked.deploymentId(), locked.projectionVersion());
            jdbc.update("insert into projection_block values (?, ?, ?, ?) on conflict do nothing",
                    locked.deploymentId(), locked.projectionVersion(), blockNumber, normalize(blockHash));
            beforeCommit.run();
        });
    }

    public void markDiverged(Checkpoint checkpoint, String detail) {
        jdbc.update("""
                update indexer_checkpoint set status = 'DIVERGED', updated_at = now()
                where deployment_id = ? and projection_version = ? and status = 'ACTIVE'
                """, checkpoint.deploymentId(), checkpoint.projectionVersion());
    }

    public Checkpoint startRebuild(String deploymentId, long initialBlock, String initialHash) {
        return startRebuild(deploymentId, initialBlock, initialHash, null);
    }

    public Checkpoint startRebuild(String deploymentId, long initialBlock, String initialHash, UUID key) {
        return transactions.execute(status -> {
            jdbc.queryForObject("select deployment_id from deployment where deployment_id = ? for update", String.class, deploymentId);
            if (key != null) {
                var versions = jdbc.query("select projection_version from indexer_checkpoint where deployment_id = ? and rebuild_key = ?", (r, n) -> r.getInt(1), deploymentId, key);
                if (!versions.isEmpty()) return checkpoint(deploymentId, versions.getFirst());
            }
            var building = jdbc.query("select projection_version from indexer_checkpoint where deployment_id = ? and status = 'BUILDING'",
                    (r, n) -> r.getInt(1), deploymentId);
            if (!building.isEmpty()) {
                if (key != null) throw new io.tokenrail.api.ConflictException("Another rebuild is pending");
                return checkpoint(deploymentId, building.getFirst());
            }
            Integer current = jdbc.queryForObject("select coalesce(max(projection_version), 0) from indexer_checkpoint where deployment_id = ?", Integer.class, deploymentId);
            int next = current + 1;
            jdbc.update("""
                    insert into indexer_checkpoint(deployment_id, projection_version,
                        block_number, block_hash, status, rebuild_key) values (?, ?, ?, ?, 'BUILDING', ?)
                    """, deploymentId, next, initialBlock, normalize(initialHash), key);
            return checkpoint(deploymentId, next);
        });
    }

    public List<Position> currentPositions(String deploymentId, int version) {
        Checkpoint checkpoint = checkpoint(deploymentId, version);
        return snapshotPositions(deploymentId, version, checkpoint.blockNumber(), checkpoint.blockHash());
    }

    public Checkpoint activate(Checkpoint expected) {
        return transactions.execute(status -> {
            jdbc.queryForObject("select deployment_id from deployment where deployment_id = ? for update", String.class, expected.deploymentId());
            Checkpoint actual = checkpoint(expected.deploymentId(), expected.projectionVersion());
            if (!actual.equals(expected) || !"BUILDING".equals(actual.status()))
                throw new IllegalStateException("Rebuild checkpoint changed");
            var active = activeCheckpoint(expected.deploymentId());
            if (active.isPresent() && active.get().blockNumber() > expected.blockNumber())
                throw new IllegalStateException("Rebuild must catch up with the active projection before activation");
            jdbc.update("update indexer_checkpoint set status = 'SUPERSEDED' where deployment_id = ? and status = 'ACTIVE'", expected.deploymentId());
            jdbc.update("update indexer_checkpoint set status = 'ACTIVE' where deployment_id = ? and projection_version = ? and status = 'BUILDING'", expected.deploymentId(), expected.projectionVersion());
            return checkpoint(expected.deploymentId(), expected.projectionVersion());
        });
    }

    public List<Position> snapshotPositions(
            String deploymentId, int version, long blockNumber, String blockHash) {
        return jdbc.query("""
                with wallets as (
                    select wallet_address from deployment_participant where deployment_id = ?
                    union select '0x' || right(topics->>1, 40) from chain_event
                        where deployment_id = ? and projection_version = ? and block_number <= ?
                    union select '0x' || right(topics->>2, 40) from chain_event
                        where deployment_id = ? and projection_version = ? and block_number <= ?
                    union select wallet_address from position_snapshot where deployment_id = ? and projection_version = ? and block_number = ?
                )
                select w.wallet_address, a.contract_address, a.symbol, coalesce(s.balance, 0)
                from wallets w cross join asset a left join position_snapshot s
                    on s.deployment_id = a.deployment_id and s.asset_address = a.contract_address
                    and s.wallet_address = w.wallet_address and s.projection_version = ? and s.block_number = ?
                where a.deployment_id = ? and w.wallet_address <> '0x0000000000000000000000000000000000000000'
                order by w.wallet_address, a.symbol
                """, (result, row) -> new Position(
                result.getString(1), result.getString(2), result.getString(3),
                result.getBigDecimal(4).toBigIntegerExact(), blockNumber, blockHash, version),
                deploymentId, deploymentId, version, blockNumber, deploymentId, version, blockNumber,
                deploymentId, version, blockNumber, version, blockNumber, deploymentId);
    }

    public String snapshotHash(String deploymentId, int version, long block) {
        var hashes = jdbc.query("select block_hash from projection_block where deployment_id = ? and projection_version = ? and block_number = ?",
                (r, n) -> r.getString(1), deploymentId, version, block);
        if (hashes.isEmpty()) throw new IllegalArgumentException("Snapshot block has not been indexed");
        return hashes.getFirst();
    }

    public BigInteger balance(
            String deploymentId, int version, String wallet, String assetAddress) {
        return jdbc.query("""
                        select balance from offchain_position where deployment_id = ?
                            and projection_version = ? and wallet_address = ? and asset_address = ?
                        """, (result, row) -> result.getBigDecimal(1).toBigIntegerExact(),
                        deploymentId, version, normalize(wallet), normalize(assetAddress))
                .stream().findFirst().orElse(BigInteger.ZERO);
    }

    public long eventCount(String deploymentId, int version) {
        Long count = jdbc.queryForObject("""
                select count(*) from chain_event where deployment_id = ? and projection_version = ?
                """, Long.class, deploymentId, version);
        return count == null ? 0 : count;
    }

    public void rewindForTest(String deploymentId, int version, long blockNumber, String blockHash) {
        requireTestProfile();
        jdbc.update("""
                update indexer_checkpoint set block_number = ?, block_hash = ?, status = 'ACTIVE'
                where deployment_id = ? and projection_version = ?
                """, blockNumber, normalize(blockHash), deploymentId, version);
    }

    public void corruptPositionForTest(
            String deploymentId, int version, long blockNumber,
            String wallet, String assetAddress, BigInteger delta) {
        requireTestProfile();
        transactions.executeWithoutResult(ignored -> {
            jdbc.update("""
                    update offchain_position set balance = balance + ? where deployment_id = ?
                        and projection_version = ? and wallet_address = ? and asset_address = ?
                    """, decimal(delta), deploymentId, version, normalize(wallet), normalize(assetAddress));
            jdbc.update("""
                    update position_snapshot set balance = balance + ? where deployment_id = ?
                        and projection_version = ? and block_number = ?
                        and wallet_address = ? and asset_address = ?
                    """, decimal(delta), deploymentId, version, blockNumber,
                    normalize(wallet), normalize(assetAddress));
        });
    }

    public void corruptCheckpointHashForTest(String deploymentId, int version, String blockHash) {
        requireTestProfile();
        jdbc.update("""
                update indexer_checkpoint set block_hash = ?
                where deployment_id = ? and projection_version = ?
                """, normalize(blockHash), deploymentId, version);
    }

    private void applyTransfer(Checkpoint checkpoint, TransferLog log) {
        UUID eventId = UUID.randomUUID();
        List<UUID> inserted = jdbc.query("""
                insert into chain_event(event_id, deployment_id, projection_version,
                    block_number, block_hash, transaction_hash, transaction_index, log_index,
                    contract_address, topic0, topics, data)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                on conflict (deployment_id, projection_version, block_hash, transaction_hash, log_index)
                    do nothing returning event_id
                """, (result, row) -> result.getObject(1, UUID.class), eventId,
                checkpoint.deploymentId(), checkpoint.projectionVersion(), log.blockNumber(),
                normalize(log.blockHash()), normalize(log.transactionHash()), log.transactionIndex(),
                log.logIndex(), normalize(log.contractAddress()), normalize(log.topics().getFirst()),
                topicsJson(log.topics()), log.data());
        if (inserted.isEmpty() || log.amount().signum() == 0 || log.from().equalsIgnoreCase(log.to())) {
            return;
        }
        if (!ZERO_ADDRESS.equals(log.from())) {
            int changed = jdbc.update("""
                    update offchain_position set balance = balance - ?, updated_block = ?
                    where deployment_id = ? and projection_version = ? and wallet_address = ?
                        and asset_address = ? and balance >= ?
                    """, decimal(log.amount()), log.blockNumber(), checkpoint.deploymentId(),
                    checkpoint.projectionVersion(), normalize(log.from()), normalize(log.contractAddress()),
                    decimal(log.amount()));
            if (changed != 1) {
                throw new IllegalStateException("Transfer would make projected balance negative");
            }
            insertEntry(eventId, log.from(), log.contractAddress(), log.amount().negate());
        }
        if (!ZERO_ADDRESS.equals(log.to())) {
            jdbc.update("""
                    insert into offchain_position(deployment_id, projection_version, wallet_address,
                        asset_address, balance, updated_block) values (?, ?, ?, ?, ?, ?)
                    on conflict (deployment_id, projection_version, wallet_address, asset_address)
                    do update set balance = offchain_position.balance + excluded.balance,
                        updated_block = excluded.updated_block
                    """, checkpoint.deploymentId(), checkpoint.projectionVersion(), normalize(log.to()),
                    normalize(log.contractAddress()), decimal(log.amount()), log.blockNumber());
            insertEntry(eventId, log.to(), log.contractAddress(), log.amount());
        }
    }

    private void insertEntry(UUID eventId, String wallet, String asset, BigInteger delta) {
        jdbc.update("""
                insert into position_entry(event_id, wallet_address, asset_address, delta)
                values (?, ?, ?, ?)
                """, eventId, normalize(wallet), normalize(asset), decimal(delta));
    }

    private String topicsJson(List<String> topics) {
        if (topics.stream().anyMatch(topic -> !topic.matches("0x[0-9a-fA-F]{64}"))) {
            throw new IllegalArgumentException("Event contains a malformed topic");
        }
        return topics.stream().map(topic -> "\"" + topic.toLowerCase(Locale.ROOT) + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private static BigDecimal decimal(BigInteger value) {
        return new BigDecimal(value);
    }
}
