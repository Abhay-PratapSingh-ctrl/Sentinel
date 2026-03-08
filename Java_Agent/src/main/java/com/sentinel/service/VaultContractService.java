package com.sentinel.service;

import com.sentinel.config.SentinelConfig;
import com.sentinel.model.VaultPosition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.*;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.*;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Convert;
import org.web3j.utils.Numeric;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * VaultContractService
 *
 * Single point of contact between the Java bot and SentinelVault.sol.
 *
 * READ operations (no gas):
 *   getAllUsers(), getHealthFactor(user), getPosition(user)
 *
 * WRITE operations (guardian wallet signs & broadcasts):
 *   pausePosition(), unpausePosition(), emergencyRebalance()
 */
@Slf4j
@Service
public class VaultContractService {

    private final SentinelConfig config;
    private Web3j       web3j;
    private Credentials guardianCredentials;

    public VaultContractService(SentinelConfig config) {
        this.config = config;
    }

    @PostConstruct
    public void init() {
        log.info("Connecting to Polkadot Hub EVM at: {}", config.getRpcUrl());
        this.web3j = Web3j.build(new HttpService(config.getRpcUrl()));
        this.guardianCredentials = Credentials.create(config.getGuardianPrivateKey());
        log.info("Guardian wallet loaded: {}", guardianCredentials.getAddress());
        log.info("Monitoring vault: {}", config.getVaultAddress());
    }

    // ═══════════════════════════════════════════
    //  READ FUNCTIONS
    // ═══════════════════════════════════════════

    public List<String> getAllUsers() throws Exception {
        Function function = new Function(
                "getAllUsers",
                Collections.emptyList(),
                Collections.singletonList(new TypeReference<DynamicArray<Address>>() {})
        );

        String encodedFunction = FunctionEncoder.encode(function);
        EthCall response = web3j.ethCall(
                Transaction.createEthCallTransaction(
                        guardianCredentials.getAddress(),
                        config.getVaultAddress(),
                        encodedFunction
                ),
                DefaultBlockParameterName.LATEST
        ).send();

        if (response.hasError()) {
            throw new RuntimeException("getAllUsers() failed: " + response.getError().getMessage());
        }

        List<Type> result = FunctionReturnDecoder.decode(
                response.getValue(), function.getOutputParameters());

        if (result.isEmpty()) return Collections.emptyList();

        @SuppressWarnings("unchecked")
        List<Address> addresses = (List<Address>) ((DynamicArray<?>) result.get(0)).getValue();
        return addresses.stream().map(Address::getValue).collect(Collectors.toList());
    }

    public VaultPosition getPosition(String userAddress) throws Exception {
        Function function = new Function(
                "getPosition",
                Collections.singletonList(new Address(userAddress)),
                Arrays.asList(
                        new TypeReference<Uint256>() {},   // collateralDOT
                        new TypeReference<Uint256>() {},   // mintedSUSD
                        new TypeReference<Uint256>() {},   // collateralUSD
                        new TypeReference<Uint256>() {},   // healthFactor
                        new TypeReference<Bool>() {}       // paused
                )
        );

        String encodedFunction = FunctionEncoder.encode(function);
        EthCall response = web3j.ethCall(
                Transaction.createEthCallTransaction(
                        guardianCredentials.getAddress(),
                        config.getVaultAddress(),
                        encodedFunction
                ),
                DefaultBlockParameterName.LATEST
        ).send();

        if (response.hasError()) {
            throw new RuntimeException("getPosition() failed for " + userAddress
                    + ": " + response.getError().getMessage());
        }

        List<Type> result = FunctionReturnDecoder.decode(
                response.getValue(), function.getOutputParameters());

        BigInteger collateralDOT = ((Uint256) result.get(0)).getValue();
        BigInteger mintedSUSD    = ((Uint256) result.get(1)).getValue();
        BigInteger collateralUSD = ((Uint256) result.get(2)).getValue();
        BigInteger healthFactor  = ((Uint256) result.get(3)).getValue();
        boolean    paused        = ((Bool)    result.get(4)).getValue();

        return VaultPosition.builder()
                .userAddress(userAddress)
                .collateralDOT(collateralDOT)
                .mintedSUSD(mintedSUSD)
                .collateralUSD(collateralUSD)
                .healthFactor(healthFactor)
                .paused(paused)
                .fetchedAt(System.currentTimeMillis())
                .riskLevel(computeRiskLevel(healthFactor, mintedSUSD))
                .build();
    }

