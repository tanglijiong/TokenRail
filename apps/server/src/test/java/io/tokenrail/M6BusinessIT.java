package io.tokenrail;

import static org.assertj.core.api.Assertions.assertThat;

import io.tokenrail.config.DeploymentGuard;
import io.tokenrail.config.TokenRailProperties;
import io.tokenrail.gateway.ContractCommands;
import io.tokenrail.gateway.BlockchainGateway;
import io.tokenrail.ledger.DepositWorkflowService;
import io.tokenrail.ledger.OperationStatus;
import io.tokenrail.ledger.WorkflowRepository;
import io.tokenrail.projection.ProjectionRepository;
import io.tokenrail.projection.TransferIndexer;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.web3j.crypto.Hash;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.profiles.active=reliability-test", "tokenrail.worker-enabled=false",
        "tokenrail.indexer-enabled=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class M6BusinessIT {
    @Autowired WorkflowRepository ledger;
    @Autowired DepositWorkflowService worker;
    @Autowired ProjectionRepository projections;
    @Autowired TransferIndexer indexer;
    @Autowired ContractCommands contracts;
    @Autowired BlockchainGateway chain;
    @Autowired TokenRailProperties properties;
    @Autowired DeploymentGuard guard;
    @Autowired JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Value("${local.server.port}") int port;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    final tools.jackson.databind.ObjectMapper json = new tools.jackson.databind.ObjectMapper();

    @BeforeAll
    void setup() {
        ledger.truncateForTest();
        ledger.registerDeployment(properties.deploymentId(), properties.chainId(),
                properties.genesisHash(), properties.depositTokenAddress());
        ledger.registerContract(properties.deploymentId(), properties.identityRegistryAddress(), "REGISTRY");
        ledger.registerContract(properties.deploymentId(), properties.bondTokenAddress(), "BOND");
        ledger.registerContract(properties.deploymentId(), properties.dvpAddress(), "DVP");
        ledger.registerContract(properties.deploymentId(), properties.escrowAddress(), "ESCROW");
        guard.initialize();
        projections.registerAsset(properties.deploymentId(), properties.depositTokenAddress(), "TRCNY", 2, properties.deploymentBlock());
        projections.registerAsset(properties.deploymentId(), properties.bondTokenAddress(), "ABC2029", 0, properties.deploymentBlock());
        long initial = Math.max(0, properties.deploymentBlock() - 1);
        projections.initializeProjection(properties.deploymentId(), 1, initial,
                chain.blockHash(initial));
        ledger.registerParticipant("INVESTOR_A", required("TOKENRAIL_PARTICIPANT_INVESTOR_A_ADDRESS"),
                "investor-a-token", required("TOKENRAIL_PARTICIPANT_INVESTOR_A_KEY_FILE"), BigInteger.ZERO);
        ledger.registerParticipant("INVESTOR_B", required("TOKENRAIL_PARTICIPANT_INVESTOR_B_ADDRESS"),
                "investor-b-token", required("TOKENRAIL_PARTICIPANT_INVESTOR_B_KEY_FILE"), BigInteger.valueOf(2_000_000));
        ledger.registerParticipant("OPERATOR", required("TOKENRAIL_PARTICIPANT_OPERATOR_ADDRESS"),
                "operator-token", required("TOKENRAIL_PARTICIPANT_OPERATOR_KEY_FILE"), BigInteger.ZERO);
        for (String role : new String[]{"ISSUER","OPERATOR","COMPLIANCE","ATTESTOR","RECONCILER"})
            jdbc.update("insert into participant_role values (?, 'OPERATOR', ?) on conflict do nothing", properties.deploymentId(), role);
        String identity = id("identity");
        contracts.setIdentity(properties.issuerPrivateKeyFile(), walletA(), ContractCommands.bytes32(identity));
        contracts.setIdentity(properties.issuerPrivateKeyFile(), walletB(), ContractCommands.bytes32(id("identity-b")));
    }

    @Test
    void t20_businessApisDriveDvpPaymentAndProjectionWithoutInventingSuccess() throws Exception {
        String unique = UUID.randomUUID().toString();
        BigInteger aBond = contracts.balanceOf(properties.bondTokenAddress(), walletA());
        BigInteger bBond = contracts.balanceOf(properties.bondTokenAddress(), walletB());
        BigInteger aCash = contracts.balanceOf(properties.depositTokenAddress(), walletA());
        BigInteger bCash = contracts.balanceOf(properties.depositTokenAddress(), walletB());

        UUID issue = submit("operator-token", "/bond-issues", Map.of(
                "issueId", id("issue-" + unique), "recipient", walletA(), "quantity", "10"));
        finish(issue, OperationStatus.SUCCEEDED);
        var conversion = ledger.createConversion(properties.deploymentId(), "INVESTOR_B", "cash-" + unique,
                io.tokenrail.support.Hashes.sha256(unique), id("cash-" + unique), BigInteger.valueOf(15_000));
        finish(conversion.operationId(), OperationStatus.COMPLETED);

        UUID approveBond = submit("investor-a-token", "/allowances", Map.of(
                "assetAddress", properties.bondTokenAddress(), "spender", properties.dvpAddress(), "amount", "10"));
        UUID approveCash = submit("investor-b-token", "/allowances", Map.of(
                "assetAddress", properties.depositTokenAddress(), "spender", properties.dvpAddress(), "amount", "10000"));
        finish(approveBond, OperationStatus.SUCCEEDED); finish(approveCash, OperationStatus.SUCCEEDED);

        String settlementId = id("settlement-" + unique);
        UUID create = submit("investor-a-token", "/settlements", Map.of(
                "instructionId", settlementId, "buyer", walletB(), "bondQuantity", "10",
                "cashAmount", "10000", "expiry", Instant.now().plusSeconds(120).toString(),
                "externalTradeRef", "T20-" + unique));
        finish(create, OperationStatus.SUCCEEDED);
        String idempotentId = id("idempotent-" + unique);
        String idempotentExpiry = Instant.now().plusSeconds(120).toString();
        Map<String, Object> idempotentBody = Map.of(
                "instructionId", idempotentId, "buyer", walletB(), "bondQuantity", "1",
                "cashAmount", "1", "expiry", idempotentExpiry);
        String idempotencyKey = "same-create-" + unique;
        UUID first = submitWithKey("investor-a-token", "/settlements", idempotencyKey, idempotentBody);
        UUID replay = submitWithKey("investor-a-token", "/settlements", idempotencyKey, idempotentBody);
        assertThat(replay).isEqualTo(first);
        assertThat(post("investor-a-token", "/settlements", idempotencyKey, Map.of(
                "instructionId", idempotentId, "buyer", walletB(), "bondQuantity", "2",
                "cashAmount", "1", "expiry", idempotentExpiry)).statusCode()).isEqualTo(409);
        finish(first, OperationStatus.SUCCEEDED);
        HttpResponse<String> same = post("investor-a-token", "/settlements/" + settlementId + "/confirm", "wrong-party", null);
        assertThat(same.statusCode()).isEqualTo(403);
        UUID confirm = submit("investor-b-token", "/settlements/" + settlementId + "/confirm", null);
        finish(confirm, OperationStatus.SUCCEEDED);
        UUID execute = submit("operator-token", "/settlements/" + settlementId + "/execute", null);
        finish(execute, OperationStatus.SUCCEEDED);
        indexer.catchUpFully();
        HttpResponse<String> settlement = get("investor-a-token", "/settlements/" + settlementId);
        assertThat(settlement.statusCode()).isEqualTo(200);
        assertThat(json.readTree(settlement.body()).at("/chain/state").asInt()).isEqualTo(3);
        assertThat(json.readTree(settlement.body()).get("syncStatus").asText()).isEqualTo("PROJECTED");
        assertThat(contracts.balanceOf(properties.bondTokenAddress(), walletA())).isEqualTo(aBond);
        assertThat(contracts.balanceOf(properties.bondTokenAddress(), walletB())).isEqualTo(bBond.add(BigInteger.TEN));
        assertThat(contracts.balanceOf(properties.depositTokenAddress(), walletA())).isEqualTo(aCash.add(BigInteger.valueOf(10_000)));
        assertThat(contracts.balanceOf(properties.depositTokenAddress(), walletB())).isEqualTo(bCash.add(BigInteger.valueOf(5_000)));

        UUID escrowApproval = submit("investor-b-token", "/allowances", Map.of(
                "assetAddress", properties.depositTokenAddress(), "spender", properties.escrowAddress(), "amount", "1000"));
        finish(escrowApproval, OperationStatus.SUCCEEDED);
        String paymentId = id("payment-" + unique);
        UUID fund = submit("investor-b-token", "/payments", Map.of(
                "paymentId", paymentId, "payee", walletA(), "attestor", operator(), "amount", "1000",
                "deadline", Instant.now().plusSeconds(120).toString()));
        finish(fund, OperationStatus.SUCCEEDED);
        assertThat(contracts.balanceOf(properties.depositTokenAddress(), walletA())).isEqualTo(aCash.add(BigInteger.valueOf(10_000)));
        assertThat(post("investor-a-token", "/payments/" + paymentId + "/release", "unauthorized", null).statusCode()).isEqualTo(403);
        UUID release = submit("operator-token", "/payments/" + paymentId + "/release", null);
        finish(release, OperationStatus.SUCCEEDED);
        indexer.catchUpFully();
        var payment = json.readTree(get("investor-b-token", "/payments/" + paymentId).body());
        assertThat(payment.at("/chain/state").asInt()).isEqualTo(2);
        assertThat(payment.get("syncStatus").asText()).isEqualTo("PROJECTED");
        assertThat(contracts.balanceOf(properties.depositTokenAddress(), walletA())).isEqualTo(aCash.add(BigInteger.valueOf(11_000)));

        HttpResponse<String> operations = get("operator-token", "/operations");
        assertThat(operations.body()).contains("EXECUTE_SETTLEMENT", "RELEASE_PAYMENT", "mined_block");
        assertThat(get("investor-b-token", "/session").body()).contains(properties.deploymentId(), "indexedHash");
    }

    @Test
    void t09_paymentRefundAndComplianceApisEnforceRolesAndState() throws Exception {
        String unique = UUID.randomUUID().toString();
        var cash = ledger.createConversion(properties.deploymentId(), "INVESTOR_B", "refund-cash-" + unique,
                io.tokenrail.support.Hashes.sha256("refund-cash-" + unique),
                id("refund-cash-" + unique), BigInteger.valueOf(1_000));
        finish(cash.operationId(), OperationStatus.COMPLETED);
        assertThat(post("investor-b-token", "/identities/" + walletA() + "/status", "no-role", Map.of(
                "participantRef", id("x"), "validUntil", Instant.now().plusSeconds(300).toString(),
                "active", true, "frozen", false, "canHoldCash", true, "canHoldBond", true)).statusCode()).isEqualTo(403);
        UUID identity = submit("operator-token", "/identities/" + walletA() + "/status", Map.of(
                "participantRef", id("identity-refresh-" + unique), "validUntil", Instant.now().plusSeconds(600).toString(),
                "active", true, "frozen", false, "canHoldCash", true, "canHoldBond", true));
        finish(identity, OperationStatus.SUCCEEDED);
        assertThat(get("investor-b-token", "/identities/" + walletA()).body()).contains("participantRef", "canHoldCash");

        UUID approval = submit("investor-b-token", "/allowances", Map.of(
                "assetAddress", properties.depositTokenAddress(), "spender", properties.escrowAddress(), "amount", "200"));
        finish(approval, OperationStatus.SUCCEEDED);
        String paymentId = id("refund-" + unique);
        UUID fund = submit("investor-b-token", "/payments", Map.of(
                "paymentId", paymentId, "payee", walletA(), "attestor", operator(), "amount", "200",
                "deadline", Instant.now().plusSeconds(8).toString()));
        finish(fund, OperationStatus.SUCCEEDED);
        UUID early = submit("investor-b-token", "/payments/" + paymentId + "/refund", null);
        finish(early, OperationStatus.REVERTED);
        Thread.sleep(9_000);
        UUID refund = submit("investor-b-token", "/payments/" + paymentId + "/refund", null);
        finish(refund, OperationStatus.SUCCEEDED);
        assertThat(json.readTree(get("investor-b-token", "/payments/" + paymentId).body()).at("/chain/state").asInt()).isEqualTo(3);
        HttpResponse<String> terminalResponse = post("operator-token", "/payments/" + paymentId + "/release", "after-refund", null);
        assertThat(terminalResponse.statusCode()).isEqualTo(202);
        UUID terminal = UUID.fromString(json.readTree(terminalResponse.body()).get("operationId").asText());
        finish(terminal, OperationStatus.REVERTED);
    }

    private UUID submit(String token, String path, Object body) throws Exception {
        return submitWithKey(token, path, UUID.randomUUID().toString(), body);
    }
    private UUID submitWithKey(String token, String path, String key, Object body) throws Exception {
        HttpResponse<String> response = post(token, path, key, body);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        return UUID.fromString(json.readTree(response.body()).get("operationId").asText());
    }
    private HttpResponse<String> post(String token, String path, String key, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                .header("Authorization", "Bearer " + token);
        if (key != null) request.header("Idempotency-Key", key);
        if (body == null) request.POST(HttpRequest.BodyPublishers.noBody());
        else request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> get(String token, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private void finish(UUID operation, OperationStatus expected) throws Exception {
        await(() -> {
            if (ledger.getOperation(operation).status() == expected) return true;
            ledger.makeReadyForTest(operation);
            try { worker.processOne(); } catch (RuntimeException transientFailure) {
                // The scheduled worker logs and retries uncertain broadcasts in the same way.
            }
            return ledger.getOperation(operation).status() == expected;
        }, 25);
    }
    private static void await(Callable<Boolean> condition, int seconds) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        while (System.nanoTime() < end) { if (condition.call()) return; Thread.sleep(150); }
        throw new AssertionError("Timed out waiting for operation");
    }
    private String walletA() { return required("TOKENRAIL_PARTICIPANT_INVESTOR_A_ADDRESS"); }
    private String walletB() { return required("TOKENRAIL_PARTICIPANT_INVESTOR_B_ADDRESS"); }
    private String operator() { return required("TOKENRAIL_PARTICIPANT_OPERATOR_ADDRESS"); }
    private static String required(String name) {
        String value = System.getenv(name); if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required"); return value;
    }
    private static String id(String source) { return Hash.sha3String(source + UUID.randomUUID()); }
}
