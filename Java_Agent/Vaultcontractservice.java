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
 * ─────────────────────────────────────────────────────────────
 * WHAT IT SHOULD DO:
 *   Be the single point of contact between the Java bot and
 *   SentinelVault.sol on the Polkadot Hub EVM chain.
 *
 * WHAT IT ACTUALLY DOES:
 *   READ operations (no gas, instant):
 *     - getAllUsers()        → returns every wallet with a position
 *     - getHealthFactor(user) → returns HF scaled to 1e18
 *     - getPosition(user)   → returns full position snapshot
 *
 *   WRITE operations (guardian wallet signs & broadcasts tx):
 *     - pausePosition(user, reason)           → freezes risky position
 *     - unpausePosition(user)                 → unfreezes after fix
 *     - emergencyRebalance(user, dot, minOut) → triggers DEX swap
 *
 *   Uses raw ABI encoding via Web3j — no auto-generated wrapper needed.
 *   This way you don't need to run `web3j generate` during the hackathon.
 * ─────────────────────────────────────────────────────────────
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

    /**
     * Called automatically by Spring after the bean is constructed.
     * Sets up the Web3j connection and loads the guardian wallet.
     */
    @PostConstruct
    public void init() {
        log.info("Connecting to Polkadot Hub EVM at: {}", config.getRpcUrl());
        this.web3j = Web3j.build(new HttpService(config.getRpcUrl()));
        this.guardianCredentials = Credentials.create(config.getGuardianPrivateKey());

        log.info("Guardian wallet loaded: {}", guardianCredentials.getAddress());
        log.info("Monitoring vault: {}", config.getVaultAddress());
    }

    // ═════════════════════════════════════════════
    //  READ FUNCTIONS — called every 60 seconds
    // ═════════════════════════════════════════════

    /**
     * Calls getAllUsers() on the vault.
     *
     * WHAT IT DOES:
     *   1. Encodes the ABI call for getAllUsers() (no parameters).
     *   2. Sends an eth_call (read-only, no gas) to the node.
     *   3. Decodes the returned address[] array.
     *   4. Returns a List<String> of wallet addresses.
     *
     * The monitoring loop iterates this list to check each position.
     */
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
                response.getValue(),
                function.getOutputParameters()
        );

        if (result.isEmpty()) return Collections.emptyList();

        @SuppressWarnings("unchecked")
        List<Address> addresses = (List<Address>) ((DynamicArray<?>) result.get(0)).getValue();
        return addresses.stream()
                .map(addr -> addr.getValue())
                .collect(Collectors.toList());
    }

    /**
     * Calls getPosition(address user) on the vault.
     *
     * WHAT IT DOES:
     *   1. Encodes the ABI call with the user's address.
     *   2. Decodes the 5-value tuple: (collateralDOT, mintedSUSD, collateralUSD, healthFactor, paused)
     *   3. Returns a VaultPosition with all fields populated.
     *   4. Computes the RiskLevel enum based on the health factor.
     *
     * This is the core data the monitoring loop works with.
     */
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
                response.getValue(),
                function.getOutputParameters()
        );

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

    // ═════════════════════════════════════════════
    //  WRITE FUNCTIONS — guardian wallet signs these
    // ═════════════════════════════════════════════

    /**
     * Calls pausePosition(address user, string reason) on the vault.
     *
     * WHAT IT DOES:
     *   1. Builds and ABI-encodes the function call.
     *   2. Gets the current nonce for the guardian wallet.
     *   3. Signs the raw transaction with the guardian's private key.
     *   4. Broadcasts it to the Polkadot Hub EVM node.
     *   5. Returns the transaction hash for logging / explorer link.
     *
     * The `reason` string appears in the PositionPaused event
     * and is included in the Telegram alert message.
     */
    public String pausePosition(String userAddress, String reason) throws Exception {
        log.info("Guardian: pausing position for {} — reason: {}", userAddress, reason);

        Function function = new Function(
                "pausePosition",
                Arrays.asList(
                        new Address(userAddress),
                        new Utf8String(reason)
                ),
                Collections.emptyList()
        );

        return sendGuardianTransaction(function);
    }

    /**
     * Calls unpausePosition(address user).
     * Bot calls this after verifying the user added enough collateral.
     */
    public String unpausePosition(String userAddress) throws Exception {
        log.info("Guardian: unpausing position for {}", userAddress);

        Function function = new Function(
                "unpausePosition",
                Collections.singletonList(new Address(userAddress)),
                Collections.emptyList()
        );

        return sendGuardianTransaction(function);
    }

    /**
     * Calls emergencyRebalance(address user, uint256 dotToSell, uint256 minSUSDOut).
     *
     * WHAT IT DOES:
     *   1. Takes the dotToSell amount calculated by MonitoringService.
     *   2. Applies 2% slippage tolerance to compute minSUSDOut.
     *   3. Signs and sends the guardian transaction.
     *   4. Returns tx hash for the Telegram confirmation message.
     *
     * @param userAddress  At-risk user
     * @param dotToSell    DOT amount in wei (1e18) to swap
     * @param minSUSDOut   Minimum sUSD to accept (slippage protection)
     */
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

    // ─────────────────────────────────────────────
    //  PRIVATE HELPERS
    // ─────────────────────────────────────────────

    /**
     * Signs and sends a guardian transaction to the vault.
     *
     * Steps:
     *   1. Get nonce (transaction count) for guardian wallet.
     *   2. Build RawTransaction with encoded function call.
     *   3. Sign with guardian private key.
     *   4. Send via eth_sendRawTransaction.
     *   5. Return tx hash (e.g. for building a Polkadot Hub explorer link).
     */
    private String sendGuardianTransaction(Function function) throws Exception {
        String encodedFunction = FunctionEncoder.encode(function);

        // Get current nonce for guardian wallet
        EthGetTransactionCount txCountResponse = web3j
                .ethGetTransactionCount(
                        guardianCredentials.getAddress(),
                        DefaultBlockParameterName.LATEST
                ).send();
        BigInteger nonce = txCountResponse.getTransactionCount();

        // Gas price from config (Gwei → Wei)
        BigInteger gasPrice = Convert.toWei(
                BigDecimal.valueOf(config.getGasPriceGwei()),
                Convert.Unit.GWEI
        ).toBigInteger();

        BigInteger gasLimit = BigInteger.valueOf(config.getGasLimit());
        BigInteger chainId  = BigInteger.valueOf(config.getChainId());

        // Build the raw transaction
        RawTransaction rawTx = RawTransaction.createTransaction(
                chainId.longValue(),
                nonce,
                gasLimit,
                config.getVaultAddress(),  // `to` address = vault contract
                BigInteger.ZERO,           // value = 0 ETH/DOT (no native token sent)
                encodedFunction,           // ABI encoded function call
                gasPrice,                  // maxPriorityFeePerGas (EIP-1559 style)
                gasPrice                   // maxFeePerGas
        );

        // Sign transaction with guardian private key
        byte[] signedTx = TransactionEncoder.signMessage(rawTx, chainId.longValue(), guardianCredentials);
        String hexSignedTx = Numeric.toHexString(signedTx);

        // Broadcast to node
        EthSendTransaction sendResponse = web3j.ethSendRawTransaction(hexSignedTx).send();

        if (sendResponse.hasError()) {
            throw new RuntimeException("Transaction failed: " + sendResponse.getError().getMessage());
        }

        String txHash = sendResponse.getTransactionHash();
        log.info("Guardian transaction sent! TX Hash: {}", txHash);
        log.info("Explorer: https://polkadot-hub-testnet.blockscout.com/tx/{}", txHash);

        return txHash;
    }

    /**
     * Computes the RiskLevel enum from raw health factor and debt.
     * Thresholds are loaded from config for easy tuning.
     */
    private VaultPosition.RiskLevel computeRiskLevel(BigInteger healthFactor, BigInteger mintedSUSD) {
        // No debt = completely safe
        if (mintedSUSD.compareTo(BigInteger.ZERO) == 0) {
            return VaultPosition.RiskLevel.NO_DEBT;
        }

        // MAX_VALUE means no debt (returned by contract)
        if (healthFactor.bitLength() > 200) {
            return VaultPosition.RiskLevel.NO_DEBT;
        }

        BigInteger hfWarn      = config.thresholdToScaled(config.getHfWarningThreshold());
        BigInteger hfPause     = config.thresholdToScaled(config.getHfPauseThreshold());
        
        // Rebalance threshold depends on strategy
        int rebalancePct = config.getHfRebalanceThreshold(); // Default 120
        if ("CONSERVATIVE".equalsIgnoreCase(config.getStrategyMode())) {
            rebalancePct = 145; // Strategy-based Conservative Rebalance
        } else if ("AGGRESSIVE".equalsIgnoreCase(config.getStrategyMode())) {
            rebalancePct = 115; // Strategy-based Aggressive Rebalance
        }

        BigInteger hfRebalance = config.thresholdToScaled(rebalancePct);
        BigInteger hfSafe      = config.thresholdToScaled(config.getHfSafeThreshold());

        if (healthFactor.compareTo(hfSafe) >= 0)      return VaultPosition.RiskLevel.SAFE;
        if (healthFactor.compareTo(hfWarn) >= 0)      return VaultPosition.RiskLevel.WARNING;
        if (healthFactor.compareTo(hfPause) >= 0)     return VaultPosition.RiskLevel.DANGER;
        if (healthFactor.compareTo(hfRebalance) >= 0) return VaultPosition.RiskLevel.CRITICAL;
        return VaultPosition.RiskLevel.LIQUIDATABLE;
    }
}