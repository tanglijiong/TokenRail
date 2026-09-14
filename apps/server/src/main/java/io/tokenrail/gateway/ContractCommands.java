package io.tokenrail.gateway;

import io.tokenrail.config.TokenRailProperties;
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
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint64;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.utils.Numeric;

@Component
public class ContractCommands {
    private final Web3j web3j;
    private final BlockchainGateway chain;
    private final TokenRailProperties properties;

    public ContractCommands(Web3j web3j, BlockchainGateway chain, TokenRailProperties properties) {
        this.web3j = web3j;
        this.chain = chain;
        this.properties = properties;
    }

    public void setIdentity(String keyFile, String account, byte[] participantRef) {
        send(keyFile, properties.identityRegistryAddress(), new Function("setIdentity", List.of(
                new Address(account), new Bytes32(participantRef), new Uint64(4_102_444_800L),
                new Bool(true), new Bool(false), new Bool(true), new Bool(true)), List.of()));
    }

    public void issueBond(String keyFile, byte[] issueId, String recipient, BigInteger amount) {
        send(keyFile, properties.bondTokenAddress(), new Function("issue", List.of(
                new Bytes32(issueId), new Address(recipient), new Uint256(amount)), List.of()));
    }

    public void approve(String keyFile, String token, String spender, BigInteger amount) {
        send(keyFile, token, new Function("approve", List.of(
                new Address(spender), new Uint256(amount)), List.of()));
    }

    public void createInstruction(
            String sellerKeyFile, byte[] instructionId, String buyer,
            BigInteger bondQuantity, BigInteger cashAmount, long expiry) {
        send(sellerKeyFile, properties.dvpAddress(), new Function("createInstruction", List.of(
                new Bytes32(instructionId), new Address(buyer), new Uint256(bondQuantity),
                new Uint256(cashAmount), new Uint64(expiry)), List.of()));
    }

    public void confirmInstruction(String buyerKeyFile, byte[] instructionId) {
        send(buyerKeyFile, properties.dvpAddress(), new Function("confirmInstruction",
                List.of(new Bytes32(instructionId)), List.of()));
    }

    public TransactionReceipt executeInstruction(String operatorKeyFile, byte[] instructionId) {
        return send(operatorKeyFile, properties.dvpAddress(), new Function("execute",
                List.of(new Bytes32(instructionId)), List.of()));
    }

    public BigInteger balanceOf(String token, String account) {
        Function function = new Function("balanceOf", List.of(new Address(account)),
                List.of(new TypeReference<Uint256>() { }));
        try {
            String value = web3j.ethCall(Transaction.createEthCallTransaction(
                    account, token, FunctionEncoder.encode(function)), DefaultBlockParameterName.LATEST)
                    .send().getValue();
            List<Type> decoded = FunctionReturnDecoder.decode(value, function.getOutputParameters());
            return (BigInteger) decoded.getFirst().getValue();
        } catch (IOException exception) {
            throw new BlockchainGateway.ChainAccessException("Cannot read token balance", exception);
        }
    }

    private TransactionReceipt send(String keyFile, String contract, Function function) {
        Credentials signer = chain.credentials(keyFile);
        BigInteger nonce = chain.pendingNonce(signer.getAddress());
        PreparedTransaction prepared = chain.prepareCall(signer, nonce, contract, function);
        String returnedHash = chain.broadcast(prepared.rawTransaction());
        if (!prepared.transactionHash().equalsIgnoreCase(returnedHash)) {
            throw new IllegalStateException("RPC returned a different transaction hash");
        }
        for (int attempt = 0; attempt < 80; attempt++) {
            var receipt = chain.receipt(returnedHash);
            if (receipt.isPresent()) {
                TransactionReceipt mined = receipt.get();
                if (!mined.isStatusOK()) {
                    throw new IllegalStateException("Contract call reverted: " + returnedHash);
                }
                return mined;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        }
        throw new IllegalStateException("Timed out waiting for transaction receipt: " + returnedHash);
    }

    public static byte[] bytes32(String hex) {
        byte[] value = Numeric.hexStringToByteArray(hex);
        if (value.length != 32) {
            throw new IllegalArgumentException("Expected bytes32");
        }
        return value;
    }
}
