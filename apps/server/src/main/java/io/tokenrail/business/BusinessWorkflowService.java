package io.tokenrail.business;

import io.tokenrail.api.AuthenticationService;
import io.tokenrail.api.ConflictException;
import io.tokenrail.api.ForbiddenException;
import io.tokenrail.config.DeploymentGuard;
import io.tokenrail.config.TokenRailProperties;
import io.tokenrail.ledger.WorkflowRepository;
import io.tokenrail.support.Hashes;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint64;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.utils.Numeric;

@Service
public class BusinessWorkflowService {
    public record SettlementTerms(String sellerId, String seller, String buyer,
                                  BigInteger bondQuantity, BigInteger cashAmount,
                                  long expiry, String externalTradeRef) { }
    public record PaymentTerms(String payerId, String payer, String payee, String attestor,
                               BigInteger amount, long deadline) { }

    private final WorkflowRepository workflows;
    private final BusinessRepository resources;
    private final AuthenticationService authentication;
    private final TokenRailProperties properties;
    private final DeploymentGuard guard;
    private final tools.jackson.databind.ObjectMapper json = new tools.jackson.databind.ObjectMapper();

    public BusinessWorkflowService(WorkflowRepository workflows, BusinessRepository resources,
            AuthenticationService authentication, TokenRailProperties properties, DeploymentGuard guard) {
        this.workflows = workflows; this.resources = resources; this.authentication = authentication;
        this.properties = properties; this.guard = guard;
    }

    public WorkflowRepository.RequestResult approve(String actor, String key, String asset,
            String spender, BigInteger amount) {
        requireAmount(amount); requireAddress(asset); requireAddress(spender);
        if (!oneOf(asset, properties.depositTokenAddress(), properties.bondTokenAddress())
                || !oneOf(spender, properties.dvpAddress(), properties.escrowAddress())) {
            throw new ForbiddenException("Asset and spender must belong to the active deployment");
        }
        String resource = org.web3j.crypto.Hash.sha3String(actor + asset + spender);
        return queue(actor, "/api/v1/allowances", key, "APPROVE", asset,
                new Function("approve", List.of(new Address(spender), new Uint256(amount)), List.of()),
                resource, null, () -> { });
    }

    public WorkflowRepository.RequestResult issueBond(String actor, String key, String issueId,
            String recipient, BigInteger quantity) {
        if (!authentication.hasRole(actor, "ISSUER")) throw new ForbiddenException("ISSUER role is required");
        requireId(issueId); requireAddress(recipient); requireAmount(quantity);
        Function call = new Function("issue", List.of(new Bytes32(bytes(issueId)),
                new Address(recipient), new Uint256(quantity)), List.of());
        return queue(actor, "/api/v1/bond-issues", key, "ISSUE_BOND", properties.bondTokenAddress(),
                call, issueId, null, () -> { });
    }

    public WorkflowRepository.RequestResult createSettlement(String actor, String key, String id,
            String buyer, BigInteger bond, BigInteger cash, Instant expiry, String reference) {
        requireId(id); requireAddress(buyer); requireAmount(bond); requireAmount(cash);
        String seller = authentication.wallet(actor);
        if (seller.equalsIgnoreCase(buyer) || expiry == null || !expiry.isAfter(Instant.now())) {
            throw new IllegalArgumentException("Settlement parties and expiry are invalid");
        }
        long epoch = expiry.getEpochSecond();
        SettlementTerms terms = new SettlementTerms(actor, seller, normalize(buyer), bond, cash, epoch,
                reference == null ? "" : reference);
        Function call = new Function("createInstruction", List.of(new Bytes32(bytes(id)), new Address(buyer),
                new Uint256(bond), new Uint256(cash), new Uint64(epoch)), List.of());
        return queue(actor, "/api/v1/settlements", key, "CREATE_SETTLEMENT", properties.dvpAddress(), call,
                id, "SETTLEMENT", () -> resources.insertResource(properties.deploymentId(), id,
                        "SETTLEMENT", actor, write(terms)));
    }

    public WorkflowRepository.RequestResult settlementAction(String actor, String key, String id, String action) {
        requireId(id);
        Map<String, Object> terms = resources.terms(properties.deploymentId(), "SETTLEMENT", id);
        String wallet = authentication.wallet(actor);
        String function;
        if ("CONFIRM_SETTLEMENT".equals(action)) {
            if (!wallet.equalsIgnoreCase(text(terms, "buyer"))) throw new ForbiddenException("Only the buyer may confirm");
            function = "confirmInstruction";
        } else if ("CANCEL_SETTLEMENT".equals(action)) {
            if (!wallet.equalsIgnoreCase(text(terms, "buyer")) && !wallet.equalsIgnoreCase(text(terms, "seller")))
                throw new ForbiddenException("Only a settlement party may cancel");
            function = "cancelInstruction";
        } else if ("EXECUTE_SETTLEMENT".equals(action)) {
            if (!authentication.hasRole(actor, "OPERATOR")) throw new ForbiddenException("OPERATOR role is required");
            function = "execute";
        } else throw new IllegalArgumentException("Unknown settlement action");
        resources.assertNoPending(properties.deploymentId(), id, action);
        return queue(actor, "/api/v1/settlements/" + id + "/" + action.toLowerCase(Locale.ROOT), key,
                action, properties.dvpAddress(), new Function(function, List.of(new Bytes32(bytes(id))), List.of()),
                id, "SETTLEMENT", () -> { });
    }

