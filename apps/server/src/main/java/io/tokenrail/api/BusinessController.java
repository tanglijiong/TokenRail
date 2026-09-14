package io.tokenrail.api;

import io.tokenrail.business.BusinessGateway;
import io.tokenrail.business.BusinessRepository;
import io.tokenrail.business.BusinessWorkflowService;
import io.tokenrail.config.TokenRailProperties;
import io.tokenrail.gateway.BlockchainGateway;
import io.tokenrail.ledger.WorkflowRepository;
import io.tokenrail.projection.ProjectionRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.math.BigInteger;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class BusinessController {
    private static final String ADDRESS = "0x[0-9a-fA-F]{40}";
    private static final String ID = "0x[0-9a-fA-F]{64}";

    public record Submission(UUID operationId, String resourceId, String submissionStatus) { }
    public record AllowanceRequest(@NotBlank @Pattern(regexp = ADDRESS) String assetAddress,
                                   @NotBlank @Pattern(regexp = ADDRESS) String spender,
                                   @NotNull @Positive BigInteger amount) { }
    public record BondIssueRequest(@NotBlank @Pattern(regexp = ID) String issueId,
                                   @NotBlank @Pattern(regexp = ADDRESS) String recipient,
                                   @NotNull @Positive BigInteger quantity) { }
    public record SettlementRequest(@NotBlank @Pattern(regexp = ID) String instructionId,
                                    @NotBlank @Pattern(regexp = ADDRESS) String buyer,
                                    @NotNull @Positive BigInteger bondQuantity,
                                    @NotNull @Positive BigInteger cashAmount,
                                    @NotNull Instant expiry, String externalTradeRef) { }
    public record PaymentRequest(@NotBlank @Pattern(regexp = ID) String paymentId,
                                 @NotBlank @Pattern(regexp = ADDRESS) String payee,
                                 @NotBlank @Pattern(regexp = ADDRESS) String attestor,
                                 @NotNull @Positive BigInteger amount,
                                 @NotNull Instant deadline) { }
    public record IdentityRequest(@NotBlank @Pattern(regexp = ID) String participantRef,
                                  @NotNull Instant validUntil, boolean active, boolean frozen,
                                  boolean canHoldCash, boolean canHoldBond) { }
    public record ResourceView(String deploymentId, String resourceId, String resourceKind,
                               Object terms, Object chain, UUID latestOperationId,
                               String operationStatus, String transactionHash,
                               Long minedBlock, String minedHash, String syncStatus,
                               Long indexedBlock, String indexedHash) { }

    private final AuthenticationService auth;
    private final BusinessWorkflowService business;
    private final BusinessRepository resources;
    private final BusinessGateway chainState;
    private final WorkflowRepository workflows;
    private final ProjectionRepository projections;
    private final BlockchainGateway chain;
    private final TokenRailProperties properties;

    public BusinessController(AuthenticationService auth, BusinessWorkflowService business,
            BusinessRepository resources, BusinessGateway chainState, WorkflowRepository workflows,
            ProjectionRepository projections, BlockchainGateway chain, TokenRailProperties properties) {
        this.auth = auth; this.business = business; this.resources = resources; this.chainState = chainState;
        this.workflows = workflows; this.projections = projections; this.chain = chain; this.properties = properties;
    }

    @GetMapping("/session")
    public Map<String, Object> session(@RequestHeader("Authorization") String authorization) {
        String actor = auth.authenticate(authorization);
        var checkpoint = projections.activeCheckpoint(properties.deploymentId());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("deploymentId", properties.deploymentId());
        response.put("actorId", actor);
        response.put("walletAddress", auth.wallet(actor));
        response.put("roles", List.of("ISSUER", "OPERATOR", "COMPLIANCE", "ATTESTOR", "RECONCILER")
                .stream().filter(role -> auth.hasRole(actor, role)).toList());
        response.put("chainId", Long.toString(properties.chainId()));
        response.put("headBlock", chain.headBlockNumber());
        response.put("indexedBlock", checkpoint.map(item -> item.blockNumber()).orElse(null));
        response.put("indexedHash", checkpoint.map(item -> item.blockHash()).orElse(null));
        response.put("contracts", Map.of("cash", properties.depositTokenAddress(), "bond",
                properties.bondTokenAddress(), "dvp", properties.dvpAddress(), "escrow",
                properties.escrowAddress(), "registry", properties.identityRegistryAddress()));
        return response;
    }

    @PostMapping("/allowances")
    public ResponseEntity<Submission> approve(@RequestHeader("Authorization") String authorization,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody AllowanceRequest request) {
        return accepted(business.approve(auth.authenticate(authorization), key,
                request.assetAddress(), request.spender(), request.amount()));
    }

    @GetMapping("/allowances")
    public Map<String, Object> allowance(@RequestHeader("Authorization") String authorization,
            @RequestParam @Pattern(regexp = ADDRESS) String asset,
            @RequestParam @Pattern(regexp = ADDRESS) String spender) {
        String actor = auth.authenticate(authorization);
        return Map.of("owner", auth.wallet(actor), "assetAddress", asset, "spender", spender,
                "amount", chainState.allowance(asset, auth.wallet(actor), spender).toString());
    }

    @PostMapping("/bond-issues")
    public ResponseEntity<Submission> issue(@RequestHeader("Authorization") String authorization,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody BondIssueRequest request) {
        return accepted(business.issueBond(auth.authenticate(authorization), key,
                request.issueId(), request.recipient(), request.quantity()));
    }

    @PostMapping("/settlements")
    public ResponseEntity<Submission> settlement(@RequestHeader("Authorization") String authorization,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody SettlementRequest request) {
        return accepted(business.createSettlement(auth.authenticate(authorization), key,
                request.instructionId(), request.buyer(), request.bondQuantity(), request.cashAmount(),
                request.expiry(), request.externalTradeRef()));
    }

    @PostMapping("/settlements/{id}/confirm")
    public ResponseEntity<Submission> confirm(@RequestHeader("Authorization") String authorization,
            @RequestHeader("Idempotency-Key") String key, @PathVariable @Pattern(regexp = ID) String id) {
        return settlementAction(authorization, key, id, "CONFIRM_SETTLEMENT");
    }

    @PostMapping("/settlements/{id}/execute")
    public ResponseEntity<Submission> execute(@RequestHeader("Authorization") String authorization,
            @RequestHeader("Idempotency-Key") String key, @PathVariable @Pattern(regexp = ID) String id) {
        return settlementAction(authorization, key, id, "EXECUTE_SETTLEMENT");
    }

    @PostMapping("/settlements/{id}/cancel")
    public ResponseEntity<Submission> cancel(@RequestHeader("Authorization") String authorization,
            @RequestHeader("Idempotency-Key") String key, @PathVariable @Pattern(regexp = ID) String id) {
        return settlementAction(authorization, key, id, "CANCEL_SETTLEMENT");
    }

    @GetMapping("/settlements/{id}")
    public ResourceView settlement(@RequestHeader("Authorization") String authorization,
            @PathVariable @Pattern(regexp = ID) String id) {
        String actor = auth.authenticate(authorization);
        if (!business.mayRead(actor, "SETTLEMENT", id)) throw new ForbiddenException("Settlement is private to its parties and operator");
        var resource = resources.get(properties.deploymentId(), "SETTLEMENT", id);
        return view(resource, chainState.settlement(properties.dvpAddress(), bytes(id)));
    }

    @PostMapping("/payments")
    public ResponseEntity<Submission> payment(@RequestHeader("Authorization") String authorization,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody PaymentRequest request) {
        return accepted(business.fundPayment(auth.authenticate(authorization), key, request.paymentId(),
                request.payee(), request.attestor(), request.amount(), request.deadline()));
    }

    @PostMapping("/payments/{id}/release")
    public ResponseEntity<Submission> release(@RequestHeader("Authorization") String authorization,
            @RequestHeader("Idempotency-Key") String key, @PathVariable @Pattern(regexp = ID) String id) {
        return paymentAction(authorization, key, id, "RELEASE_PAYMENT");
    }

    @PostMapping("/payments/{id}/refund")
    public ResponseEntity<Submission> refund(@RequestHeader("Authorization") String authorization,
            @RequestHeader("Idempotency-Key") String key, @PathVariable @Pattern(regexp = ID) String id) {
        return paymentAction(authorization, key, id, "REFUND_PAYMENT");
    }

    @GetMapping("/payments/{id}")
    public ResourceView payment(@RequestHeader("Authorization") String authorization,
            @PathVariable @Pattern(regexp = ID) String id) {
        String actor = auth.authenticate(authorization);
        if (!business.mayRead(actor, "PAYMENT", id)) throw new ForbiddenException("Payment is private to its parties and attestor");
        var resource = resources.get(properties.deploymentId(), "PAYMENT", id);
        return view(resource, chainState.payment(properties.escrowAddress(), bytes(id)));
    }

    @PostMapping("/identities/{wallet}/status")
    public ResponseEntity<Submission> identity(@RequestHeader("Authorization") String authorization,
            @RequestHeader("Idempotency-Key") String key, @PathVariable @Pattern(regexp = ADDRESS) String wallet,
            @Valid @RequestBody IdentityRequest request) {
        return accepted(business.updateIdentity(auth.authenticate(authorization), key, wallet,
                request.participantRef(), request.validUntil(), request.active(), request.frozen(),
                request.canHoldCash(), request.canHoldBond()));
    }

    @GetMapping("/identities/{wallet}")
    public BusinessGateway.Identity identity(@RequestHeader("Authorization") String authorization,
            @PathVariable @Pattern(regexp = ADDRESS) String wallet) {
        auth.authenticate(authorization);
        return chainState.identity(properties.identityRegistryAddress(), wallet);
    }

    @GetMapping("/operations")
    public List<Map<String, Object>> operations(@RequestHeader("Authorization") String authorization,
            @RequestParam(defaultValue = "30") int limit) {
        String actor = auth.authenticate(authorization);
        return resources.recentOperations(properties.deploymentId(), actor, limit);
    }

    private ResponseEntity<Submission> settlementAction(String authorization, String key, String id, String action) {
        return accepted(business.settlementAction(auth.authenticate(authorization), key, id, action));
    }
    private ResponseEntity<Submission> paymentAction(String authorization, String key, String id, String action) {
        return accepted(business.paymentAction(auth.authenticate(authorization), key, id, action));
    }
    private ResponseEntity<Submission> accepted(WorkflowRepository.RequestResult result) {
        return ResponseEntity.accepted().body(new Submission(result.operationId(), result.resourceId(), result.status().name()));
    }
    private ResourceView view(BusinessRepository.Resource resource, Object state) {
        var checkpoint = projections.activeCheckpoint(properties.deploymentId());
        Long indexed = checkpoint.map(item -> item.blockNumber()).orElse(null);
        String sync = resource.operationStatus() == null ? "NOT_SUBMITTED"
                : !"SUCCEEDED".equals(resource.operationStatus()) ? "OPERATION_" + resource.operationStatus()
                : resource.minedBlock() != null && indexed != null && indexed >= resource.minedBlock()
                ? "PROJECTED" : "CHAIN_CONFIRMED";
        return new ResourceView(resource.deploymentId(), resource.id(), resource.kind(),
                resources.terms(resource.deploymentId(), resource.kind(), resource.id()), state,
                resource.latestOperationId(), resource.operationStatus(), resource.transactionHash(),
                resource.minedBlock(), resource.minedHash(), sync, indexed,
                checkpoint.map(item -> item.blockHash()).orElse(null));
    }
    private static byte[] bytes(String id) { return org.web3j.utils.Numeric.hexStringToByteArray(id); }
}
