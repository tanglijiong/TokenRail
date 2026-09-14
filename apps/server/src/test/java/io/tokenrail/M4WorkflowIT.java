package io.tokenrail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tokenrail.api.ConflictException;
import io.tokenrail.config.TokenRailProperties;
import io.tokenrail.gateway.BlockchainGateway;
import io.tokenrail.gateway.ChainTransaction;
import io.tokenrail.gateway.ContractCommands;
import io.tokenrail.gateway.WorkflowFaults;
import io.tokenrail.ledger.DepositOperation;
import io.tokenrail.ledger.DepositWorkflowService;
import io.tokenrail.ledger.OperationStatus;
import io.tokenrail.ledger.WorkflowRepository;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.profiles.active=reliability-test", "tokenrail.worker-enabled=false", "tokenrail.indexer-enabled=false"})
class M4WorkflowIT {
    private final WorkflowRepository repository;
    private final DepositWorkflowService workflows;
    private final BlockchainGateway chain;
    private final WorkflowFaults faults;
    private final TokenRailProperties properties;
    private final ContractCommands contracts;

    @Autowired
    M4WorkflowIT(
            WorkflowRepository repository,
            DepositWorkflowService workflows,
            BlockchainGateway chain,
            WorkflowFaults faults,
            TokenRailProperties properties,
            ContractCommands contracts) {
        this.repository = repository;
        this.workflows = workflows;
        this.chain = chain;
        this.faults = faults;
        this.properties = properties;
        this.contracts = contracts;
    }

    @Autowired io.tokenrail.config.DeploymentGuard guard;

    @BeforeEach
    void resetDatabase() {
        repository.truncateForTest();
        repository.registerDeployment(properties.deploymentId(), properties.chainId(),
                properties.genesisHash(), properties.depositTokenAddress());
        guard.initialize();
        repository.registerParticipant("INVESTOR_B", participantAddress(), "investor-b-token",
                participantKeyFile(), new BigInteger("100000"));
    }

    @AfterEach
    void cleanDatabase() {
        repository.truncateForTest();
    }

