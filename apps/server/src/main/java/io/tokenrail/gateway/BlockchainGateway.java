package io.tokenrail.gateway;

import io.tokenrail.config.TokenRailProperties;
import io.tokenrail.ledger.DepositOperation;
import io.tokenrail.ledger.OperationKind;
import io.tokenrail.projection.TransferLog;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.utils.Numeric;

@Component
public class BlockchainGateway {
    private static final BigInteger GAS_LIMIT = BigInteger.valueOf(700_000);
    public static final String TRANSFER_TOPIC = Hash.sha3String("Transfer(address,address,uint256)");
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final Web3j web3j;
    private final TokenRailProperties properties;

    public BlockchainGateway(Web3j web3j, TokenRailProperties properties) {
        this.web3j = web3j;
        this.properties = properties;
    }

    public Credentials credentials(String keyFile) {
        try {
            String privateKey = Files.readString(Path.of(keyFile)).trim();
            return Credentials.create(privateKey);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read signer key file: " + keyFile, exception);
        }
    }

    public Credentials signer(DepositOperation operation) {
        String keyFile = operation.kind() == OperationKind.CONVERSION
                ? properties.issuerPrivateKeyFile() : operation.signerKeyFile();
        if (keyFile == null || keyFile.isBlank()) {
            throw new IllegalStateException("Signer key file is not configured");
        }
        return credentials(keyFile);
    }

    public BigInteger pendingNonce(String signerAddress) {
        try {
            return web3j.ethGetTransactionCount(
                    signerAddress, DefaultBlockParameterName.PENDING).send().getTransactionCount();
        } catch (IOException exception) {
            throw new ChainAccessException("Cannot read pending nonce", exception);
        }
    }

    public PreparedTransaction prepare(
            DepositOperation operation, Credentials signer, BigInteger nonce) {
        if (!properties.deploymentId().equals(operation.deploymentId())) throw new IllegalStateException("Operation belongs to another deployment");
        if (operation.kind() == OperationKind.BUSINESS) {
            var command = jdbc.queryForMap("select target_address, call_data from business_command where operation_id = ?", operation.operationId());
            return prepareData(signer, nonce, (String) command.get("target_address"), (String) command.get("call_data"));
        }
        Function function = operation.kind() == OperationKind.CONVERSION
                ? new Function("mintForConversion", List.of(
                        new Bytes32(Numeric.hexStringToByteArray(operation.businessId())),
                        new Address(operation.walletAddress()), new Uint256(operation.amount())), List.of())
                : new Function("requestRedemption", List.of(
                        new Bytes32(Numeric.hexStringToByteArray(operation.businessId())),
                        new Uint256(operation.amount())), List.of());
        return prepareCall(signer, nonce, properties.depositTokenAddress(), function);
    }

    public PreparedTransaction prepareCall(
            Credentials signer, BigInteger nonce, String contractAddress, Function function) {
        return prepareData(signer, nonce, contractAddress, FunctionEncoder.encode(function));
    }

    private PreparedTransaction prepareData(Credentials signer, BigInteger nonce, String contractAddress, String data) {
        RawTransaction raw = RawTransaction.createTransaction(
                nonce, BigInteger.ZERO, GAS_LIMIT, contractAddress, BigInteger.ZERO,
                data);
        byte[] signed = TransactionEncoder.signMessage(raw, properties.chainId(), signer);
        String rawHex = Numeric.toHexString(signed);
        return new PreparedTransaction(rawHex, Hash.sha3(rawHex));
    }

    public String broadcast(String rawTransaction) {
        try {
            EthSendTransaction response = web3j.ethSendRawTransaction(rawTransaction).send();
            if (response.hasError()) {
                String message = response.getError().getMessage();
                if (message != null && message.toLowerCase().contains("known transaction")) {
                    return Hash.sha3(rawTransaction);
                }
                throw new ChainAccessException("Broadcast rejected: " + message);
            }
            return response.getTransactionHash();
        } catch (IOException exception) {
            throw new ChainAccessException("Broadcast result is uncertain", exception);
        }
    }

    public Optional<TransactionReceipt> receipt(String transactionHash) {
        try {
            return web3j.ethGetTransactionReceipt(transactionHash).send().getTransactionReceipt();
        } catch (IOException exception) {
            throw new ChainAccessException("Cannot read transaction receipt", exception);
        }
    }

    public String genesisHash() {
        try {
            return web3j.ethGetBlockByNumber(
                    org.web3j.protocol.core.DefaultBlockParameter.valueOf(BigInteger.ZERO), false)
                    .send().getBlock().getHash();
        } catch (IOException exception) {
            throw new ChainAccessException("Cannot read genesis block", exception);
        }
    }

