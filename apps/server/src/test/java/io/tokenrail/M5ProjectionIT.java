package io.tokenrail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tokenrail.config.TokenRailProperties;
import io.tokenrail.gateway.BlockchainGateway;
import io.tokenrail.gateway.ContractCommands;
import io.tokenrail.ledger.DepositWorkflowService;
import io.tokenrail.ledger.OperationStatus;
import io.tokenrail.ledger.WorkflowRepository;
import io.tokenrail.projection.ChainDivergenceException;
import io.tokenrail.projection.Checkpoint;
import io.tokenrail.projection.IndexerFaults;
import io.tokenrail.projection.ProjectionRepository;
import io.tokenrail.projection.TransferIndexer;
import io.tokenrail.reconciliation.ReconciliationFaults;
import io.tokenrail.reconciliation.ReconciliationReport;
import io.tokenrail.reconciliation.ReconciliationRepository;
import io.tokenrail.reconciliation.ReconciliationService;
import java.math.BigInteger;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.web3j.crypto.Hash;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.profiles.active=reliability-test", "tokenrail.worker-enabled=false", "tokenrail.indexer-enabled=false"})
class M5ProjectionIT {
    private final WorkflowRepository ledger;
    private final DepositWorkflowService workflows;
    private final ProjectionRepository projections;
    private final TransferIndexer indexer;
    private final IndexerFaults indexerFaults;
    private final ReconciliationService reconciliations;
    private final ReconciliationRepository reconciliationRepository;
    private final ReconciliationFaults reconciliationFaults;
    private final ContractCommands contracts;
    private final BlockchainGateway chain;
    private final TokenRailProperties properties;

    @Autowired
    M5ProjectionIT(
            WorkflowRepository ledger,
            DepositWorkflowService workflows,
            ProjectionRepository projections,
            TransferIndexer indexer,
            IndexerFaults indexerFaults,
            ReconciliationService reconciliations,
            ReconciliationRepository reconciliationRepository,
            ReconciliationFaults reconciliationFaults,
            ContractCommands contracts,
            BlockchainGateway chain,
            TokenRailProperties properties) {
        this.ledger = ledger;
        this.workflows = workflows;
        this.projections = projections;
        this.indexer = indexer;
        this.indexerFaults = indexerFaults;
        this.reconciliations = reconciliations;
        this.reconciliationRepository = reconciliationRepository;
        this.reconciliationFaults = reconciliationFaults;
        this.contracts = contracts;
        this.chain = chain;
        this.properties = properties;
    }

    @Autowired io.tokenrail.config.DeploymentGuard guard;

    @BeforeEach
    void reset() {
        ledger.truncateForTest();
        ledger.registerDeployment(properties.deploymentId(), properties.chainId(),
                properties.genesisHash(), properties.depositTokenAddress());
        guard.initialize();
        projections.registerAsset(properties.deploymentId(), properties.depositTokenAddress(),
                "TRCNY", 2, properties.deploymentBlock());
        projections.registerAsset(properties.deploymentId(), properties.bondTokenAddress(),
                "ABC2029", 0, properties.deploymentBlock());
        long initial = Math.max(0, properties.deploymentBlock() - 1);
        projections.initializeProjection(properties.deploymentId(), 1, initial, chain.blockHash(initial));
        ledger.registerParticipant("INVESTOR_B", investorB(), "investor-b-token",
                investorBKey(), BigInteger.valueOf(100_000));
    }

    @AfterEach
    void clean() {
        ledger.truncateForTest();
    }

