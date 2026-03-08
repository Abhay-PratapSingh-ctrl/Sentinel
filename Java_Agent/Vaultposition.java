package com.sentinel.model;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * VaultPosition
 *
 * Represents the on-chain state of a single user's position
 * in SentinelVault, enriched with computed fields for the bot's logic.
 *
 * Populated by VaultReaderService after calling getPosition(user).
 */
@Data
@Builder
public class VaultPosition {

    // ── Raw on-chain data (returned by getPosition()) ─────────────
    private String  userAddress;
    private BigInteger collateralDOT;   // DOT locked (wei, 1e18)
    private BigInteger mintedSUSD;      // sUSD debt (wei, 1e18)
    private BigInteger collateralUSD;   // USD value of collateral (1e18)
    private BigInteger healthFactor;    // Health factor (1e18 scaled)
    private boolean    paused;          // Currently frozen by Sentinel?

    // ── Enriched by bot after fetching ────────────────────────────
    private BigDecimal dotPriceUSD;     // Live DOT price at time of fetch
    private long       fetchedAt;       // Unix timestamp of this snapshot
    private RiskLevel  riskLevel;       // Computed from healthFactor

    // ─────────────────────────────────────────────────────────────
    //  RISK LEVEL ENUM
    //  Maps health factor ranges to human-readable states.
    //  The monitoring loop uses this to decide which action to take.
    // ─────────────────────────────────────────────────────────────
    public enum RiskLevel {
        SAFE,           // HF >= 150% — no action needed
        WARNING,        // HF 130–150% — send Telegram alert, watch closely
        DANGER,         // HF 125–130% — pause position, urgent alert
        CRITICAL,       // HF 120–125% — trigger emergency rebalance NOW
        LIQUIDATABLE,   // HF < 120%  — open to external liquidation
        NO_DEBT         // No sUSD minted — completely safe
    }

    // ─────────────────────────────────────────────────────────────
    //  COMPUTED HELPERS
    // ─────────────────────────────────────────────────────────────

    /**
     * Returns the health factor as a human-readable percentage string.
     * healthFactor is 1e18 scaled, so divide by 1e16 to get percentage.
     * Example: 1_500_000_000_000_000_000 → "150.00%"
     */
    public String getHealthFactorPercent() {
        if (healthFactor.compareTo(BigInteger.TWO.pow(200)) > 0) {
            return "∞ (no debt)";
        }
        BigDecimal hf = new BigDecimal(healthFactor)
                .divide(BigDecimal.TEN.pow(16), 2, RoundingMode.HALF_UP);
        return hf.toPlainString() + "%";
    }

    /**
     * Returns the DOT collateral in human-readable format (not wei).
     * Example: 5_000_000_000_000_000_000 → "5.0000 DOT"
     */
    public String getCollateralDOTFormatted() {
        BigDecimal dot = new BigDecimal(collateralDOT)
                .divide(BigDecimal.TEN.pow(18), 4, RoundingMode.HALF_UP);
        return dot.toPlainString() + " DOT";
    }

    /**
     * Returns the sUSD debt in human-readable format.
     */
    public String getMintedSUSDFormatted() {
        BigDecimal susd = new BigDecimal(mintedSUSD)
                .divide(BigDecimal.TEN.pow(18), 2, RoundingMode.HALF_UP);
        return "$" + susd.toPlainString() + " sUSD";
    }

    /**
     * Returns the USD value of collateral formatted.
     */
    public String getCollateralUSDFormatted() {
        BigDecimal usd = new BigDecimal(collateralUSD)
                .divide(BigDecimal.TEN.pow(18), 2, RoundingMode.HALF_UP);
        return "$" + usd.toPlainString();
    }

    /**
     * Truncates the wallet address for display in Telegram messages.
     * Example: 0x1234...5678
     */
    public String getShortAddress() {
        if (userAddress == null || userAddress.length() < 10) return userAddress;
        return userAddress.substring(0, 6) + "..." + userAddress.substring(userAddress.length() - 4);
    }
}