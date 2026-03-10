package com.sentinel.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.LinkedList;
import java.util.List;
import java.util.OptionalDouble;

/**
 * VolatilityService
 * 
 * Tracks recent price history to calculate volatility and predict liquidation risk.
 */
@Slf4j
@Service
public class VolatilityService {

    private final List<BigDecimal> priceHistory = new LinkedList<>();
    private static final int MAX_HISTORY_SIZE = 60; // 60 minutes of data if polled every minute

    public synchronized void addPrice(BigDecimal price) {
        priceHistory.add(price);
        if (priceHistory.size() > MAX_HISTORY_SIZE) {
            priceHistory.remove(0);
        }
    }

    /**
     * Calculates the standard deviation of price changes (volatility) over the tracked period.
     */
    public BigDecimal calculateShortTermVolatility() {
        if (priceHistory.size() < 2) return BigDecimal.ZERO;

        BigDecimal mean = priceHistory.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(priceHistory.size()), 8, RoundingMode.HALF_UP);

        BigDecimal variance = priceHistory.stream()
                .map(price -> price.subtract(mean).pow(2))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(priceHistory.size()), 8, RoundingMode.HALF_UP);

        return variance.sqrt(new MathContext(8));
    }

    /**
     * Predicts the probability of liquidation within the next window based on current HF and volatility.
     * 
     * Formula (Simplified): 
     * Prob = (Distance to Liquidation / Volatility) -> mapped to a percentage.
     */
    public int predictLiquidationRisk(double currentHealthFactor, BigDecimal dotPrice) {
        if (priceHistory.size() < 5) return 0; // Need some data

        BigDecimal vol = calculateShortTermVolatility();
        if (vol.compareTo(BigDecimal.ZERO) == 0) return 0;

        // How much can the price drop before HF hits 1.2 (rebalance threshold)
        // HF = (Collateral * Price) / Debt
        // TargetPrice = (1.2 * Debt) / Collateral = (1.2 / HF) * CurrentPrice
        
        double dropNeeded = (1.0 - (1.2 / currentHealthFactor));
        if (dropNeeded <= 0) return 100; // Already at risk

        // Simple heuristic: if dropNeeded < 2 * VolatilityPct, risk is high.
        BigDecimal currentPrice = priceHistory.get(priceHistory.size() - 1);
        BigDecimal volPct = vol.divide(currentPrice, 8, RoundingMode.HALF_UP);
        
        double riskScore = volPct.doubleValue() / dropNeeded;
        
        int riskPct = (int) (riskScore * 100);
        return Math.min(riskPct, 99);
    }

    // ─────────────────────────────────────────────
    //  AEGIS BUFFER METHODS
    // ─────────────────────────────────────────────

    /**
     * Returns true when current short-term volatility exceeds the Aegis threshold.
     *
     * "Red flag" means the market is moving fast enough that the normal 150% safe
     * threshold is insufficient — Sentinel will raise it to 170% (or whatever
     * sentinel.aegis-elevated-buffer is configured to).
     *
     * @param thresholdUSD volatility threshold in USD (e.g. 0.30 = $0.30 std-dev)
     * @return true if the market is in a red-flag condition
     */
    public boolean isRedFlagCondition(double thresholdUSD) {
        if (priceHistory.size() < 5) return false; // need enough data
        BigDecimal vol = calculateShortTermVolatility();
        return vol.compareTo(BigDecimal.valueOf(thresholdUSD)) > 0;
    }

    /**
     * Returns the latest percent price change (last two samples).
     * Negative = price dropped, positive = price rose.
     */
    public double getLatestPriceChangePct() {
        if (priceHistory.size() < 2) return 0.0;
        BigDecimal latest = priceHistory.get(priceHistory.size() - 1);
        BigDecimal prev   = priceHistory.get(priceHistory.size() - 2);
        if (prev.compareTo(BigDecimal.ZERO) == 0) return 0.0;
        return latest.subtract(prev)
                     .divide(prev, 8, RoundingMode.HALF_UP)
                     .multiply(BigDecimal.valueOf(100))
                     .doubleValue();
    }

    /**
     * Builds a human-readable description of current Aegis buffer status.
     * Used in LLM risk reports and Telegram alerts.
     *
     * Example outputs:
     *   "⚠️ Aegis ACTIVE — DOT volatility σ=$0.42 exceeds $0.30 red-flag threshold"
     *   "✅ Aegis NORMAL — DOT volatility σ=$0.11 (threshold: $0.30)"
     */
    public String getAegisBufferDescription(double thresholdUSD) {
        BigDecimal vol = calculateShortTermVolatility();
        String volStr  = "$" + vol.setScale(4, RoundingMode.HALF_UP).toPlainString();
        String thrStr  = "$" + BigDecimal.valueOf(thresholdUSD).toPlainString();

        if (isRedFlagCondition(thresholdUSD)) {
            double changePct = getLatestPriceChangePct();
            String direction = changePct < 0 ? "▼" : "▲";
            return String.format(
                "⚠️ Aegis ACTIVE — DOT volatility σ=%s exceeds %s red-flag threshold (latest: %s%.2f%%)",
                volStr, thrStr, direction, Math.abs(changePct));
        } else {
            return String.format(
                "✅ Aegis NORMAL — DOT volatility σ=%s (threshold: %s)",
                volStr, thrStr);
        }
    }
}
