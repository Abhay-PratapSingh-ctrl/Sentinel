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
    private long chainId;

    // ── Contracts ─────────────────────────────────────────────────
    private String vaultAddress;
    private String susdAddress;
    private String rebalancerAddress;

    // ── Wallet ────────────────────────────────────────────────────
    private String guardianPrivateKey;

    // ── Oracle ────────────────────────────────────────────────────
    private String pythApiUrl;
    private String dotPriceFeedId;

    // ── Health Factor Thresholds (as percentages, e.g. 130 = 130%) ─
    private int hfWarningThreshold; // 130 — send Telegram warning
    private int hfPauseThreshold; // 125 — pause the position
    private int hfRebalanceThreshold; // 120 — trigger emergency rebalance
    private int hfSafeThreshold; // 150 — fully safe

    // ── Scheduler ─────────────────────────────────────────────────
    private long monitorIntervalMs;

    // ── Telegram Bot ───────────────────────────────────────────────
    private String telegramBotToken;
    private String telegramBotUsername;

    // ── Strategy ──────────────────────────────────────────────────
    // "CONSERVATIVE" or "AGGRESSIVE"
    private String strategyMode = "CONSERVATIVE";

    // ── Aegis Dynamic Buffer ───────────────────────────────────────
    // USD std-dev volatility level that activates the elevated safety threshold
    private double aegisVolatilityThreshold = 0.30;
    // Health Factor % target during red-flag market conditions (replaces
    // hfSafeThreshold)
    private int aegisElevatedBuffer = 170;

    // ── LLM Risk Reports ──────────────────────────────────────────
    // Optional: OpenAI API key for GPT-powered /why explanations.
    // Leave blank to use the built-in template fallback.
    private String openAiApiKey;
    private String openAiModel = "gpt-4o-mini";
    private String anthropicApiKey;
    private String geminiApiKey;
    private String groqApiKey;

    // ── Multi-Collateral (USDT) ───────────────────────────────────
    // ERC-20 precompile address for native USDT on Polkadot Hub.
    // Asset Hub USDT (assetId=1984) → precompile: 0xFFFFFFFF000007C0
    private String usdtTokenAddress;

    // ── PVM Precompile (Trustless Rebalancing Brain) ──────────────
    // Address of the deployed PVM precompile on Polkadot Hub.
    // Set to empty string to skip PVM verification (graceful degradation).
    private String pvmPrecompileAddress;

    // ── Gas ───────────────────────────────────────────────────────
    private long gasLimit;
    private double gasPriceGwei;

    /**
     * Converts a percentage threshold to the 1e18-scaled value
     * that the Solidity contract uses.
     *
     * Example: hfWarningThreshold = 130
     * → returns 1_300_000_000_000_000_000L (1.3 * 1e18)
     *
     * This is used when comparing against getHealthFactor() output.
     */
    public java.math.BigInteger thresholdToScaled(int percentageThreshold) {
        return java.math.BigInteger.valueOf(percentageThreshold)
                .multiply(java.math.BigInteger.TEN.pow(16)); // * 1e16 = threshold * 1e18 / 100
    }
}
