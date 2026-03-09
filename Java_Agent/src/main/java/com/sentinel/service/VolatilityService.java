package com.sentinel.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.LinkedList;
import java.util.List;

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
}
