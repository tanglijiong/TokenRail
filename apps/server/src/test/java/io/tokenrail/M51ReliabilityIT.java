package io.tokenrail;

import static org.assertj.core.api.Assertions.*;

import io.tokenrail.api.*;
import io.tokenrail.config.*;
import io.tokenrail.gateway.*;
import io.tokenrail.ledger.*;
import io.tokenrail.projection.*;
import io.tokenrail.reconciliation.*;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import org.springframework.jdbc.core.JdbcTemplate;
import org.web3j.crypto.Hash;

@EnabledIfEnvironmentVariable(named = "TOKENRAIL_M51_RUN_DIR", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.profiles.active=reliability-test", "tokenrail.worker-enabled=false", "tokenrail.indexer-enabled=false",
        "tokenrail.lease-seconds=2"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class M51ReliabilityIT {
    @Autowired WorkflowRepository ledger;
    @Autowired DepositWorkflowService workflows;
    @Autowired BlockchainGateway chain;
    @Autowired org.web3j.protocol.Web3j web3j;
    @Autowired ContractCommands contracts;
    @Autowired WorkflowFaults faults;
    @Autowired IndexerFaults indexerFaults;
    @Autowired ProjectionRepository projections;
    @Autowired TransferIndexer indexer;
    @Autowired ReconciliationService reconciliations;
    @Autowired TokenRailProperties properties;
    @Autowired DeploymentGuard guard;
    @Autowired JdbcTemplate jdbc;
    @Autowired AdminJobs jobs;
    @org.springframework.beans.factory.annotation.Value("${local.server.port}") int port;
    final Path run = Path.of(System.getenv().getOrDefault("TOKENRAIL_M51_RUN_DIR", "/tmp"));
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @BeforeAll void identities() {
        contracts.setIdentity(properties.issuerPrivateKeyFile(), investor(), ContractCommands.bytes32(id()));
        contracts.setIdentity(properties.issuerPrivateKeyFile(), System.getenv("TOKENRAIL_INVESTOR_A_ADDRESS"), ContractCommands.bytes32(id()));
        ledger.registerParticipant("AUDITOR", System.getenv("TOKENRAIL_INVESTOR_A_ADDRESS"), "auditor-token",
                System.getenv("TOKENRAIL_INVESTOR_A_KEY_FILE"), BigInteger.ZERO);
        jdbc.update("update deployment_participant set role = 'RECONCILER' where deployment_id = ? and participant_id = 'AUDITOR'", properties.deploymentId());
    }

    @Test @Order(1) void r01_realSigkillAndRestartAtThreeDurableBoundaries() throws Exception {
        for (String stage : List.of("CLAIMED", "SIGNED", "BROADCAST")) {
            BigInteger before = ledger.backing();
            UUID operation = convert(100);
            Path marker = run.resolve(stage + ".marker");
            Process child = child(stage, marker, Map.of());
            try { await(() -> Files.exists(marker), 30); }
            finally { child.destroyForcibly(); assertThat(child.waitFor(10, TimeUnit.SECONDS)).isTrue(); }
            assertThat(jdbc.queryForObject("select status from outbox_task where operation_id = ?", String.class, operation)).isEqualTo("PROCESSING");
            String signedHash = ledger.findTransaction(operation).map(ChainTransaction::transactionHash).orElse(null);
            Process restarted = child("NONE", run.resolve("unused"), Map.of());
            try { await(() -> ledger.getOperation(operation).status() == OperationStatus.COMPLETED, 30); }
            finally { restarted.destroyForcibly(); restarted.waitFor(10, TimeUnit.SECONDS); }
            assertThat(ledger.backing()).isEqualTo(before.add(BigInteger.valueOf(100)));
            assertThat(ledger.journalCount(operation, "CONVERSION_BACKING")).isOne();
            assertThat(jdbc.queryForObject("select count(*) from chain_transaction where operation_id = ?", Integer.class, operation)).isOne();
            if (signedHash != null) assertThat(ledger.findTransaction(operation).orElseThrow().transactionHash()).isEqualTo(signedHash);
            Files.writeString(run.resolve(stage + ".evidence"), "SIGKILL; automatic restart; operation=" + operation + "; hash=" + ledger.findTransaction(operation).orElseThrow().transactionHash() + "; journals=1\n");
        }
    }

    @Test @Order(2) void r01_staleWorkersCannotAllocateSignReleaseOrPost() throws Exception {
        UUID operation = convert(10);
        DepositOperation stale = ledger.claimNext().orElseThrow();
        Thread.sleep(2200);
        DepositOperation owner = ledger.claimNext().orElseThrow();
        assertThat(owner.claimVersion()).isGreaterThan(stale.claimVersion());
        assertThatThrownBy(() -> ledger.markWaiting(stale)).isInstanceOf(StaleClaimException.class);
        assertThatThrownBy(() -> ledger.storeSigned(stale, "0x00", id())).isInstanceOf(StaleClaimException.class);
        assertThatThrownBy(() -> ledger.completeConversion(stale, () -> {})).isInstanceOf(StaleClaimException.class);
        assertThatThrownBy(() -> ledger.allocateNonce(stale, "mintForConversion", investor(), BigInteger.ZERO)).isInstanceOf(StaleClaimException.class);
        ledger.markWaiting(owner);
        finish(operation, OperationStatus.COMPLETED);
        assertThat(ledger.journalCount(operation, "CONVERSION_BACKING")).isOne();
        UUID competing = convert(10);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = executor.invokeAll(List.<Callable<Optional<DepositOperation>>>of(ledger::claimNext, ledger::claimNext));
            assertThat(results.stream().map(f -> { try { return f.get(); } catch (Exception e) { throw new RuntimeException(e); } }).filter(Optional::isPresent).count()).isOne();
        }
        Thread.sleep(2200);
        finish(competing, OperationStatus.COMPLETED);
    }

    @Test @Order(3) void r03_inflightMintBurnHistoricalAndCorruptedBacking() throws Exception {
        UUID mint = convert(1000);
        faults.failBeforeNextConversionCommit();
        assertThatThrownBy(workflows::processOne).isInstanceOf(WorkflowFaults.InjectedFailure.class);
        Checkpoint minted = indexer.catchUpFully();
        var pendingMint = reconciliations.reconcileAt(minted.blockNumber());
        assertThat(pendingMint.status()).isEqualTo("MATCH");
        assertThat(pendingMint.bankEvidence().pendingMints()).isEqualTo(BigInteger.valueOf(1000));
        finish(mint, OperationStatus.COMPLETED);
        UUID burn = workflows.requestRedemption("INVESTOR_B", id(), id(), BigInteger.valueOf(200)).operationId();
        faults.failBeforeNextRedemptionCredit();
        assertThatThrownBy(workflows::processOne).isInstanceOf(WorkflowFaults.InjectedFailure.class);
        Checkpoint burned = indexer.catchUpFully();
        var pendingBurn = reconciliations.reconcileAt(burned.blockNumber());
        assertThat(pendingBurn.status()).isEqualTo("MATCH");
        assertThat(pendingBurn.bankEvidence().pendingBurns()).isEqualTo(BigInteger.valueOf(200));
        finish(burn, OperationStatus.CREDITED);
        var historical = reconciliations.reconcileAt(minted.blockNumber());
        assertThat(historical.status()).isEqualTo("MATCH");
        assertThat(historical.bankEvidence().futurePostings()).isEqualTo(BigInteger.valueOf(-200));
        jdbc.update("update deployment_backing set amount = amount + 7 where deployment_id = ?", properties.deploymentId());
        assertThat(reconciliations.reconcileAt(burned.blockNumber()).status()).isEqualTo("BACKING_MISMATCH");
        jdbc.update("update deployment_backing set amount = amount - 7 where deployment_id = ?", properties.deploymentId());
        assertThat(reconciliations.get(pendingMint.reconciliationId()).bankEvidence()).isEqualTo(pendingMint.bankEvidence());
    }

    @Test @Order(4) void r04_r05_missingPositionFailedRebuildAndExactScopeClosure() throws Exception {
        Checkpoint old = indexer.catchUpFully();
        jdbc.update("delete from position_snapshot where deployment_id = ? and projection_version = ? and block_number = ? and wallet_address = ? and asset_address = ?",
                old.deploymentId(), old.projectionVersion(), old.blockNumber(), investor().toLowerCase(), properties.depositTokenAddress().toLowerCase());
        var broken = reconciliations.reconcileAt(old.blockNumber());
        assertThat(broken.status()).isEqualTo("POSITION_MISMATCH");
        UUID extra = convert(20); finish(extra, OperationStatus.COMPLETED);
        Checkpoint newer = indexer.catchUpFully();
        indexerFaults.failBeforeNextBlockCommit();
        assertThatThrownBy(() -> indexer.rebuildTo(newer.blockNumber())).isInstanceOf(IndexerFaults.InjectedIndexerFailure.class);
        assertThat(projections.activeCheckpoint(old.deploymentId()).orElseThrow()).isEqualTo(newer);
        Checkpoint rebuilt = indexer.rebuildTo(newer.blockNumber());
        assertThat(rebuilt.projectionVersion()).isEqualTo(old.projectionVersion() + 1);
        assertThat(reconciliations.reconcileAt(newer.blockNumber()).status()).isEqualTo("MATCH");
        assertThat(reconciliations.get(broken.reconciliationId()).breaks()).anyMatch(b -> b.resolutionStatus().equals("OPEN"));
        var repaired = reconciliations.reconcileAt(old.blockNumber());
        assertThat(repaired.status()).isEqualTo("MATCH");
        assertThat(reconciliations.get(broken.reconciliationId()).breaks()).allMatch(b -> repaired.reconciliationId().equals(b.resolvedByReconciliationId()));
        assertThat(ledger.journalCount(extra, "CONVERSION_BACKING")).isOne();
    }

    @Test @Order(5) void r06_httpRolesIdempotencyJobsAndOwnership() throws Exception {
        indexer.catchUpFully();
        assertThat(request(port, "POST", "/reconciliations", "investor-b-token", "denied").statusCode()).isEqualTo(403);
        assertThat(request(port, "GET", "/positions?wallet=" + System.getenv("TOKENRAIL_INVESTOR_A_ADDRESS"), "investor-b-token", null).statusCode()).isEqualTo(403);
        assertThat(request(port, "GET", "/positions", "investor-b-token", null).body()).contains("deploymentId", "blockHash", "\"balance\":\"");
        var first = request(port, "POST", "/projections/rebuild", "auditor-token", "rebuild-key");
        assertThat(first.statusCode()).isEqualTo(202);
        UUID job = jsonJobId(first.body());
        assertThat(jsonJobId(request(port, "POST", "/projections/rebuild", "auditor-token", "rebuild-key").body())).isEqualTo(job);
        assertThat(jobs.processOne()).isTrue();
        assertThat(jobs.get(job, "AUDITOR").status()).isEqualTo("COMPLETED");
        assertThat(request(port, "GET", "/jobs/" + job, "investor-b-token", null).statusCode()).isEqualTo(403);
        assertThatThrownBy(() -> jobs.get(job, "SOMEONE_ELSE")).isInstanceOf(ForbiddenException.class);
        var recon = request(port, "POST", "/reconciliations", "auditor-token", "recon-key");
        UUID reconJob = jsonJobId(recon.body());
        assertThat(request(port, "POST", "/reconciliations?block=" + properties.deploymentBlock(), "auditor-token", "recon-key").statusCode()).isEqualTo(409);
        jobs.processOne();
        assertThat(jobs.get(reconJob, "AUDITOR").status()).isEqualTo("COMPLETED");
        assertThat(request(port, "GET", "/reconciliations/" + reconJob, "auditor-token", null).statusCode()).isEqualTo(200);
        // Crash after work committed but before the job result update: same rebuild key returns its original version.
        int version = Integer.parseInt(jobs.get(job, "AUDITOR").resultId());
        jdbc.update("update admin_job set status = 'RUNNING', lease_until = now() - interval '1 second' where job_id = ?", job);
        jobs.processOne();
        assertThat(Integer.parseInt(jobs.get(job, "AUDITOR").resultId())).isEqualTo(version);
    }

    @Test @Order(6) void r03_r04_concurrentReadsBankPostingRebuildAndZeroSelfTransfers() throws Exception {
        String a = System.getenv("TOKENRAIL_INVESTOR_A_ADDRESS");
        var signer = chain.credentials(System.getenv("TOKENRAIL_INVESTOR_A_KEY_FILE"));
        var transfer = new org.web3j.abi.datatypes.Function("transfer", List.of(
                new org.web3j.abi.datatypes.Address(investor()), new org.web3j.abi.datatypes.generated.Uint256(BigInteger.ZERO)), List.of());
        var zero = chain.prepareCall(signer, chain.pendingNonce(signer.getAddress()), properties.depositTokenAddress(), transfer);
        chain.broadcast(zero.rawTransaction());
        signer = chain.credentials(System.getenv("TOKENRAIL_PARTICIPANT_INVESTOR_B_KEY_FILE"));
        var self = chain.prepareCall(signer, chain.pendingNonce(signer.getAddress()), properties.depositTokenAddress(),
                new org.web3j.abi.datatypes.Function("transfer", List.of(new org.web3j.abi.datatypes.Address(investor()),
                        new org.web3j.abi.datatypes.generated.Uint256(BigInteger.ONE)), List.of()));
        chain.broadcast(self.rawTransaction());
        Checkpoint fixed = indexer.catchUpFully();
        assertThat(reconciliations.reconcileAt(fixed.blockNumber()).status()).isEqualTo("MATCH");
        UUID operation = convert(9);
        try (var executor = Executors.newFixedThreadPool(3)) {
            Future<?> posting = executor.submit(() -> { try { finish(operation, OperationStatus.COMPLETED); } catch (Exception e) { throw new RuntimeException(e); } });
            Future<?> rebuilding = executor.submit(() -> indexer.rebuildTo(fixed.blockNumber()));
            for (int i = 0; i < 8; i++) {
                assertThat(reconciliations.reconcileAt(fixed.blockNumber()).status()).isEqualTo("MATCH");
                var current = projections.activeCheckpoint(properties.deploymentId()).orElseThrow();
                var positions = projections.currentPositions(current.deploymentId(), current.projectionVersion());
                for (Position position : positions)
                    assertThat(position.balance()).isEqualTo(chain.tokenBalanceAt(position.assetAddress(), position.walletAddress(), position.blockNumber()));
            }
            posting.get(15, TimeUnit.SECONDS); rebuilding.get(15, TimeUnit.SECONDS);
        }
        Checkpoint latest = indexer.catchUpFully();
        assertThat(reconciliations.reconcileAt(latest.blockNumber()).status()).isEqualTo("MATCH");
        assertThat(ledger.journalCount(operation, "CONVERSION_BACKING")).isOne();
    }

    @Test @Order(7) void r04_realRebuildProcessCrashKeepsActiveAndResumesSameVersion() throws Exception {
        Checkpoint before = indexer.catchUpFully();
        BigInteger backing = ledger.backing();
        var job = jobs.submit("AUDITOR", "REBUILD", id(), null, before.blockNumber());
        Path marker = run.resolve("REBUILD.marker");
        Process child = child("REBUILD", marker, Map.of());
        try { await(() -> Files.exists(marker), 30); }
        finally { child.destroyForcibly(); child.waitFor(10, TimeUnit.SECONDS); }
        assertThat(projections.activeCheckpoint(before.deploymentId()).orElseThrow()).isEqualTo(before);
        Process restarted = child("NONE", run.resolve("unused-rebuild"), Map.of());
        try { await(() -> jobs.get(job.jobId(), "AUDITOR").status().equals("COMPLETED"), 30); }
        finally { restarted.destroyForcibly(); restarted.waitFor(10, TimeUnit.SECONDS); }
        assertThat(projections.activeCheckpoint(before.deploymentId()).orElseThrow().projectionVersion()).isEqualTo(before.projectionVersion() + 1);
        assertThat(ledger.backing()).isEqualTo(backing);
        Files.writeString(run.resolve("rebuild.evidence"), "SIGKILL inside block transaction; prior active version preserved; restarted same job/version; backing unchanged\n");
    }

    @Test @Order(9) void r02_realSameChainIdResetStopsOldSignedTaskAndNewDeploymentIsIsolated() throws Exception {
        UUID old = convert(37);
        DepositOperation claimed = ledger.claimNext().orElseThrow();
        var signer = chain.signer(claimed);
        var transaction = ledger.allocateNonce(claimed, "mintForConversion", signer.getAddress(), chain.pendingNonce(signer.getAddress()));
        var signed = chain.prepare(claimed, signer, transaction.nonce());
        ledger.storeSigned(claimed, signed.rawTransaction(), signed.transactionHash());
        BigInteger oldBacking = ledger.backing();
        command("cast", "rpc", "--rpc-url", properties.rpcUrl(), "anvil_reset");
        assertThat(chain.chainId()).isEqualTo(BigInteger.valueOf(properties.chainId()));
        assertThatThrownBy(workflows::processOne).isInstanceOf(IllegalStateException.class);
        assertThat(ledger.journalCount(old, "CONVERSION_BACKING")).isZero();
        assertThat(chain.receipt(signed.transactionHash())).isEmpty();
        String issuer = chain.credentials(properties.issuerPrivateKeyFile()).getAddress();
        for (String address : List.of(issuer, investor(), System.getenv("TOKENRAIL_INVESTOR_A_ADDRESS")))
            command("cast", "rpc", "--rpc-url", properties.rpcUrl(), "anvil_setBalance", address, "0x3635c9adc5dea00000");
        command("cast", "rpc", "--rpc-url", properties.rpcUrl(), "anvil_setNextBlockBaseFeePerGas", "0x0");
        Path root = Path.of(System.getProperty("user.dir")).resolve("../..").normalize();
        command(root.resolve("scripts/deploy-m3-contracts.sh").toString());
        String manifest = Files.readString(run.resolve("deployment-manifest.json"));
        var json = new tools.jackson.databind.ObjectMapper().readTree(manifest);
        String replacement = json.get("deploymentId").asText();
        assertThat(replacement).isNotEqualTo(properties.deploymentId());
        assertThatThrownBy(guard::verify).isInstanceOf(IllegalStateException.class);
        Map<String, String> env = new HashMap<>();
        env.put("TOKENRAIL_DEPLOYMENT_ID", replacement);
        env.put("TOKENRAIL_GENESIS_HASH", json.get("genesisHash").asText());
        env.put("TOKENRAIL_DEPLOYMENT_BLOCK", json.get("deploymentBlock").asText());
        env.put("TOKENRAIL_DEPOSIT_TOKEN_ADDRESS", json.at("/contracts/DepositToken/address").asText());
        env.put("TOKENRAIL_BOND_TOKEN_ADDRESS", json.at("/contracts/BondToken/address").asText());
        env.put("TOKENRAIL_IDENTITY_REGISTRY_ADDRESS", json.at("/contracts/IdentityRegistry/address").asText());
        env.put("TOKENRAIL_DVP_ADDRESS", json.at("/contracts/DvPSettlement/address").asText());
        env.put("TOKENRAIL_ESCROW_ADDRESS", json.at("/contracts/PaymentEscrow/address").asText());
        int replacementPort;
        try (var socket = new java.net.ServerSocket(0)) { replacementPort = socket.getLocalPort(); }
        env.put("M51_HTTP_PORT", Integer.toString(replacementPort));
        var issuerSigner = chain.credentials(properties.issuerPrivateKeyFile());
        var identityCall = new org.web3j.abi.datatypes.Function("setIdentity", List.of(
                new org.web3j.abi.datatypes.Address(investor()), new org.web3j.abi.datatypes.generated.Bytes32(ContractCommands.bytes32(id())),
                new org.web3j.abi.datatypes.generated.Uint64(4102444800L), new org.web3j.abi.datatypes.Bool(true),
                new org.web3j.abi.datatypes.Bool(false), new org.web3j.abi.datatypes.Bool(true), new org.web3j.abi.datatypes.Bool(true)), List.of());
        var identityTx = chain.prepareCall(issuerSigner, chain.pendingNonce(issuerSigner.getAddress()), env.get("TOKENRAIL_IDENTITY_REGISTRY_ADDRESS"), identityCall);
        chain.broadcast(identityTx.rawTransaction());
        Process replacementWorker = child("NONE", run.resolve("unused2"), env);
        try {
            await(() -> jdbc.queryForObject("select count(*) from deployment_account where deployment_id = ?", Integer.class, replacement) == 2, 30);
            Thread.sleep(2500);
            assertThat(jdbc.queryForObject("select amount from deployment_backing where deployment_id = ?", java.math.BigDecimal.class, replacement).toBigIntegerExact()).isZero();
            assertThat(ledger.backing()).isEqualTo(oldBacking);
            assertThat(ledger.journalCount(old, "CONVERSION_BACKING")).isZero();
            assertThat(chain.receipt(signed.transactionHash())).isEmpty();
            assertThat(web3j.ethGetTransactionByHash(signed.transactionHash()).send().getTransaction()).isEmpty();
            assertThat(ledger.getOperation(old).claimVersion()).isEqualTo(claimed.claimVersion());
            String payload = "{\"businessId\":\"" + id() + "\",\"amount\":11}";
            var accepted = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + replacementPort + "/api/v1/deposit-conversions"))
                    .header("Authorization", "Bearer investor-b-token").header("Idempotency-Key", "new-deployment-mint")
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(accepted.statusCode()).isEqualTo(202);
            UUID fresh = UUID.fromString(new tools.jackson.databind.ObjectMapper().readTree(accepted.body()).get("operationId").asText());
            await(() -> ledger.getOperation(fresh).status() == OperationStatus.COMPLETED, 15);
            assertThat(jdbc.queryForObject("select amount from deployment_backing where deployment_id = ?", java.math.BigDecimal.class, replacement).toBigIntegerExact()).isEqualTo(BigInteger.valueOf(11));
            assertThat(chain.totalSupplyAt(env.get("TOKENRAIL_DEPOSIT_TOKEN_ADDRESS"), chain.headBlockNumber())).isEqualTo(BigInteger.valueOf(11));
            assertThat(ledger.backing()).isEqualTo(oldBacking);
            assertThat(chain.receipt(signed.transactionHash())).isEmpty();
            assertThat(web3j.ethGetTransactionByHash(signed.transactionHash()).send().getTransaction()).isEmpty();
            assertThat(ledger.getOperation(old).claimVersion()).isEqualTo(claimed.claimVersion());
        } finally { replacementWorker.destroyForcibly(); replacementWorker.waitFor(10, TimeUnit.SECONDS); }
        Files.writeString(run.resolve("reset.evidence"), "actual anvil_reset; same chainId=" + properties.chainId() + "; old=" + properties.deploymentId() + "; new=" + replacement + "; old signed transaction not broadcast; bank balances isolated\n");
    }

    private UUID convert(long amount) { return workflows.requestConversion("INVESTOR_B", id(), id(), BigInteger.valueOf(amount)).operationId(); }
    private void finish(UUID id, OperationStatus status) throws Exception {
        await(() -> { if (ledger.getOperation(id).status() == status) return true; workflows.processOne(); return false; }, 15);
    }
    private Process child(String stage, Path marker, Map<String, String> overrides) throws Exception {
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-Dcrash.stage=" + stage, "-Dcrash.marker=" + marker, "-cp", System.getProperty("java.class.path"),
                CrashWorkerMain.class.getName(), "--spring.main.web-application-type=none", "--spring.profiles.active=reliability-test",
                "--tokenrail.worker-enabled=true", "--tokenrail.indexer-enabled=false", "--tokenrail.worker-delay=100", "--tokenrail.lease-seconds=2", "--tokenrail.admin-lease-seconds=2");
        if (overrides.containsKey("M51_HTTP_PORT")) {
            builder.command().remove("--spring.main.web-application-type=none");
            builder.command().add("--spring.main.web-application-type=servlet");
            builder.command().add("--server.port=" + overrides.get("M51_HTTP_PORT"));
        }
        builder.environment().putAll(overrides);
        return builder.redirectErrorStream(true).redirectOutput(run.resolve("child-" + stage + "-" + UUID.randomUUID() + ".log").toFile()).start();
    }
    private HttpResponse<String> request(int port, String method, String path, String token, String key) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path)).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token).method(method, HttpRequest.BodyPublishers.noBody());
        if (key != null) builder.header("Idempotency-Key", key);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    private UUID jsonJobId(String body) throws Exception { return UUID.fromString(new tools.jackson.databind.ObjectMapper().readTree(body).get("jobId").asText()); }
    private void command(String... args) throws Exception {
        var process = new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(run.resolve("command-" + UUID.randomUUID() + ".log").toFile()).start();
        assertThat(process.waitFor(45, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isZero();
    }
    private static void await(Callable<Boolean> ready, int seconds) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < end) { if (ready.call()) return; Thread.sleep(100); }
        throw new AssertionError("Timed out waiting for durable state");
    }
    private String investor() { return System.getenv("TOKENRAIL_PARTICIPANT_INVESTOR_B_ADDRESS"); }
    private static String id() { return Hash.sha3String(UUID.randomUUID().toString()); }
}