    @Test
    void t13_blockProjectionIsAtomicDeduplicatedAndResumesFromCheckpoint() {
        String unique = UUID.randomUUID().toString();
        prepareDvp(unique);
        Checkpoint beforeExecute = indexer.catchUpFully();
        BigInteger aBondBefore = balance(investorA(), properties.bondTokenAddress());
        BigInteger bBondBefore = balance(investorB(), properties.bondTokenAddress());
        BigInteger aCashBefore = balance(investorA(), properties.depositTokenAddress());
        BigInteger bCashBefore = balance(investorB(), properties.depositTokenAddress());
        long eventCountBefore = projections.eventCount(properties.deploymentId(), 1);

        TransactionReceipt receipt = contracts.executeInstruction(properties.issuerPrivateKeyFile(),
                ContractCommands.bytes32(id("dvp-" + unique)));
        long settlementBlock = receipt.getBlockNumber().longValueExact();
        catchUpTo(settlementBlock - 1);
        Checkpoint immediatelyBefore = projections.activeCheckpoint(properties.deploymentId()).orElseThrow();
        assertThat(immediatelyBefore.blockNumber()).isEqualTo(settlementBlock - 1);

        indexerFaults.failBeforeNextBlockCommit();
        assertThatThrownBy(() -> indexer.catchUp(1))
                .isInstanceOf(IndexerFaults.InjectedIndexerFailure.class);
        Checkpoint rolledBack = projections.activeCheckpoint(properties.deploymentId()).orElseThrow();
        assertThat(rolledBack.blockNumber()).isEqualTo(settlementBlock - 1);
        assertThat(projections.eventCount(properties.deploymentId(), 1)).isEqualTo(eventCountBefore);
        assertThat(balance(investorA(), properties.bondTokenAddress())).isEqualTo(aBondBefore);
        assertThat(balance(investorB(), properties.depositTokenAddress())).isEqualTo(bCashBefore);

        indexer.catchUp(1);
        assertThat(balance(investorA(), properties.bondTokenAddress()))
                .isEqualTo(aBondBefore.subtract(BigInteger.TEN));
        assertThat(balance(investorB(), properties.bondTokenAddress()))
                .isEqualTo(bBondBefore.add(BigInteger.TEN));
        assertThat(balance(investorA(), properties.depositTokenAddress()))
                .isEqualTo(aCashBefore.add(BigInteger.valueOf(10_000)));
        assertThat(balance(investorB(), properties.depositTokenAddress()))
                .isEqualTo(bCashBefore.subtract(BigInteger.valueOf(10_000)));
        long eventCountAfter = projections.eventCount(properties.deploymentId(), 1);
        assertThat(eventCountAfter - eventCountBefore).isEqualTo(2);

        projections.rewindForTest(properties.deploymentId(), 1, settlementBlock - 1,
                chain.blockHash(settlementBlock - 1));
        indexer.catchUp(1);
        assertThat(projections.eventCount(properties.deploymentId(), 1)).isEqualTo(eventCountAfter);
        assertThat(balance(investorB(), properties.bondTokenAddress()))
                .isEqualTo(bBondBefore.add(BigInteger.TEN));

        BigInteger offlineBalance = balance(investorB(), properties.bondTokenAddress());
        contracts.issueBond(properties.issuerPrivateKeyFile(),
                ContractCommands.bytes32(id("offline-" + unique)), investorB(), BigInteger.valueOf(3));
        indexer.catchUpFully();
        assertThat(balance(investorB(), properties.bondTokenAddress()))
                .isEqualTo(offlineBalance.add(BigInteger.valueOf(3)));
        assertThat(beforeExecute.projectionVersion()).isOne();
    }

    @Test
    void t14_reconciliationClassifiesMismatchLagAndSnapshotUnavailable() {
        String unique = UUID.randomUUID().toString();
        registerIdentities(unique);
        contracts.issueBond(properties.issuerPrivateKeyFile(),
                ContractCommands.bytes32(id("recon-" + unique)), investorB(), BigInteger.valueOf(100));
        Checkpoint checkpoint = indexer.catchUpFully();

        projections.corruptPositionForTest(properties.deploymentId(), checkpoint.projectionVersion(),
                checkpoint.blockNumber(), investorB(), properties.bondTokenAddress(), BigInteger.TEN.negate());

        ReconciliationReport mismatch = reconcileAtCurrentHead();
        assertThat(mismatch.status()).isEqualTo("POSITION_MISMATCH");
        assertThat(mismatch.breaks()).anySatisfy(item -> {
            assertThat(item.type()).isEqualTo("POSITION_MISMATCH");
            assertThat(item.delta()).isEqualTo(BigInteger.TEN);
        });

        contracts.issueBond(properties.issuerPrivateKeyFile(),
                ContractCommands.bytes32(id("lag-" + unique)), investorB(), BigInteger.ONE);
        ReconciliationReport lag = reconciliations.reconcile();
        assertThat(lag.status()).isEqualTo("LAG");
        assertThat(lag.lagBlocks()).isPositive();

        checkpoint = indexer.catchUpFully();

        reconciliationFaults.failNextHistoricalRead();
        ReconciliationReport unavailable = reconcileAtCurrentHead();
        assertThat(unavailable.status()).isEqualTo("SNAPSHOT_UNAVAILABLE");
    }