    @Test
    void t10_idempotencyReturnsOriginalResultAndRejectsPayloadChange() {
        String businessId = id("T10-conversion");
        WorkflowRepository.RequestResult first = workflows.requestConversion(
                "INVESTOR_B", "same-key", businessId, BigInteger.valueOf(1_000));
        WorkflowRepository.RequestResult replay = workflows.requestConversion(
                "INVESTOR_B", "same-key", businessId, BigInteger.valueOf(1_000));

        assertThat(replay.operationId()).isEqualTo(first.operationId());
        assertThat(repository.available("INVESTOR_B")).isEqualTo(BigInteger.valueOf(99_000));
        assertThat(repository.reserved("INVESTOR_B")).isEqualTo(BigInteger.valueOf(1_000));
        assertThatThrownBy(() -> workflows.requestConversion(
                "INVESTOR_B", "same-key", businessId, BigInteger.valueOf(2_000)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void t12_concurrentSignerNonceAllocationIsUniqueAndContiguous() throws Exception {
        Credentials issuer = chain.credentials(properties.issuerPrivateKeyFile());
        BigInteger pending = chain.pendingNonce(issuer.getAddress());
        List<DepositOperation> operations = new ArrayList<>();
        for (int index = 0; index < 16; index++) {
            UUID operationId = workflows.requestConversion("INVESTOR_B", "nonce-" + index,
                    id("T12-" + index), BigInteger.ONE).operationId();
            operations.add(repository.claimNext().orElseThrow());
        }
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Callable<BigInteger>> tasks = operations.stream()
                    .<Callable<BigInteger>>map(operation -> () -> repository.allocateNonce(
                            operation, "mintForConversion", issuer.getAddress(), pending).nonce())
                    .toList();
            List<BigInteger> nonces = executor.invokeAll(tasks).stream()
                    .map(future -> {
                        try { return future.get(); } catch (Exception exception) {
                            throw new RuntimeException(exception);
                        }
                    }).sorted().toList();
            assertThat(new HashSet<>(nonces)).hasSize(16);
            for (int index = 0; index < nonces.size(); index++) {
                assertThat(nonces.get(index)).isEqualTo(pending.add(BigInteger.valueOf(index)));
            }
        }
    }

    @Test
    void t11AndT15_unknownBroadcastAndDatabaseFailuresRecoverExactlyOnce() {
        String unique = UUID.randomUUID().toString();
        WorkflowRepository.RequestResult uncertain = workflows.requestConversion(
                "INVESTOR_B", "unknown-broadcast-" + unique, id("T11-" + unique),
                BigInteger.valueOf(1_000));
        faults.failAfterNextBroadcast();
        assertThatThrownBy(workflows::processOne).isInstanceOf(WorkflowFaults.InjectedFailure.class);
        ChainTransaction unknown = repository.findTransaction(uncertain.operationId()).orElseThrow();
        assertThat(unknown.status()).isEqualTo("UNKNOWN");
        awaitStatus(uncertain.operationId(), OperationStatus.COMPLETED);
        assertThat(repository.journalCount(uncertain.operationId(), "CONVERSION_BACKING")).isOne();

        WorkflowRepository.RequestResult databaseFailure = workflows.requestConversion(
                "INVESTOR_B", "conversion-db-failure-" + unique, id("T15-mint-" + unique),
                BigInteger.valueOf(1_000));
        faults.failBeforeNextConversionCommit();
        awaitInjectedFailure(databaseFailure.operationId());
        awaitStatus(databaseFailure.operationId(), OperationStatus.COMPLETED);
        assertThat(repository.journalCount(databaseFailure.operationId(), "CONVERSION_BACKING")).isOne();

        WorkflowRepository.RequestResult redemption = workflows.requestRedemption(
                "INVESTOR_B", "redemption-db-failure-" + unique, id("T15-burn-" + unique),
                BigInteger.valueOf(500));
        faults.failBeforeNextRedemptionCredit();
        awaitInjectedFailure(redemption.operationId());
        awaitStatus(redemption.operationId(), OperationStatus.CREDITED);

        assertThat(repository.journalCount(redemption.operationId(), "REDEMPTION_CREDIT")).isOne();
        assertThat(repository.available("INVESTOR_B")).isEqualTo(BigInteger.valueOf(98_500));
        assertThat(repository.reserved("INVESTOR_B")).isZero();
        assertThat(repository.backing()).isEqualTo(BigInteger.valueOf(1_500));
    }

    @Test
    void t19_javaRunsIssueAtomicDvpAndRedemptionOnBesu() {
        String investorA = required("TOKENRAIL_INVESTOR_A_ADDRESS");
        String investorAKey = required("TOKENRAIL_INVESTOR_A_KEY_FILE");
        String investorB = participantAddress();
        String investorBKey = participantKeyFile();
        String issuerKey = properties.issuerPrivateKeyFile();
        String unique = UUID.randomUUID().toString();

        contracts.setIdentity(issuerKey, investorA, ContractCommands.bytes32(id("A-" + unique)));
        contracts.setIdentity(issuerKey, investorB, ContractCommands.bytes32(id("B-" + unique)));
        BigInteger initialBondA = contracts.balanceOf(properties.bondTokenAddress(), investorA);
        BigInteger initialBondB = contracts.balanceOf(properties.bondTokenAddress(), investorB);
        BigInteger initialCashA = contracts.balanceOf(properties.depositTokenAddress(), investorA);
        BigInteger initialCashB = contracts.balanceOf(properties.depositTokenAddress(), investorB);

        contracts.issueBond(issuerKey, ContractCommands.bytes32(id("issue-" + unique)),
                investorA, BigInteger.TEN);
        WorkflowRepository.RequestResult conversion = workflows.requestConversion(
                "INVESTOR_B", "t19-convert-" + unique, id("cash-" + unique),
                BigInteger.valueOf(15_000));
        awaitStatus(conversion.operationId(), OperationStatus.COMPLETED);

        contracts.approve(investorAKey, properties.bondTokenAddress(), properties.dvpAddress(),
                BigInteger.TEN);
        contracts.approve(investorBKey, properties.depositTokenAddress(), properties.dvpAddress(),
                BigInteger.valueOf(10_000));
        byte[] instructionId = ContractCommands.bytes32(id("dvp-" + unique));
        contracts.createInstruction(investorAKey, instructionId, investorB,
                BigInteger.TEN, BigInteger.valueOf(10_000),
                java.time.Instant.now().getEpochSecond() + 3_600);
        contracts.confirmInstruction(investorBKey, instructionId);
        contracts.executeInstruction(issuerKey, instructionId);

        assertThat(contracts.balanceOf(properties.bondTokenAddress(), investorA))
                .isEqualTo(initialBondA);
        assertThat(contracts.balanceOf(properties.bondTokenAddress(), investorB))
                .isEqualTo(initialBondB.add(BigInteger.TEN));
        assertThat(contracts.balanceOf(properties.depositTokenAddress(), investorA))
                .isEqualTo(initialCashA.add(BigInteger.valueOf(10_000)));
        assertThat(contracts.balanceOf(properties.depositTokenAddress(), investorB))
                .isEqualTo(initialCashB.add(BigInteger.valueOf(5_000)));

        WorkflowRepository.RequestResult redemption = workflows.requestRedemption(
                "INVESTOR_B", "t19-redeem-" + unique, id("redeem-" + unique),
                BigInteger.valueOf(5_000));
        awaitStatus(redemption.operationId(), OperationStatus.CREDITED);
        assertThat(contracts.balanceOf(properties.depositTokenAddress(), investorB))
                .isEqualTo(initialCashB);
        assertThat(repository.available("INVESTOR_B")).isEqualTo(BigInteger.valueOf(90_000));
        assertThat(repository.backing()).isEqualTo(BigInteger.valueOf(10_000));
    }

    private void awaitInjectedFailure(UUID operationId) {
        for (int attempt = 0; attempt < 20; attempt++) {
            repository.makeReadyForTest(operationId);
            try {
                workflows.processOne();
            } catch (WorkflowFaults.InjectedFailure expected) {
                return;
            }
            pause();
        }
        throw new AssertionError("Injected failure was not reached");
    }

    private void awaitStatus(UUID operationId, OperationStatus expected) {
        for (int attempt = 0; attempt < 30; attempt++) {
            if (repository.getOperation(operationId).status() == expected) {
                return;
            }
            repository.makeReadyForTest(operationId);
            workflows.processOne();
            pause();
        }
        throw new AssertionError("Operation did not reach " + expected);
    }

    private static void pause() {
        try {
            Thread.sleep(250);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static String id(String value) {
        return Hash.sha3String(value);
    }

    private String participantAddress() {
        return required("TOKENRAIL_PARTICIPANT_INVESTOR_B_ADDRESS");
    }

    private String participantKeyFile() {
        return required("TOKENRAIL_PARTICIPANT_INVESTOR_B_KEY_FILE");
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }
}
