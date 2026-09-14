package io.tokenrail.ledger;

import io.tokenrail.api.ConflictException;
import io.tokenrail.api.NotFoundException;
import io.tokenrail.gateway.ChainTransaction;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class WorkflowRepository {
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.core.env.Environment environment;
    private void requireTestProfile() {
        if (!environment.matchesProfiles("reliability-test", "local-demo"))
            throw new IllegalStateException("Fault tools require an explicit local-demo or reliability-test profile");
    }

    public record RequestResult(UUID operationId, String resourceId, OperationStatus status) {
    }

    private record ExistingRequest(String requestHash, UUID operationId) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    @org.springframework.beans.factory.annotation.Value("${tokenrail.lease-seconds:30}")
    private int leaseSeconds;
    @org.springframework.beans.factory.annotation.Value("${tokenrail.deployment-id}")
    private String currentDeployment;

    public WorkflowRepository(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public void registerDeployment(
            String deploymentId, long chainId, String genesisHash, String depositTokenAddress) {
        jdbc.update("""
                insert into deployment(deployment_id, chain_id, genesis_hash, deposit_token_address)
                values (?, ?, ?, ?)
                on conflict (deployment_id) do nothing
                """, deploymentId, chainId, normalizeHash(genesisHash), normalizeAddress(depositTokenAddress));
        Integer matches = jdbc.queryForObject("""
                select count(*) from deployment where deployment_id = ? and chain_id = ?
                    and genesis_hash = ? and deposit_token_address = ? and active
                """, Integer.class, deploymentId, chainId, normalizeHash(genesisHash), normalizeAddress(depositTokenAddress));
        if (matches == null || matches != 1) throw new IllegalStateException("Deployment identity is immutable or suspended");
        registerContract(deploymentId, depositTokenAddress, "CASH");
        jdbc.update("insert into deployment_backing values (?, 0) on conflict do nothing", deploymentId);
    }

    public void registerContract(String deployment, String address, String kind) {
        if (address == null || address.isBlank()) return;
        jdbc.update("insert into deployment_contract values (?, ?, ?) on conflict do nothing", deployment, normalizeAddress(address), kind);
        Integer count = jdbc.queryForObject("select count(*) from deployment_contract where deployment_id = ? and address = ? and contract_kind = ?", Integer.class, deployment, normalizeAddress(address), kind);
        if (count != 1) throw new IllegalStateException("Deployment contract identity changed");
    }

    public RequestResult createBusiness(String actor, String route, String key, String requestHash,
            String action, String target, String calldata, String resourceId, String resourceKind, Runnable saveResource) {
        if (key == null || key.isBlank() || key.length() > 128) throw new IllegalArgumentException("Idempotency-Key is required (max 128)");
        var prior = findRequest(actor, route, key);
        if (prior.isPresent()) return resolveExisting(prior.get(), requestHash);
        try {
            return transactions.execute(ignored -> {
                saveResource.run();
                UUID id = UUID.randomUUID();
                insertOperation(id, currentDeployment, actor, org.web3j.crypto.Hash.sha3String(id.toString()),
                        OperationKind.BUSINESS, BigInteger.ONE, OperationStatus.QUEUED);
                jdbc.update("insert into business_command(operation_id, deployment_id, action, target_address, call_data, resource_id, resource_kind) values (?, ?, ?, ?, ?, ?, ?)",
                        id, currentDeployment, action, normalizeAddress(target), calldata, resourceId, resourceKind);
                insertRequest(actor, route, key, requestHash, id);
                insertOutbox(id);
                return new RequestResult(id, resourceId, OperationStatus.QUEUED);
            });
        } catch (DataIntegrityViolationException e) {
            return findRequest(actor, route, key).map(found -> resolveExisting(found, requestHash))
                    .orElseThrow(() -> new ConflictException("Resource or action already pending"));
        }
    }

    public void recordReceipt(DepositOperation operation, org.web3j.protocol.core.methods.response.TransactionReceipt receipt) {
        transactions.executeWithoutResult(ignored -> {
            requireClaim(operation);
            jdbc.update("update chain_transaction set mined_block = ?, mined_hash = ?, revert_data = ? where operation_id = ?",
                    receipt.getBlockNumber().longValueExact(), receipt.getBlockHash(), receipt.getRevertReason(), operation.operationId());
        });
    }

    public void completeBusiness(DepositOperation operation) {
        transactions.executeWithoutResult(ignored -> {
            requireClaim(operation);
            jdbc.update("update deposit_operation set status = 'SUCCEEDED', updated_at = now() where operation_id = ?", operation.operationId());
            jdbc.update("update business_command set pending = false where operation_id = ?", operation.operationId());
            jdbc.update("update outbox_task set status = 'DONE', updated_at = now() where operation_id = ?", operation.operationId());
        });
    }

    public String businessAction(UUID operationId) {
        return jdbc.queryForObject(
                "select action from business_command where operation_id = ?",
                String.class, operationId);
    }

    public String resourceId(DepositOperation operation) {
        if (operation.kind() != OperationKind.BUSINESS) return operation.businessId();
        return jdbc.queryForObject(
                "select resource_id from business_command where operation_id = ?",
                String.class, operation.operationId());
    }

    public void registerParticipant(
            String participantId,
            String walletAddress,
            String bearerToken,
            String signerKeyFile,
            BigInteger available) {
        transactions.executeWithoutResult(ignored -> {
            jdbc.update("""
                    insert into participant(participant_id, wallet_address, token_hash, signer_key_file)
                    values (?, ?, ?, ?)
                    on conflict (participant_id) do update set wallet_address = excluded.wallet_address,
                        token_hash = excluded.token_hash, signer_key_file = excluded.signer_key_file
                    """, participantId, normalizeAddress(walletAddress),
                    io.tokenrail.support.Hashes.sha256(bearerToken), signerKeyFile);
            jdbc.update("""
                    insert into deployment_participant(deployment_id, participant_id, wallet_address, token_hash, signer_key_file)
                    values (?, ?, ?, ?, ?) on conflict (deployment_id, participant_id) do nothing
                    """, currentDeployment, participantId, normalizeAddress(walletAddress),
                    io.tokenrail.support.Hashes.sha256(bearerToken), signerKeyFile);
            jdbc.update("""
                    insert into deployment_account(deployment_id, participant_id, available, reserved)
                    values (?, ?, ?, 0)
                    on conflict (deployment_id, participant_id) do nothing
                    """, currentDeployment, participantId, decimal(available));
        });
    }

    public Optional<String> findParticipantByTokenHash(String tokenHash) {
        return jdbc.query(
                        "select participant_id from deployment_participant where token_hash = ? and deployment_id = ?",
                        (result, row) -> result.getString(1), tokenHash, currentDeployment)
                .stream()
                .findFirst();
    }

    public RequestResult createConversion(
            String deploymentId,
            String actorId,
            String idempotencyKey,
            String requestHash,
            String businessId,
            BigInteger amount) {
        if (!currentDeployment.equals(deploymentId)) throw new ConflictException("Operation deployment mismatch");
        String route = "/api/v1/deposit-conversions";
        Optional<ExistingRequest> existing = findRequest(actorId, route, idempotencyKey);
        if (existing.isPresent()) {
            return resolveExisting(existing.get(), requestHash);
        }
        try {
            return transactions.execute(status -> {
                UUID operationId = UUID.randomUUID();
                List<BigDecimal> balances = jdbc.query(
                        "select available from deployment_account where participant_id = ? and deployment_id = ? for update",
                        (result, row) -> result.getBigDecimal(1), actorId, deploymentId);
                if (balances.isEmpty()) {
                    throw new NotFoundException("Deposit account not found");
                }
                if (balances.getFirst().toBigIntegerExact().compareTo(amount) < 0) {
                    throw new ConflictException("INSUFFICIENT_AVAILABLE_DEPOSIT");
                }
                jdbc.update("""
                        update deployment_account set available = available - ?, reserved = reserved + ?,
                            version = version + 1 where participant_id = ? and deployment_id = ?
                        """, decimal(amount), decimal(amount), actorId, deploymentId);
                insertOperation(operationId, deploymentId, actorId, businessId,
                        OperationKind.CONVERSION, amount, OperationStatus.RESERVED);
                insertRequest(actorId, route, idempotencyKey, requestHash, operationId);
                insertOutbox(operationId);
                return new RequestResult(operationId, businessId, OperationStatus.RESERVED);
            });
        } catch (DataIntegrityViolationException exception) {
            return findRequest(actorId, route, idempotencyKey)
                    .map(found -> resolveExisting(found, requestHash))
                    .orElseThrow(() -> new ConflictException("Business ID already exists"));
        }
    }

    public RequestResult createRedemption(
            String deploymentId,
            String actorId,
            String idempotencyKey,
            String requestHash,
            String businessId,
            BigInteger amount) {
        if (!currentDeployment.equals(deploymentId)) throw new ConflictException("Operation deployment mismatch");
        String route = "/api/v1/redemptions";
        Optional<ExistingRequest> existing = findRequest(actorId, route, idempotencyKey);
        if (existing.isPresent()) {
            return resolveExisting(existing.get(), requestHash);
        }
        try {
            return transactions.execute(status -> {
                UUID operationId = UUID.randomUUID();
                Integer participantCount = jdbc.queryForObject(
                        "select count(*) from deployment_participant where participant_id = ? and deployment_id = ?",
                        Integer.class, actorId, currentDeployment);
                if (participantCount == null || participantCount == 0) {
                    throw new NotFoundException("Participant not found");
                }
                insertOperation(operationId, deploymentId, actorId, businessId,
                        OperationKind.REDEMPTION, amount, OperationStatus.BURN_PENDING);
                insertRequest(actorId, route, idempotencyKey, requestHash, operationId);
                insertOutbox(operationId);
                return new RequestResult(operationId, businessId, OperationStatus.BURN_PENDING);
            });
        } catch (DataIntegrityViolationException exception) {
            return findRequest(actorId, route, idempotencyKey)
                    .map(found -> resolveExisting(found, requestHash))
                    .orElseThrow(() -> new ConflictException("Business ID already exists"));
        }
    }

    public Optional<DepositOperation> claimNext() {
        return transactions.execute(status -> {
            List<DepositOperation> operations = jdbc.query("""
                    select o.operation_id, o.deployment_id, o.participant_id, p.wallet_address,
                        p.signer_key_file, o.business_id, o.kind, o.amount, o.status, o.tx_hash, t.claim_version
                    from outbox_task t
                    join deposit_operation o on o.operation_id = t.operation_id
                    join deployment_participant p on p.participant_id = o.participant_id and p.deployment_id = o.deployment_id
                    where o.deployment_id = ? and (
                        (t.status in ('PENDING', 'WAITING') and t.available_at <= now())
                        or (t.status = 'PROCESSING' and t.lease_until <= clock_timestamp()))
                    order by t.created_at
                    for update of t skip locked limit 1
                    """, this::mapOperation, currentDeployment);
            if (operations.isEmpty()) {
                return Optional.empty();
            }
            DepositOperation operation = operations.getFirst();
            jdbc.update("""
                    update outbox_task set status = 'PROCESSING', attempts = attempts + 1,
                        claim_version = claim_version + 1,
                        lease_until = clock_timestamp() + (? * interval '1 second'),
                        updated_at = now() where operation_id = ?
                    """, Math.max(1, leaseSeconds), operation.operationId());
            return Optional.of(getOperation(operation.operationId()));
        });
    }

    public ChainTransaction allocateNonce(
            DepositOperation operation,
            String method,
            String signerAddress,
            BigInteger pendingNonce) {
        return transactions.execute(status -> {
            requireClaim(operation);
            Optional<ChainTransaction> existing = findTransaction(operation.operationId());
            if (existing.isPresent()) {
                return existing.get();
            }
            String normalizedSigner = normalizeAddress(signerAddress);
            jdbc.update("""
                    insert into signer_nonce(deployment_id, signer_address, next_nonce)
                    values (?, ?, ?)
                    on conflict (deployment_id, signer_address) do nothing
                    """, operation.deploymentId(), normalizedSigner, decimal(pendingNonce));
            BigInteger databaseNonce = jdbc.queryForObject("""
                    select next_nonce from signer_nonce
                    where deployment_id = ? and signer_address = ? for update
                    """, (result, row) -> result.getBigDecimal(1).toBigIntegerExact(),
                    operation.deploymentId(), normalizedSigner);
            BigInteger allocated = databaseNonce.max(pendingNonce);
            jdbc.update("""
                    update signer_nonce set next_nonce = ?
                    where deployment_id = ? and signer_address = ?
                    """, decimal(allocated.add(BigInteger.ONE)), operation.deploymentId(), normalizedSigner);
            ChainTransaction created = new ChainTransaction(
                    UUID.randomUUID(), operation.operationId(), normalizedSigner, allocated,
                    null, null, "PREPARED");
            jdbc.update("""
                    insert into chain_transaction(transaction_id, operation_id, deployment_id,
                        method, signer_address, nonce, status, target_address)
                    values (?, ?, ?, ?, ?, ?, 'PREPARED', coalesce((select target_address from business_command where operation_id = ?), (select deposit_token_address from deployment where deployment_id = ?)))
                    """, created.transactionId(), created.operationId(), operation.deploymentId(), method,
                    normalizedSigner, decimal(allocated), operation.operationId(), operation.deploymentId());
            return created;
        });
    }

    public void storeSigned(DepositOperation claim, String rawTransaction, String transactionHash) {
        UUID operationId = claim.operationId();
        transactions.executeWithoutResult(ignored -> {
            requireClaim(claim);
            jdbc.update("""
                update chain_transaction set raw_transaction = ?, tx_hash = ?, updated_at = now()
                where operation_id = ? and raw_transaction is null
                """, rawTransaction, normalizeHash(transactionHash), operationId);
        });
    }

    public Optional<ChainTransaction> findTransaction(UUID operationId) {
        return jdbc.query("""
                        select transaction_id, operation_id, signer_address, nonce,
                            raw_transaction, tx_hash, status
                        from chain_transaction where operation_id = ?
                        """, (result, row) -> new ChainTransaction(
                                result.getObject(1, UUID.class), result.getObject(2, UUID.class),
                                result.getString(3), result.getBigDecimal(4).toBigIntegerExact(),
                                result.getString(5), result.getString(6), result.getString(7)), operationId)
                .stream().findFirst();
    }

    public void markSubmitted(DepositOperation claim, String transactionHash, boolean uncertain) {
        UUID operationId = claim.operationId();
        transactions.executeWithoutResult(ignored -> {
            requireClaim(claim);
            String transactionStatus = uncertain ? "UNKNOWN" : "BROADCAST";
            jdbc.update("""
                    update chain_transaction set status = ?, tx_hash = ?, updated_at = now()
                    where operation_id = ?
                    """, transactionStatus, normalizeHash(transactionHash), operationId);
            jdbc.update("""
                    update deposit_operation set status = case kind
                        when 'CONVERSION' then 'MINT_PENDING' when 'BUSINESS' then 'SUBMITTED' else 'BURN_PENDING' end,
                        tx_hash = ?, updated_at = now() where operation_id = ?
                    """, normalizeHash(transactionHash), operationId);
        });
    }

    public void markWaiting(DepositOperation claim) {
        UUID operationId = claim.operationId();
        transactions.executeWithoutResult(ignored -> {
            requireClaim(claim);
            jdbc.update("""
                update outbox_task set status = 'WAITING', available_at = now() + interval '1 second',
                    updated_at = now() where operation_id = ?
                """, operationId);
        });
    }

    public void markMined(DepositOperation claim, boolean success, String errorCode) {
        UUID operationId = claim.operationId();
        transactions.executeWithoutResult(ignored -> {
            requireClaim(claim);
            jdbc.update("""
                    update chain_transaction set status = ?, receipt_status = ?, error_code = ?,
                        updated_at = now() where operation_id = ?
                    """, success ? "MINED_SUCCESS" : "MINED_REVERT", success ? 1 : 0,
                    errorCode, operationId);
            if (!success) {
                jdbc.update("update business_command set pending = false where operation_id = ?", operationId);
                DepositOperation operation = getOperation(operationId);
                int changed = jdbc.update("""
                        update deposit_operation set status = 'REVERTED', error_code = ?, updated_at = now()
                        where operation_id = ? and status not in ('REVERTED', 'COMPLETED', 'CREDITED')
                        """, errorCode, operationId);
                if (changed > 0 && operation.kind() == OperationKind.CONVERSION) {
                    jdbc.update("""
                            update deployment_account set available = available + ?, reserved = reserved - ?,
                                version = version + 1 where participant_id = ? and deployment_id = ?
                            """, decimal(operation.amount()), decimal(operation.amount()),
                            operation.participantId(), operation.deploymentId());
                }
                jdbc.update("update outbox_task set status = 'DONE', updated_at = now() where operation_id = ?",
                        operationId);
            }
        });
    }

    public void completeConversion(DepositOperation claim, Runnable beforeCommit) {
        UUID operationId = claim.operationId();
        transactions.executeWithoutResult(ignored -> {
            requireClaim(claim);
            DepositOperation operation = getOperation(operationId);
            int changed = jdbc.update("""
                    update deposit_operation set status = 'COMPLETED', updated_at = now()
                    where operation_id = ? and status = 'MINT_PENDING'
                    """, operationId);
            if (changed == 0) {
                return;
            }
            jdbc.update("""
                    update deployment_account set reserved = reserved - ?, version = version + 1
                    where participant_id = ? and deployment_id = ?
                    """, decimal(operation.amount()), operation.participantId(), operation.deploymentId());
            jdbc.update("update deployment_backing set amount = amount + ? where deployment_id = ?",
                    decimal(operation.amount()), operation.deploymentId());
            jdbc.update("""
                    insert into deposit_journal(journal_id, operation_id, posting_kind, amount)
                    values (?, ?, 'CONVERSION_BACKING', ?)
                    """, UUID.randomUUID(), operationId, decimal(operation.amount()));
            jdbc.update("update outbox_task set status = 'DONE', updated_at = now() where operation_id = ?",
                    operationId);
            beforeCommit.run();
        });
    }

    public void markBurnConfirmed(DepositOperation claim) {
        UUID operationId = claim.operationId();
        transactions.executeWithoutResult(ignored -> {
            requireClaim(claim);
            jdbc.update("""
                    update deposit_operation set status = 'BURN_CONFIRMED', updated_at = now()
                    where operation_id = ? and status = 'BURN_PENDING'
                    """, operationId);
        });
    }

    public void creditRedemption(DepositOperation claim, Runnable beforeCommit) {
        UUID operationId = claim.operationId();
        transactions.executeWithoutResult(ignored -> {
            requireClaim(claim);
            DepositOperation operation = getOperation(operationId);
            int changed = jdbc.update("""
                    update deposit_operation set status = 'CREDITED', updated_at = now()
                    where operation_id = ? and status = 'BURN_CONFIRMED'
                    """, operationId);
            if (changed == 0) {
                return;
            }
            jdbc.update("""
                    update deployment_account set available = available + ?, version = version + 1
                    where participant_id = ? and deployment_id = ?
                    """, decimal(operation.amount()), operation.participantId(), operation.deploymentId());
            jdbc.update("update deployment_backing set amount = amount - ? where deployment_id = ?",
                    decimal(operation.amount()), operation.deploymentId());
            jdbc.update("""
                    insert into deposit_journal(journal_id, operation_id, posting_kind, amount)
                    values (?, ?, 'REDEMPTION_CREDIT', ?)
                    """, UUID.randomUUID(), operationId, decimal(operation.amount()));
            jdbc.update("update outbox_task set status = 'DONE', updated_at = now() where operation_id = ?",
                    operationId);
            beforeCommit.run();
        });
    }

    public DepositOperation getOperation(UUID operationId) {
        return jdbc.query("""
                        select o.operation_id, o.deployment_id, o.participant_id, p.wallet_address,
                            p.signer_key_file, o.business_id, o.kind, o.amount, o.status, o.tx_hash, t.claim_version
                        from deposit_operation o join deployment_participant p on p.participant_id = o.participant_id and p.deployment_id = o.deployment_id
                        join outbox_task t on t.operation_id = o.operation_id
                        where o.operation_id = ?
                        """, this::mapOperation, operationId).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Operation not found"));
    }

    public BigInteger available(String participantId) {
        return jdbc.queryForObject("select available from deployment_account where participant_id = ? and deployment_id = ?",
                (result, row) -> result.getBigDecimal(1).toBigIntegerExact(), participantId, currentDeployment);
    }

    public BigInteger reserved(String participantId) {
        return jdbc.queryForObject("select reserved from deployment_account where participant_id = ? and deployment_id = ?",
                (result, row) -> result.getBigDecimal(1).toBigIntegerExact(), participantId, currentDeployment);
    }

    public BigInteger backing() {
        return jdbc.queryForObject("select amount from deployment_backing where deployment_id = ?",
                (result, row) -> result.getBigDecimal(1).toBigIntegerExact(), currentDeployment);
    }

    public io.tokenrail.reconciliation.BankSnapshot bankSnapshot() {
        return new io.tokenrail.reconciliation.BankSnapshot(backing(), jdbc.query("""
                select t.tx_hash, o.kind, o.amount, j.journal_id is not null
                from deposit_operation o left join chain_transaction t on t.operation_id = o.operation_id
                left join deposit_journal j on j.operation_id = o.operation_id
                where o.deployment_id = ? and o.kind in ('CONVERSION','REDEMPTION')
                """, (r, n) -> new io.tokenrail.reconciliation.BankSnapshot.Posting(
                r.getString(1), r.getString(2), r.getBigDecimal(3).toBigIntegerExact(), r.getBoolean(4)), currentDeployment));
    }

    public int journalCount(UUID operationId, String postingKind) {
        Integer count = jdbc.queryForObject("""
                select count(*) from deposit_journal where operation_id = ? and posting_kind = ?
                """, Integer.class, operationId, postingKind);
        return count == null ? 0 : count;
    }

    public void truncateForTest() {
        requireTestProfile();
        jdbc.execute("""
                truncate table deposit_journal, chain_transaction, signer_nonce, outbox_task,
                    api_request, deposit_operation, deployment_account, participant, deployment restart identity cascade
                """);
        jdbc.update("""
                insert into deposit_backing(singleton, amount) values (true, 0)
                on conflict (singleton) do update set amount = 0
                """);
    }

    public void makeReadyForTest(UUID operationId) {
        requireTestProfile();
        jdbc.update("update outbox_task set status = 'WAITING', available_at = now() where operation_id = ?",
                operationId);
    }

    public void setBackingForTest(BigInteger amount) {
        requireTestProfile();
        jdbc.update("update deployment_backing set amount = ? where deployment_id = ?", decimal(amount), currentDeployment);
    }

    /** Lock and fence inside the same short transaction as every durable write. */
    private void requireClaim(DepositOperation operation) {
        List<Long> claims = jdbc.query("""
                select claim_version from outbox_task where operation_id = ?
                    and exists (select 1 from deposit_operation o join deployment d on d.deployment_id = o.deployment_id where o.operation_id = outbox_task.operation_id and d.active and d.deployment_id = ?)
                    and status = 'PROCESSING' and claim_version = ?
                    and lease_until > clock_timestamp() for update
                """, (r, n) -> r.getLong(1), operation.operationId(), currentDeployment, operation.claimVersion());
        if (claims.isEmpty()) throw new StaleClaimException();
    }

    public void assertClaim(DepositOperation operation) {
        transactions.executeWithoutResult(ignored -> requireClaim(operation));
    }

    private Optional<ExistingRequest> findRequest(String actorId, String route, String key) {
        return jdbc.query("""
                        select request_hash, operation_id from api_request
                        where actor_id = ? and route = ? and idempotency_key = ? and deployment_id = ?
                        """, (result, row) -> new ExistingRequest(
                                result.getString(1), result.getObject(2, UUID.class)), actorId, route, key, currentDeployment)
                .stream().findFirst();
    }

    private RequestResult resolveExisting(ExistingRequest existing, String requestHash) {
        if (!existing.requestHash().equals(requestHash)) {
            throw new ConflictException("Idempotency key was already used with a different request");
        }
        DepositOperation operation = getOperation(existing.operationId());
        String resource = resourceId(operation);
        return new RequestResult(operation.operationId(), resource, operation.status());
    }

    private void insertOperation(
            UUID id,
            String deploymentId,
            String actorId,
            String businessId,
            OperationKind kind,
            BigInteger amount,
            OperationStatus status) {
        jdbc.update("""
                insert into deposit_operation(operation_id, deployment_id, participant_id,
                    business_id, kind, amount, status)
                values (?, ?, ?, ?, ?, ?, ?)
                """, id, deploymentId, actorId, normalizeHash(businessId), kind.name(),
                decimal(amount), status.name());
    }

    private void insertRequest(
            String actorId, String route, String key, String requestHash, UUID operationId) {
        jdbc.update("""
                insert into api_request(actor_id, route, idempotency_key, request_hash, operation_id, deployment_id)
                values (?, ?, ?, ?, ?, ?)
                """, actorId, route, key, requestHash, operationId, currentDeployment);
    }

    private void insertOutbox(UUID operationId) {
        jdbc.update("insert into outbox_task(operation_id, status) values (?, 'PENDING')", operationId);
    }

    private DepositOperation mapOperation(ResultSet result, int row) throws SQLException {
        return new DepositOperation(
                result.getObject(1, UUID.class), result.getString(2), result.getString(3),
                result.getString(4), result.getString(5), result.getString(6),
                OperationKind.valueOf(result.getString(7)),
                result.getBigDecimal(8).toBigIntegerExact(),
                OperationStatus.valueOf(result.getString(9)), result.getString(10), result.getLong(11));
    }

    private static BigDecimal decimal(BigInteger value) {
        return new BigDecimal(value);
    }

    private static String normalizeAddress(String value) {
        return value.toLowerCase(java.util.Locale.ROOT);
    }

    private static String normalizeHash(String value) {
        return value.toLowerCase(java.util.Locale.ROOT);
    }
}
