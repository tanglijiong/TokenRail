package io.tokenrail.business;

import java.io.IOException;
import java.math.BigInteger;
import java.util.List;
import org.springframework.stereotype.Component;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint8;
import org.web3j.abi.datatypes.generated.Uint64;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;

@Component
public class BusinessGateway {
    public record Settlement(String seller, String buyer,
                             @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) BigInteger bondQuantity,
                             @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) BigInteger cashAmount,
                             long expiry, int state) { }
    public record Payment(String payer, String payee, String attestor,
                          @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) BigInteger amount,
                          long deadline, int state) { }
    public record Identity(String participantRef, long validUntil, boolean active, boolean frozen,
                           boolean canHoldCash, boolean canHoldBond) { }

    private final Web3j web3j;

    public BusinessGateway(Web3j web3j) { this.web3j = web3j; }

    public Settlement settlement(String contract, byte[] id) {
        List<Type> values = call(contract, new Function("instructions", List.of(new Bytes32(id)), List.of(
                new TypeReference<Address>() { }, new TypeReference<Address>() { },
                new TypeReference<Uint256>() { }, new TypeReference<Uint256>() { },
                new TypeReference<Uint64>() { }, new TypeReference<Uint8>() { })));
        return new Settlement(string(values, 0), string(values, 1), integer(values, 2), integer(values, 3),
                integer(values, 4).longValueExact(), integer(values, 5).intValueExact());
    }

    public Payment payment(String contract, byte[] id) {
        List<Type> values = call(contract, new Function("payments", List.of(new Bytes32(id)), List.of(
                new TypeReference<Address>() { }, new TypeReference<Address>() { },
                new TypeReference<Address>() { }, new TypeReference<Uint256>() { },
                new TypeReference<Uint64>() { }, new TypeReference<Uint8>() { })));
        return new Payment(string(values, 0), string(values, 1), string(values, 2), integer(values, 3),
                integer(values, 4).longValueExact(), integer(values, 5).intValueExact());
    }

    public Identity identity(String registry, String wallet) {
        List<Type> values = call(registry, new Function("getIdentity", List.of(new Address(wallet)), List.of(
                new TypeReference<Bytes32>() { }, new TypeReference<Uint64>() { },
                new TypeReference<Bool>() { }, new TypeReference<Bool>() { },
                new TypeReference<Bool>() { }, new TypeReference<Bool>() { })));
        return new Identity(org.web3j.utils.Numeric.toHexString((byte[]) values.get(0).getValue()),
                integer(values, 1).longValueExact(), bool(values, 2), bool(values, 3),
                bool(values, 4), bool(values, 5));
    }

    public BigInteger allowance(String token, String owner, String spender) {
        return integer(call(token, new Function("allowance", List.of(new Address(owner), new Address(spender)),
                List.of(new TypeReference<Uint256>() { }))), 0);
    }

    private List<Type> call(String contract, Function function) {
        try {
            var response = web3j.ethCall(Transaction.createEthCallTransaction(
                    null, contract, FunctionEncoder.encode(function)), DefaultBlockParameterName.LATEST).send();
            if (response.hasError() || response.isReverted()) {
                String detail = response.hasError() ? response.getError().getMessage() : response.getRevertReason();
                throw new IllegalStateException("Contract read failed: " + detail);
            }
            List<Type> values = FunctionReturnDecoder.decode(response.getValue(), function.getOutputParameters());
            if (values.size() != function.getOutputParameters().size()) {
                throw new IllegalStateException("Contract read returned incomplete data");
            }
            return values;
        } catch (IOException exception) {
            throw new IllegalStateException("Contract read failed", exception);
        }
    }

    private static BigInteger integer(List<Type> values, int index) {
        return (BigInteger) values.get(index).getValue();
    }
    private static String string(List<Type> values, int index) {
        return values.get(index).getValue().toString().toLowerCase(java.util.Locale.ROOT);
    }
    private static boolean bool(List<Type> values, int index) {
        return (Boolean) values.get(index).getValue();
    }
}
