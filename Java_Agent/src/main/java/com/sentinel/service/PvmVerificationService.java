package com.sentinel.service;

import com.sentinel.config.SentinelConfig;
import com.sentinel.model.VaultPosition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * PvmVerificationService
 *
 * ─────────────────────────────────────────────────────────────
 * THE SENTINEL PVM BRAIN — Java-side pre-flight mirror.
 *
 * WHAT IT DOES:
 *   Mirrors the three-invariant logic of the Rust PVM `verify_rebalance`
 *   export, running it in Java BEFORE the bot submits a transaction.
 *
 *   This serves two purposes:
 *   1. Avoids wasting gas on a transaction that the on-chain PVM would reject.
 *   2. Provides clear, structured logs explaining WHY a rebalance was approved
 *      or rejected — essential for auditing and demo visibility.
 *
 * WHAT IT IS NOT:
 *   This is NOT the trustless verification layer. The Rust PVM precompile
 *   called on-chain by SentinelVault.emergencyRebalance() is the source of
 *   truth. This Java service is a convenience pre-flight check only.
 *
 * THREE INVARIANTS (matching Rust exactly):
 *   1. Is the position actually undercollateralised? (HF < rebalance floor)
 *   2. Will dotToSell achieve the target HF?
 *   3. Is dotToSell <= total collateral? (shouldn't oversell)
 * ─────────────────────────────────────────────────────────────
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PvmVerificationService {

    private final SentinelConfig    config;
    private final VolatilityService volatilityService;

    private static final BigDecimal PRECISION     = new BigDecimal("1000000000000000000"); // 1e18
    private static final BigDecimal PRICE_PREC    = new BigDecimal("100000000");           // 1e8

    // ─────────────────────────────────────────────
    //  MAIN ENTRY POINT
    // ─────────────────────────────────────────────

    /**
     * Pre-flight check: mirrors Rust `verify_rebalance`.
     *
     * Returns a RebalanceVerdict with:
     *   - approved:    whether all three invariants pass
     *   - dotToSell:   the calculated optimal sale amount (also the Rust `calculate_dot_to_sell` mirror)
     *   - explanation: human-readable reason for approval or rejection (used in Telegram logs)
     *
     * @param position       Current position snapshot from the contract
     * @param dotPrice       Current DOT/USD price (8 decimals, from Pyth)
     * @param targetHfPct    Target Health Factor % (e.g. 150, or 170 when Aegis active)
     */
    public RebalanceVerdict verify(VaultPosition position, BigDecimal dotPrice, int targetHfPct) {
        BigInteger collateralDOT = position.getCollateralDOT();
        BigInteger mintedSUSD    = position.getMintedSUSD();

        // ── Guard: no debt ────────────────────────────────────────
        if (mintedSUSD.compareTo(BigInteger.ZERO) == 0) {
            return RebalanceVerdict.rejected("Position has no debt — rebalance not needed.");
        }

        // ── Compute current HF ────────────────────────────────────
        BigDecimal currentHF = computeHF(collateralDOT, mintedSUSD, dotPrice);
        BigDecimal floorHF   = BigDecimal.valueOf(config.getHfRebalanceThreshold())
                .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);

        // ── Invariant 1: Is rebalance actually needed? ────────────
        if (currentHF.compareTo(floorHF) >= 0) {
            return RebalanceVerdict.rejected(String.format(
                "Invariant 1 FAILED: current HF %.2f%% >= floor %.0f%% — not undercollateralised.",
                currentHF.multiply(BigDecimal.valueOf(100)).doubleValue(),
                floorHF.multiply(BigDecimal.valueOf(100)).doubleValue()
            ));
        }

        // ── Calculate optimal dotToSell ───────────────────────────
        BigDecimal dotToSell = calculateDotToSell(collateralDOT, mintedSUSD, dotPrice, targetHfPct);

        if (dotToSell.compareTo(BigDecimal.ZERO) <= 0) {
            return RebalanceVerdict.rejected("Calculated dotToSell is zero — math error or already at target.");
        }

        // ── Invariant 2: Don't oversell ───────────────────────────
        BigDecimal collateralDotDecimal = new BigDecimal(collateralDOT);
        if (dotToSell.multiply(PRECISION).compareTo(collateralDotDecimal) > 0) {
            return RebalanceVerdict.rejected(String.format(
                "Invariant 2 FAILED: would need to sell %.4f DOT but only %.4f DOT available.",
                dotToSell.doubleValue(),
                collateralDotDecimal.divide(PRECISION, 4, RoundingMode.HALF_UP).doubleValue()
            ));
        }

        // ── Invariant 3: Simulate and verify outcome ───────────────
        BigDecimal susdReceived = dotToSell.multiply(dotPrice)
                .divide(PRICE_PREC, 18, RoundingMode.HALF_UP);

        BigDecimal mintedDecimal = new BigDecimal(mintedSUSD).divide(PRECISION, 18, RoundingMode.HALF_UP);
        BigDecimal debtRepaid    = susdReceived.min(mintedDecimal);
        BigDecimal newDebt       = mintedDecimal.subtract(debtRepaid);
        BigDecimal dotToSellWei  = dotToSell.multiply(PRECISION);
        BigDecimal newCollateral = collateralDotDecimal.subtract(dotToSellWei);

        BigDecimal resultingHF;
        if (newDebt.compareTo(BigDecimal.ZERO) == 0) {
            resultingHF = BigDecimal.valueOf(999); // fully repaid
        } else {
            resultingHF = computeHF(newCollateral.toBigInteger(), newDebt.multiply(PRECISION).toBigInteger(), dotPrice);
        }

        BigDecimal targetHF = BigDecimal.valueOf(targetHfPct)
                .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);

        if (resultingHF.compareTo(targetHF) < 0) {
            return RebalanceVerdict.rejected(String.format(
                "Invariant 3 FAILED: selling %.4f DOT only achieves HF %.1f%% but target is %.0f%%.",
                dotToSell.doubleValue(),
                resultingHF.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP).doubleValue(),
                targetHF.multiply(BigDecimal.valueOf(100)).doubleValue()
            ));
        }

        String reason = String.format(
            "✅ PVM pre-flight APPROVED: HF %.1f%% → %.1f%% after selling %.4f DOT (target: %d%%)",
            currentHF.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP).doubleValue(),
            resultingHF.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP).doubleValue(),
            dotToSell.setScale(4, RoundingMode.HALF_UP).doubleValue(),
            targetHfPct
        );
        log.info(reason);

        return RebalanceVerdict.approved(dotToSell, reason);
    }

    // ─────────────────────────────────────────────
    //  MATH HELPERS (mirror Rust functions exactly)
    // ─────────────────────────────────────────────

    /**
     * Mirrors Rust `calculate_health_factor`.
     * Returns HF as a decimal ratio (e.g. 1.50 = 150%).
     */
    public BigDecimal computeHF(BigInteger collateralDOT, BigInteger mintedSUSD, BigDecimal dotPrice) {
        if (mintedSUSD.compareTo(BigInteger.ZERO) == 0) {
            return BigDecimal.valueOf(999);
        }
        BigDecimal collateralUSD = new BigDecimal(collateralDOT)
                .multiply(dotPrice)
                .divide(PRICE_PREC.multiply(PRECISION), 18, RoundingMode.HALF_UP);

        BigDecimal debtDecimal = new BigDecimal(mintedSUSD)
                .divide(PRECISION, 18, RoundingMode.HALF_UP);

        return collateralUSD.divide(debtDecimal, 8, RoundingMode.HALF_UP);
    }

    /**
     * Mirrors Rust `calculate_dot_to_sell`.
     *
     * Algebraic solution for: how much DOT must be sold to reach the target HF?
     *   sold_usd = (target * debt - collateral) / (target - 1)
     *   dot_to_sell = sold_usd / dotPrice
     *
     * Returns DOT amount as a human-readable decimal (not wei).
     */
    public BigDecimal calculateDotToSell(BigInteger collateralDOT,
                                          BigInteger mintedSUSD,
                                          BigDecimal dotPrice,
                                          int targetHfPct) {
        if (mintedSUSD.compareTo(BigInteger.ZERO) == 0 || dotPrice.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal target = BigDecimal.valueOf(targetHfPct)
                .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);

        BigDecimal collateralUSD = new BigDecimal(collateralDOT)
                .multiply(dotPrice)
                .divide(PRICE_PREC.multiply(PRECISION), 8, RoundingMode.HALF_UP);

        BigDecimal debtDecimal = new BigDecimal(mintedSUSD)
                .divide(PRECISION, 8, RoundingMode.HALF_UP);

        BigDecimal targetTimesDebt = target.multiply(debtDecimal);

        if (targetTimesDebt.compareTo(collateralUSD) <= 0) {
            return BigDecimal.ZERO; // Already at or above target
        }

        // sold_usd = (target * debt - collateral_usd) / (target - 1)
        BigDecimal numerator   = targetTimesDebt.subtract(collateralUSD);
        BigDecimal denominator = target.subtract(BigDecimal.ONE);

        if (denominator.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal soldUSD    = numerator.divide(denominator, 8, RoundingMode.HALF_UP);
        BigDecimal dotToSell  = soldUSD.divide(dotPrice, 8, RoundingMode.HALF_UP);

        // Cap at available collateral (convert wei collateral to DOT)
        BigDecimal maxDOT = new BigDecimal(collateralDOT).divide(PRECISION, 8, RoundingMode.HALF_UP);
        return dotToSell.min(maxDOT);
    }

    // ─────────────────────────────────────────────
    //  VERDICT RECORD
    // ─────────────────────────────────────────────

    /**
     * Carries the result of the pre-flight verification.
     * MonitoringService reads this to decide whether to submit the tx.
     */
    public static class RebalanceVerdict {
        public final boolean    approved;
        public final BigDecimal dotToSell;    // null if rejected
        public final String     explanation;

        private RebalanceVerdict(boolean approved, BigDecimal dotToSell, String explanation) {
            this.approved    = approved;
            this.dotToSell   = dotToSell;
            this.explanation = explanation;
        }

        public static RebalanceVerdict approved(BigDecimal dotToSell, String reason) {
            return new RebalanceVerdict(true, dotToSell, reason);
        }

        public static RebalanceVerdict rejected(String reason) {
            return new RebalanceVerdict(false, null, reason);
        }
    }
}
