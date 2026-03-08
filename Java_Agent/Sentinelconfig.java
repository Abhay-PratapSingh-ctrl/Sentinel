package com.sentinel.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import lombok.Data;

/**
 * SentinelConfig
 *
 * Binds all sentinel.* properties from application.properties
 * into a single typed object. Injected wherever needed via @Autowired.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "sentinel")
public class SentinelConfig {

    // ── Network ───────────────────────────────────────────────────
    private String rpcUrl;
    private long   chainId;

    // ── Contracts ─────────────────────────────────────────────────
    private String vaultAddress;
    private String susdAddress;

    // ── Wallet ────────────────────────────────────────────────────
    private String guardianPrivateKey;

    // ── Oracle ────────────────────────────────────────────────────
    private String pythApiUrl;
    private String dotPriceFeedId;

    // ── Health Factor Thresholds (as percentages, e.g. 130 = 130%) ─
    private int hfWarningThreshold;      // 130 — send Telegram warning
    private int hfPauseThreshold;        // 125 — pause the position
    private int hfRebalanceThreshold;    // 120 — trigger emergency rebalance
    private int hfSafeThreshold;         // 150 — fully safe

    // ── Scheduler ─────────────────────────────────────────────────
    private long monitorIntervalMs;

    // ── Telegram ──────────────────────────────────────────────────
    private String telegramBotToken;
    private String telegramBotUsername;

    // ── Gas ───────────────────────────────────────────────────────
    private long   gasLimit;
    private double gasPriceGwei;

    /**
     * Converts a percentage threshold to the 1e18-scaled value
     * that the Solidity contract uses.
     *
     * Example: hfWarningThreshold = 130
     *   → returns 1_300_000_000_000_000_000L (1.3 * 1e18)
     *
     * This is used when comparing against getHealthFactor() output.
     */
    public java.math.BigInteger thresholdToScaled(int percentageThreshold) {
        return java.math.BigInteger.valueOf(percentageThreshold)
                .multiply(java.math.BigInteger.TEN.pow(16)); // * 1e16 = threshold * 1e18 / 100
    }
}