    @Test
    void t16_rebuildRepairsProjectionWithoutReplayingDepositJournal() {
        String unique = UUID.randomUUID().toString();
        registerIdentities(unique);
        contracts.issueBond(properties.issuerPrivateKeyFile(),
                ContractCommands.bytes32(id("rebuild-bond-" + unique)), investorB(), BigInteger.valueOf(50));
        var conversion = workflows.requestConversion("INVESTOR_B", "rebuild-mint-" + unique,
                id("rebuild-mint-" + unique), BigInteger.valueOf(1_000));
        awaitStatus(conversion.operationId(), OperationStatus.COMPLETED);
        var redemption = workflows.requestRedemption("INVESTOR_B", "rebuild-burn-" + unique,
                id("rebuild-burn-" + unique), BigInteger.valueOf(200));
        awaitStatus(redemption.operationId(), OperationStatus.CREDITED);

        Checkpoint original = indexer.catchUpFully();

        projections.corruptPositionForTest(properties.deploymentId(), original.projectionVersion(),
                original.blockNumber(), investorB(), properties.bondTokenAddress(), BigInteger.TEN.negate());
        ReconciliationReport broken = reconcileAtCurrentHead();
        assertThat(broken.status()).isEqualTo("POSITION_MISMATCH");
        int conversionJournals = ledger.journalCount(conversion.operationId(), "CONVERSION_BACKING");
        int redemptionJournals = ledger.journalCount(redemption.operationId(), "REDEMPTION_CREDIT");

        Checkpoint rebuilt = indexer.rebuildTo(original.blockNumber());
        assertThat(rebuilt.projectionVersion()).isEqualTo(2);
        BigInteger chainBond = chain.tokenBalanceAt(properties.bondTokenAddress(), investorB(),
                rebuilt.blockNumber());
        assertThat(balance(investorB(), properties.bondTokenAddress())).isEqualTo(chainBond);
        assertThat(ledger.journalCount(conversion.operationId(), "CONVERSION_BACKING"))
                .isEqualTo(conversionJournals);
        assertThat(ledger.journalCount(redemption.operationId(), "REDEMPTION_CREDIT"))
                .isEqualTo(redemptionJournals);

        ReconciliationReport repaired = reconciliations.reconcileAt(original.blockNumber());
        assertThat(repaired.breaks()).noneMatch(b -> "POSITION_MISMATCH".equals(b.type()));
        assertThat(reconciliations.get(broken.reconciliationId()).breaks()).filteredOn(b -> "POSITION_MISMATCH".equals(b.type())).allMatch(b -> "RESOLVED".equals(b.resolutionStatus()));
    }

    @Test
    void t18_checkpointDivergenceStopsIndexingAndDeploymentIdsIsolateState() {
        Checkpoint checkpoint = indexer.catchUpFully();
        projections.corruptCheckpointHashForTest(properties.deploymentId(),
                checkpoint.projectionVersion(), "0x" + "ff".repeat(32));
        assertThatThrownBy(() -> indexer.catchUp(1)).isInstanceOf(ChainDivergenceException.class);
        assertThat(projections.activeCheckpoint(properties.deploymentId())).isEmpty();
        assertThat(projections.checkpoint(properties.deploymentId(), 1).status()).isEqualTo("DIVERGED");

        String replacementDeployment = id("replacement-" + UUID.randomUUID());
        ledger.registerDeployment(replacementDeployment, properties.chainId(),
                properties.genesisHash(), properties.depositTokenAddress());
        projections.registerAsset(replacementDeployment, properties.depositTokenAddress(),
                "TRCNY", 2, properties.deploymentBlock());
        projections.registerAsset(replacementDeployment, properties.bondTokenAddress(),
                "ABC2029", 0, properties.deploymentBlock());
        long initial = Math.max(0, properties.deploymentBlock() - 1);
        projections.initializeProjection(replacementDeployment, 1, initial, chain.blockHash(initial));
        assertThat(projections.currentPositions(replacementDeployment, 1)).isEmpty();
        assertThat(projections.eventCount(replacementDeployment, 1)).isZero();
    }