    public BigInteger chainId() {
        try {
            return web3j.ethChainId().send().getChainId();
        } catch (IOException exception) {
            throw new ChainAccessException("Cannot read chain ID", exception);
        }
    }

    public long headBlockNumber() {
        try {
            return web3j.ethBlockNumber().send().getBlockNumber().longValueExact();
        } catch (IOException exception) {
            throw new ChainAccessException("Cannot read chain head", exception);
        }
    }

    public String blockHash(long blockNumber) {
        try {
            var response = web3j.ethGetBlockByNumber(
                    DefaultBlockParameter.valueOf(BigInteger.valueOf(blockNumber)), false).send();
            if (response.getBlock() == null) {
                throw new SnapshotUnavailableException("Block " + blockNumber + " is unavailable");
            }
            return response.getBlock().getHash();
        } catch (IOException exception) {
            throw new SnapshotUnavailableException("Block " + blockNumber + " is unavailable", exception);
        }
    }

    public List<TransferLog> transferLogs(long blockNumber, List<String> contractAddresses) {
        DefaultBlockParameter block = DefaultBlockParameter.valueOf(BigInteger.valueOf(blockNumber));
        EthFilter filter = new EthFilter(block, block, contractAddresses);
        filter.addSingleTopic(TRANSFER_TOPIC);
        try {
            var response = web3j.ethGetLogs(filter).send();
            if (response.hasError()) {
                throw new ChainAccessException("Cannot read block logs: " + response.getError().getMessage());
            }
            return response.getLogs().stream().map(result -> mapTransfer((Log) result.get())).toList();
        } catch (IOException exception) {
            throw new ChainAccessException("Cannot read block logs", exception);
        }
    }

    public BigInteger tokenBalanceAt(String token, String wallet, long blockNumber) {
        Function function = new Function("balanceOf", List.of(new Address(wallet)),
                List.of(new TypeReference<Uint256>() { }));
        return callUint(token, wallet, function, blockNumber);
    }

    public BigInteger totalSupplyAt(String token, long blockNumber) {
        Function function = new Function("totalSupply", List.of(),
                List.of(new TypeReference<Uint256>() { }));
        return callUint(token, null, function, blockNumber);
    }

    private BigInteger callUint(
            String contract, String from, Function function, long blockNumber) {
        try {
            EthCall response = web3j.ethCall(Transaction.createEthCallTransaction(
                    from, contract, FunctionEncoder.encode(function)),
                    DefaultBlockParameter.valueOf(BigInteger.valueOf(blockNumber))).send();
            if (response.hasError() || response.isReverted()) {
                String detail = response.hasError() ? response.getError().getMessage()
                        : response.getRevertReason();
                throw new SnapshotUnavailableException(
                        "Historical contract state is unavailable at block " + blockNumber + ": " + detail);
            }
            List<Type> decoded = FunctionReturnDecoder.decode(
                    response.getValue(), function.getOutputParameters());
            if (decoded.isEmpty()) {
                throw new SnapshotUnavailableException(
                        "Historical contract state returned no value at block " + blockNumber);
            }
            return (BigInteger) decoded.getFirst().getValue();
        } catch (IOException exception) {
            throw new SnapshotUnavailableException(
                    "Historical contract state is unavailable at block " + blockNumber, exception);
        }
    }

    private static TransferLog mapTransfer(Log log) {
        List<String> topics = log.getTopics();
        if (topics.size() != 3) {
            throw new ChainAccessException("Malformed Transfer log topics");
        }
        return new TransferLog(
                log.getBlockNumber().longValueExact(), log.getBlockHash(), log.getTransactionHash(),
                log.getTransactionIndex().longValueExact(), log.getLogIndex().longValueExact(),
                log.getAddress().toLowerCase(java.util.Locale.ROOT), topics, log.getData(),
                topicAddress(topics.get(1)), topicAddress(topics.get(2)),
                Numeric.toBigInt(log.getData()));
    }

    private static String topicAddress(String topic) {
        String clean = Numeric.cleanHexPrefix(topic);
        if (clean.length() != 64) {
            throw new ChainAccessException("Malformed indexed address topic");
        }
        return "0x" + clean.substring(24).toLowerCase(java.util.Locale.ROOT);
    }

    public static final class ChainAccessException extends RuntimeException {
        public ChainAccessException(String message) { super(message); }
        public ChainAccessException(String message, Throwable cause) { super(message, cause); }
    }

    public static final class SnapshotUnavailableException extends RuntimeException {
        public SnapshotUnavailableException(String message) { super(message); }
        public SnapshotUnavailableException(String message, Throwable cause) { super(message, cause); }
    }
}