    public WorkflowRepository.RequestResult fundPayment(String actor, String key, String id,
            String payee, String attestor, BigInteger amount, Instant deadline) {
        requireId(id); requireAddress(payee); requireAddress(attestor); requireAmount(amount);
        String payer = authentication.wallet(actor);
        if (payer.equalsIgnoreCase(payee) || deadline == null || !deadline.isAfter(Instant.now()))
            throw new IllegalArgumentException("Payment parties and deadline are invalid");
        long epoch = deadline.getEpochSecond();
        PaymentTerms terms = new PaymentTerms(actor, payer, normalize(payee), normalize(attestor), amount, epoch);
        Function call = new Function("fund", List.of(new Bytes32(bytes(id)), new Address(payee),
                new Address(attestor), new Uint256(amount), new Uint64(epoch)), List.of());
        return queue(actor, "/api/v1/payments", key, "FUND_PAYMENT", properties.escrowAddress(), call,
                id, "PAYMENT", () -> resources.insertResource(properties.deploymentId(), id,
                        "PAYMENT", actor, write(terms)));
    }

    public WorkflowRepository.RequestResult paymentAction(String actor, String key, String id, String action) {
        requireId(id);
        Map<String, Object> terms = resources.terms(properties.deploymentId(), "PAYMENT", id);
        String wallet = authentication.wallet(actor);
        String function;
        if ("RELEASE_PAYMENT".equals(action)) {
            if (!authentication.hasRole(actor, "ATTESTOR") || !wallet.equalsIgnoreCase(text(terms, "attestor")))
                throw new ForbiddenException("Only the named attestor may release");
            function = "release";
        } else if ("REFUND_PAYMENT".equals(action)) {
            if (!wallet.equalsIgnoreCase(text(terms, "payer"))) throw new ForbiddenException("Only the payer may refund");
            function = "refund";
        } else throw new IllegalArgumentException("Unknown payment action");
        resources.assertNoPending(properties.deploymentId(), id, action);
        return queue(actor, "/api/v1/payments/" + id + "/" + action.toLowerCase(Locale.ROOT), key,
                action, properties.escrowAddress(), new Function(function, List.of(new Bytes32(bytes(id))), List.of()),
                id, "PAYMENT", () -> { });
    }

    public WorkflowRepository.RequestResult updateIdentity(String actor, String key, String wallet,
            String participantRef, Instant validUntil, boolean active, boolean frozen,
            boolean cash, boolean bond) {
        if (!authentication.hasRole(actor, "COMPLIANCE")) throw new ForbiddenException("COMPLIANCE role is required");
        requireAddress(wallet); requireId(participantRef);
        if (validUntil == null) throw new IllegalArgumentException("validUntil is required");
        Function call = new Function("setIdentity", List.of(new Address(wallet), new Bytes32(bytes(participantRef)),
                new Uint64(validUntil.getEpochSecond()), new Bool(active), new Bool(frozen),
                new Bool(cash), new Bool(bond)), List.of());
        return queue(actor, "/api/v1/identities/" + normalize(wallet) + "/status", key,
                "SET_IDENTITY", properties.identityRegistryAddress(), call,
                org.web3j.crypto.Hash.sha3String(wallet), null, () -> { });
    }

    public boolean mayRead(String actor, String kind, String id) {
        Map<String, Object> terms = resources.terms(properties.deploymentId(), kind, id);
        String wallet = authentication.wallet(actor);
        if ("SETTLEMENT".equals(kind)) return authentication.hasRole(actor, "OPERATOR")
                || wallet.equalsIgnoreCase(text(terms, "seller")) || wallet.equalsIgnoreCase(text(terms, "buyer"));
        return (authentication.hasRole(actor, "ATTESTOR")
                && wallet.equalsIgnoreCase(text(terms, "attestor")))
                || wallet.equalsIgnoreCase(text(terms, "payer"))
                || wallet.equalsIgnoreCase(text(terms, "payee"));
    }

    private WorkflowRepository.RequestResult queue(String actor, String route, String key, String action,
            String target, Function function, String resource, String kind, Runnable createResource) {
        guard.verify();
        String data = FunctionEncoder.encode(function);
        return workflows.createBusiness(actor, route, key, Hashes.sha256(action + "|" + data + "|" + resource),
                action, target, data, normalize(resource), kind, createResource);
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalArgumentException("Terms cannot be encoded", exception); }
    }
    private static String text(Map<String, Object> terms, String key) { return terms.get(key).toString(); }
    private static byte[] bytes(String id) { return Numeric.hexStringToByteArray(id); }
    private static boolean oneOf(String actual, String... allowed) {
        for (String value : allowed) if (value != null && value.equalsIgnoreCase(actual)) return true;
        return false;
    }
    private static void requireAmount(BigInteger value) {
        if (value == null || value.signum() <= 0) throw new IllegalArgumentException("Amount must be positive");
    }
    private static void requireAddress(String value) {
        if (value == null || !value.matches("0x[0-9a-fA-F]{40}")) throw new IllegalArgumentException("Invalid address");
    }
    private static void requireId(String value) {
        if (value == null || !value.matches("0x[0-9a-fA-F]{64}") || BigInteger.ZERO.equals(new BigInteger(value.substring(2), 16)))
            throw new IllegalArgumentException("ID must be a non-zero bytes32 value");
    }
    private static String normalize(String value) { return value.toLowerCase(Locale.ROOT); }
}