    private void prepareDvp(String unique) {
        registerIdentities(unique);
        contracts.issueBond(properties.issuerPrivateKeyFile(),
                ContractCommands.bytes32(id("issue-" + unique)), investorA(), BigInteger.TEN);
        var conversion = workflows.requestConversion("INVESTOR_B", "dvp-cash-" + unique,
                id("cash-" + unique), BigInteger.valueOf(15_000));
        awaitStatus(conversion.operationId(), OperationStatus.COMPLETED);
        contracts.approve(investorAKey(), properties.bondTokenAddress(), properties.dvpAddress(),
                BigInteger.TEN);
        contracts.approve(investorBKey(), properties.depositTokenAddress(), properties.dvpAddress(),
                BigInteger.valueOf(10_000));
        byte[] instructionId = ContractCommands.bytes32(id("dvp-" + unique));
        contracts.createInstruction(investorAKey(), instructionId, investorB(), BigInteger.TEN,
                BigInteger.valueOf(10_000), java.time.Instant.now().getEpochSecond() + 3_600);
        contracts.confirmInstruction(investorBKey(), instructionId);
    }

    private void registerIdentities(String unique) {
        contracts.setIdentity(properties.issuerPrivateKeyFile(), investorA(),
                ContractCommands.bytes32(id("A-" + unique)));
        contracts.setIdentity(properties.issuerPrivateKeyFile(), investorB(),
                ContractCommands.bytes32(id("B-" + unique)));
    }

    private void catchUpTo(long target) {
        Checkpoint checkpoint = projections.activeCheckpoint(properties.deploymentId()).orElseThrow();
        long remaining = target - checkpoint.blockNumber();
        if (remaining > 0) {
            indexer.catchUp(Math.toIntExact(remaining));
        }
    }

    private ReconciliationReport reconcileAtCurrentHead() {
        for (int attempt = 0; attempt < 10; attempt++) {
            Checkpoint checkpoint = indexer.catchUpFully();

            ReconciliationReport report = reconciliations.reconcile();
            if (!"LAG".equals(report.status())) {
                return report;
            }
        }
        throw new AssertionError("Could not reconcile at the current chain head");
    }

    private BigInteger balance(String wallet, String asset) {
        Checkpoint checkpoint = projections.activeCheckpoint(properties.deploymentId()).orElseThrow();
        return projections.balance(properties.deploymentId(), checkpoint.projectionVersion(), wallet, asset);
    }

    private void awaitStatus(UUID operationId, OperationStatus expected) {
        for (int attempt = 0; attempt < 40; attempt++) {
            if (ledger.getOperation(operationId).status() == expected) {
                return;
            }
            ledger.makeReadyForTest(operationId);
            workflows.processOne();
            try {
                Thread.sleep(200);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        }
        throw new AssertionError("Operation did not reach " + expected);
    }

    private BigInteger chainBalance(String wallet, String asset, long block) {
        return chain.tokenBalanceAt(asset, wallet, block);
    }

    private static String id(String value) {
        return Hash.sha3String(value);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }

    private String investorA() { return required("TOKENRAIL_INVESTOR_A_ADDRESS"); }
    private String investorAKey() { return required("TOKENRAIL_INVESTOR_A_KEY_FILE"); }
    private String investorB() { return required("TOKENRAIL_PARTICIPANT_INVESTOR_B_ADDRESS"); }
    private String investorBKey() { return required("TOKENRAIL_PARTICIPANT_INVESTOR_B_KEY_FILE"); }
}