    // ═══════════════════════════════════════════
    //  WRITE FUNCTIONS (guardian wallet signs)
    // ═══════════════════════════════════════════

    public String pausePosition(String userAddress, String reason) throws Exception {
        log.info("Guardian: pausing position for {} — reason: {}", userAddress, reason);
        Function function = new Function(
                "pausePosition",
                Arrays.asList(new Address(userAddress), new Utf8String(reason)),
                Collections.emptyList()
        );
        return sendGuardianTransaction(function);
    }

    public String unpausePosition(String userAddress) throws Exception {
        log.info("Guardian: unpausing position for {}", userAddress);
        Function function = new Function(
                "unpausePosition",
                Collections.singletonList(new Address(userAddress)),
                Collections.emptyList()
        );
        return sendGuardianTransaction(function);
    }

    public String emergencyRebalance(String userAddress,
                                     BigInteger dotToSell,
                                     BigInteger minSUSDOut) throws Exception {
        log.info("Guardian: emergency rebalance for {} — selling {} wei DOT, min {} sUSD out",
                userAddress, dotToSell, minSUSDOut);
        Function function = new Function(
                "emergencyRebalance",
                Arrays.asList(
                        new Address(userAddress),
                        new Uint256(dotToSell),
                        new Uint256(minSUSDOut)
                ),
                Collections.emptyList()
        );
        return sendGuardianTransaction(function);
    }

    // ─────────────────────────────────────────
    //  PRIVATE HELPERS
    // ─────────────────────────────────────────

    private String sendGuardianTransaction(Function function) throws Exception {
        String encodedFunction = FunctionEncoder.encode(function);

        EthGetTransactionCount txCountResponse = web3j
                .ethGetTransactionCount(
                        guardianCredentials.getAddress(),
                        DefaultBlockParameterName.LATEST)
                .send();
        BigInteger nonce = txCountResponse.getTransactionCount();

        BigInteger gasPrice = Convert.toWei(
                BigDecimal.valueOf(config.getGasPriceGwei()), Convert.Unit.GWEI).toBigInteger();
        BigInteger gasLimit = BigInteger.valueOf(config.getGasLimit());
        BigInteger chainId  = BigInteger.valueOf(config.getChainId());

        RawTransaction rawTx = RawTransaction.createTransaction(
                chainId.longValue(),
                nonce,
                gasLimit,
                config.getVaultAddress(),
                BigInteger.ZERO,
                encodedFunction,
                gasPrice,
                gasPrice
        );

        byte[] signedTx = TransactionEncoder.signMessage(rawTx, chainId.longValue(), guardianCredentials);
        String hexSignedTx = Numeric.toHexString(signedTx);

        EthSendTransaction sendResponse = web3j.ethSendRawTransaction(hexSignedTx).send();
        if (sendResponse.hasError()) {
            throw new RuntimeException("Transaction failed: " + sendResponse.getError().getMessage());
        }

        String txHash = sendResponse.getTransactionHash();
        log.info("Guardian transaction sent! TX Hash: {}", txHash);
        log.info("Explorer: https://polkadot-hub-testnet.blockscout.com/tx/{}", txHash);
        return txHash;
    }

    private VaultPosition.RiskLevel computeRiskLevel(BigInteger healthFactor, BigInteger mintedSUSD) {
        if (mintedSUSD.compareTo(BigInteger.ZERO) == 0)  return VaultPosition.RiskLevel.NO_DEBT;
        if (healthFactor.bitLength() > 200)               return VaultPosition.RiskLevel.NO_DEBT;

        BigInteger hfSafe      = config.thresholdToScaled(config.getHfSafeThreshold());
        BigInteger hfWarn      = config.thresholdToScaled(config.getHfWarningThreshold());
        BigInteger hfPause     = config.thresholdToScaled(config.getHfPauseThreshold());
        BigInteger hfRebalance = config.thresholdToScaled(config.getHfRebalanceThreshold());

        if (healthFactor.compareTo(hfSafe) >= 0)      return VaultPosition.RiskLevel.SAFE;
        if (healthFactor.compareTo(hfWarn) >= 0)      return VaultPosition.RiskLevel.WARNING;
        if (healthFactor.compareTo(hfPause) >= 0)     return VaultPosition.RiskLevel.DANGER;
        if (healthFactor.compareTo(hfRebalance) >= 0) return VaultPosition.RiskLevel.CRITICAL;
        return VaultPosition.RiskLevel.LIQUIDATABLE;
    }
}
