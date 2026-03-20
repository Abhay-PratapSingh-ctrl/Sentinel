package com.sentinel.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * RebalancerService
 *
 * Calls SentinelRebalancer.sol on behalf of the guardian bot wallet.
 * Uses batchRepay() instead of vault.emergencyRebalance() because
 * the DEX router is not set (address(0)) on this testnet deployment.
 *
 * Flow:
 *   1. Bot detects HF < 120% via MonitoringService
 *   2. MonitoringService calls rebalancerService.triggerBatchRepay()
 *   3. This service sends batchRepay() tx using the guardian wallet
 *   4. SentinelRebalancer pulls pre-approved sUSD from user → burns debt
 *   5. HF is restored without any DEX swap
 *
 * Prerequisites (one-time setup):
 *   - Deploy SentinelRebalancer.sol with vault + sUSD addresses
 *   - Call setBot(guardianWallet, true) from deployer wallet
 *   - User clicks "Enable Guardian" on frontend (approve + register)
 */
@Service
@Slf4j
public class RebalancerService {

    @Value("${sentinel.guardian-private-key}")
    private String botPrivateKey;

    @Value("${sentinel.rebalancer.address}")
    private String rebalancerAddress;

    private final Web3j web3j;
    private Credentials botCredentials;

    public RebalancerService(Web3j web3j) {
        this.web3j = web3j;
    }

    @PostConstruct
    public void init() {
        try {
            botCredentials = Credentials.create(botPrivateKey);
            log.info("✅ RebalancerService ready. Bot wallet: {}", botCredentials.getAddress());
            log.info("✅ SentinelRebalancer contract: {}", rebalancerAddress);
        } catch (Exception e) {
            log.error("❌ RebalancerService init failed: {}", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────
    //  PUBLIC API — called by MonitoringService
    // ─────────────────────────────────────────────

    /**
     * Sends batchRepay() transaction.
     * The contract loops through all registered users and repays
     * the minimum sUSD needed for those below TRIGGER_HF (130%).
     *
     * @return tx hash, or null if failed
     */
    public String triggerBatchRepay() throws Exception {
        log.warn("[Rebalancer] Sending batchRepay()...");

        Function fn = new Function(
            "batchRepay",
            Collections.emptyList(),
            Collections.emptyList()
        );

        String txHash = sendTx(FunctionEncoder.encode(fn));

        if (txHash == null) {
            throw new RuntimeException("batchRepay TX hash null — rejected by node");
        }

        log.info("[Rebalancer] ✅ batchRepay tx sent: {}", txHash);
        return txHash;
    }

    /**
     * Check if a specific user needs rebalancing.
     * Used by MonitoringService to decide whether to trigger batchRepay.
     *
     * @return [repayNeeded (uint256), shouldAct (bool), currentHF (uint256)]
     */
    public List<Object> getRepayAmount(String userAddr) throws Exception {
        Function fn = new Function(
            "getRepayAmount",
            List.of(new Address(userAddr)),
            List.of(
                new TypeReference<Uint256>() {},
                new TypeReference<Bool>() {},
                new TypeReference<Uint256>() {}
            )
        );

        EthCall result = web3j.ethCall(
            Transaction.createEthCallTransaction(
                botCredentials.getAddress(),
                rebalancerAddress,
                FunctionEncoder.encode(fn)
            ),
            DefaultBlockParameterName.LATEST
        ).send();

        if (result.hasError()) {
            log.error("[Rebalancer] getRepayAmount error for {}: {}",
                userAddr, result.getError().getMessage());
            return Collections.emptyList();
        }

        return FunctionReturnDecoder.decode(
            result.getValue(), fn.getOutputParameters()
        ).stream().map(Type::getValue).collect(Collectors.toList());
    }

    /**
     * Get all users registered with the rebalancer contract.
     */
    public List<String> getRegisteredUsers() throws Exception {
        Function fn = new Function(
            "getUsers",
            Collections.emptyList(),
            List.of(new TypeReference<DynamicArray<Address>>() {})
        );

        EthCall result = web3j.ethCall(
            Transaction.createEthCallTransaction(
                botCredentials.getAddress(),
                rebalancerAddress,
                FunctionEncoder.encode(fn)
            ),
            DefaultBlockParameterName.LATEST
        ).send();

        if (result.hasError()) {
            log.error("[Rebalancer] getUsers error: {}", result.getError().getMessage());
            return Collections.emptyList();
        }

        List<Type> decoded = FunctionReturnDecoder.decode(
            result.getValue(), fn.getOutputParameters()
        );

        if (decoded.isEmpty()) return Collections.emptyList();

        @SuppressWarnings("unchecked")
        List<Address> addresses = (List<Address>) decoded.get(0).getValue();

        return addresses.stream()
            .map(Address::getValue)
            .collect(Collectors.toList());
    }

    /**
     * Check if a user is registered with the rebalancer.
     */
    public boolean isUserRegistered(String userAddr) {
        try {
            Function fn = new Function(
                "isRegistered",
                List.of(new Address(userAddr)),
                List.of(new TypeReference<Bool>() {})
            );

            EthCall result = web3j.ethCall(
                Transaction.createEthCallTransaction(
                    botCredentials.getAddress(),
                    rebalancerAddress,
                    FunctionEncoder.encode(fn)
                ),
                DefaultBlockParameterName.LATEST
            ).send();

            if (result.hasError()) return false;

            List<Type> decoded = FunctionReturnDecoder.decode(
                result.getValue(), fn.getOutputParameters()
            );

            return !decoded.isEmpty() && (Boolean) decoded.get(0).getValue();

        } catch (Exception e) {
            log.error("[Rebalancer] isRegistered check failed for {}: {}", userAddr, e.getMessage());
            return false;
        }
    }

    // ─────────────────────────────────────────────
    //  INTERNAL TX SENDER
    // ─────────────────────────────────────────────

    private String sendTx(String encodedFn) throws Exception {
        BigInteger nonce = web3j.ethGetTransactionCount(
            botCredentials.getAddress(),
            DefaultBlockParameterName.LATEST
        ).send().getTransactionCount();

        BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();

        RawTransaction tx = RawTransaction.createTransaction(
            nonce,
            gasPrice,
            BigInteger.valueOf(500_000L),
            rebalancerAddress,
            encodedFn
        );

        byte[] signed = TransactionEncoder.signMessage(tx, botCredentials);

        return web3j.ethSendRawTransaction(
            Numeric.toHexString(signed)
        ).send().getTransactionHash();
    }
}