package com.sentinel.service;

import com.sentinel.bot.TelegramAlertService;
import com.sentinel.config.SentinelConfig;
import com.sentinel.model.VaultPosition;
import com.sentinel.oracle.PythOracleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MonitoringService
 *
 * ─────────────────────────────────────────────────────────────
 * WHAT IT SHOULD DO:
 *   Be the "brain" of the Sentinel bot. Every 60 seconds it
 *   scans ALL vault positions and decides what action to take
 *   based on each position's health factor.
 *
 * WHAT IT ACTUALLY DOES:
 *   Runs on a fixed schedule (configurable, default 60s).
 *   For each user:
 *     HF >= 150%      → SAFE, log only
 *     130–150%        → WARNING, send Telegram alert (once per cooldown)
 *     125–130%        → DANGER, pause position on-chain + Telegram alert
 *     120–125%        → CRITICAL, trigger emergency rebalance + alert
 *     < 120%          → LIQUIDATABLE, alert user + log (anyone can liquidate)
 *
 *   Alert cooldown: Won't re-alert the same wallet more than once per 10 minutes
 *   to avoid spamming the user.
 *
 *   Calculates dotToSell for rebalance using the formula:
 *     targetDebt = collateralUSD / TARGET_RATIO
 *     debtToRepay = mintedSUSD - targetDebt
 *     dotToSell (wei) = debtToRepay / dotPrice
 * ─────────────────────────────────────────────────────────────
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonitoringService {

    private final SentinelConfig      config;
    private final VaultContractService vaultService;
    private final TelegramAlertService telegramService;
    private final PythOracleService   oracleService;
    private final VolatilityService   volatilityService;
    private final LighthouseService   lighthouseService;

    // Track last alert time per wallet to avoid spamming (cooldown = 10 min)
    private final Map<String, Long> lastAlertTime = new ConcurrentHashMap<>();
    private static final long ALERT_COOLDOWN_MS = 10 * 60 * 1000; // 10 minutes

    // Track wallets that already had a rebalance triggered this cycle
    private final Set<String> rebalancedThisCycle = ConcurrentHashMap.newKeySet();

    // ─────────────────────────────────────────────
    //  MAIN MONITORING LOOP
    // ─────────────────────────────────────────────

    /**
     * THE CORE LOOP — runs every `sentinel.monitor-interval-ms` milliseconds.
     *
     * What happens each tick:
     *   1. Fetch live DOT price from Pyth.
     *   2. Get all user addresses from the vault contract.
     *   3. For each user: fetch their position, evaluate risk, take action.
     *   4. Log a summary.
     */
    @Scheduled(fixedDelayString = "${sentinel.monitor-interval-ms}")
    public void monitorAllPositions() {
        log.info("═══════════════════════════════════════════");
        log.info("Sentinel monitoring cycle started...");

        // Step 1: Get live DOT price
        BigDecimal dotPrice;
        try {
            dotPrice = oracleService.getDOTPriceUSD();
            volatilityService.addPrice(dotPrice);
            log.info("DOT/USD price: ${} (Volatility: {})", 
                    dotPrice.toPlainString(), 
                    volatilityService.calculateShortTermVolatility().toPlainString());
        } catch (Exception e) {
            log.error("Cannot fetch DOT price — skipping this monitoring cycle: {}", e.getMessage());
            return; // Don't act without price data — too risky
        }

        // Step 2: Get all vault users
        List<String> users;
        try {
            users = vaultService.getAllUsers();
            log.info("Monitoring {} active positions...", users.size());
        } catch (Exception e) {
            log.error("Failed to fetch users from vault: {}", e.getMessage());
            return;
        }

        if (users.isEmpty()) {
            log.info("No active positions found. Waiting for next cycle.");
            return;
        }

        // Step 3: Check each position
        rebalancedThisCycle.clear();
        int safe = 0, warning = 0, danger = 0, critical = 0, liquidatable = 0;

        for (String userAddress : users) {
            try {
                VaultPosition position = vaultService.getPosition(userAddress);
                position.setDotPriceUSD(dotPrice);

                switch (position.getRiskLevel()) {
                    case NO_DEBT, SAFE -> {
                        safe++;
                        log.debug("SAFE: {} HF={}", position.getShortAddress(),
                                position.getHealthFactorPercent());
                    }
                    case WARNING -> {
                        warning++;
                        handleWarning(position);
                    }
                    case DANGER -> {
                        danger++;
                        handleDanger(position);
                    }
                    case CRITICAL -> {
                        critical++;
                        handleCritical(position, dotPrice);
                    }
                    case LIQUIDATABLE -> {
                        liquidatable++;
                        handleLiquidatable(position);
                    }
                }

                // AI PREDICTION LAYER
                int riskPct = volatilityService.predictLiquidationRisk(
                        position.getHealthFactorPercent().doubleValue() / 100.0, 
                        dotPrice);
                
                if (riskPct > 50 && !isInCooldown(userAddress + "_predict")) {
                    log.info("🤖 SENTINEL PREDICT: User {} has a {}% chance of liquidation within 30m.", 
                            position.getShortAddress(), riskPct);
                    // Add logic to notify via Telegram in the future
                    updateAlertTime(userAddress + "_predict");
                }

            } catch (Exception e) {
                log.error("Error checking position for {}: {}", userAddress, e.getMessage());
            }
        }

        // Step 4: Summary log
        log.info("Cycle complete — Safe:{} Warn:{} Danger:{} Critical:{} Liquidatable:{}",
                safe, warning, danger, critical, liquidatable);
        log.info("═══════════════════════════════════════════");
    }

    // ─────────────────────────────────────────────
    //  RISK HANDLERS
    // ─────────────────────────────────────────────

    /**
     * HF 130–150% — Send Telegram warning once per cooldown period.
     * No on-chain action yet — just nudge the user.
     */
    private void handleWarning(VaultPosition position) {
        log.warn("⚠️  WARNING: {} HF={}", position.getShortAddress(),
                position.getHealthFactorPercent());

        if (isInCooldown(position.getUserAddress())) return;

        if (telegramService.isWalletLinked(position.getUserAddress())) {
            telegramService.sendWarningAlert(position);
            updateAlertTime(position.getUserAddress());
        }
    }

    /**
     * HF 125–130% — Pause the position on-chain to prevent further minting.
     * Then alert via Telegram.
     */
    private void handleDanger(VaultPosition position) {
        log.warn("🚨 DANGER: {} HF={} — pausing position",
                position.getShortAddress(), position.getHealthFactorPercent());

        // Don't pause if already paused
        if (position.isPaused()) {
            log.info("Position {} is already paused.", position.getShortAddress());
            return;
        }

        try {
            String reason = "Health Factor dropped to " + position.getHealthFactorPercent()
                    + " — below 125% danger threshold";

            String txHash = vaultService.pausePosition(position.getUserAddress(), reason);
            log.warn("Position paused! TX: {}", txHash);

            if (telegramService.isWalletLinked(position.getUserAddress())) {
                telegramService.sendPausedAlert(position, txHash);
            }
            updateAlertTime(position.getUserAddress());

            // ── Lighthouse Transparency Log ───────────────────────────
            lighthouseService.uploadTransparencyLog("POSITION_PAUSED", Map.of(
                "user", position.getUserAddress(),
                "healthFactor", position.getHealthFactorPercent(),
                "reason", reason,
                "txHash", txHash,
                "strategy", config.getStrategyMode()
            ));

        } catch (Exception e) {
            log.error("Failed to pause position {}: {}", position.getShortAddress(), e.getMessage());
        }
    }

    /**
     * HF 120–125% — Trigger emergency rebalance.
     *
     * The rebalance calculation:
     *   1. We want to restore HF to 150%.
     *   2. Target debt = collateralUSD / 1.50
     *   3. Debt to repay = currentDebt - targetDebt
     *   4. DOT to sell (USD) = debtToRepay
     *   5. DOT to sell (wei) = (debtToRepay / dotPrice) * 1e18
     *   6. minSUSDOut = dotToSellUSD * 0.98 (2% slippage buffer)
     */
    private void handleCritical(VaultPosition position, BigDecimal dotPrice) {
        log.error("🔴 CRITICAL: {} HF={} — triggering emergency rebalance",
                position.getShortAddress(), position.getHealthFactorPercent());

        // Don't rebalance the same wallet twice in one cycle
        if (rebalancedThisCycle.contains(position.getUserAddress())) return;

        try {
            // ── Calculate dotToSell ───────────────────────────────────
            BigDecimal collateralUSD = new BigDecimal(position.getCollateralUSD())
                    .divide(BigDecimal.TEN.pow(18), 8, RoundingMode.HALF_UP);

            BigDecimal mintedSUSD = new BigDecimal(position.getMintedSUSD())
                    .divide(BigDecimal.TEN.pow(18), 8, RoundingMode.HALF_UP);

            // Target debt that would give us 150% collateral ratio
            BigDecimal targetDebt = collateralUSD.divide(
                    BigDecimal.valueOf(config.getHfSafeThreshold())
                            .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP),
                    8, RoundingMode.HALF_UP
            );

            BigDecimal debtToRepayUSD = mintedSUSD.subtract(targetDebt);

            if (debtToRepayUSD.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("Rebalance calc shows no debt to repay for {} — skipping",
                        position.getShortAddress());
                return;
            }

            // Convert USD debt to repay → DOT amount to sell
            BigDecimal dotToSellDecimal = debtToRepayUSD.divide(dotPrice, 8, RoundingMode.HALF_UP);

            // Convert to wei (1e18)
            BigInteger dotToSellWei = dotToSellDecimal
                    .multiply(BigDecimal.TEN.pow(18))
                    .toBigInteger();

            // Cap at user's total collateral (safety check)
            if (dotToSellWei.compareTo(position.getCollateralDOT()) > 0) {
                dotToSellWei = position.getCollateralDOT();
            }

            // minSUSDOut = expected sUSD * 98% (2% slippage tolerance)
            BigInteger expectedSUSDOut = debtToRepayUSD
                    .multiply(BigDecimal.TEN.pow(18))
                    .multiply(BigDecimal.valueOf(0.98))
                    .toBigInteger();

            String dotToSellFormatted = dotToSellDecimal.setScale(4, RoundingMode.HALF_UP)
                    .toPlainString();

            // ── Notify user that rebalance is starting ────────────────
            if (telegramService.isWalletLinked(position.getUserAddress())) {
                telegramService.sendRebalanceStartedAlert(position, dotToSellFormatted);
            }

            // ── Fire the on-chain transaction ─────────────────────────
            String txHash = vaultService.emergencyRebalance(
                    position.getUserAddress(),
                    dotToSellWei,
                    expectedSUSDOut
            );

            rebalancedThisCycle.add(position.getUserAddress());

            // ── Fetch updated position for the success alert ──────────
            Thread.sleep(3000); // Wait 3s for tx to be mined
            VaultPosition afterPosition = vaultService.getPosition(position.getUserAddress());
            afterPosition.setDotPriceUSD(dotPrice);

            if (telegramService.isWalletLinked(position.getUserAddress())) {
                telegramService.sendRebalanceSuccessAlert(position, afterPosition, txHash);
            }

            updateAlertTime(position.getUserAddress());
            log.info("Emergency rebalance complete for {}. New HF: {}",
                    position.getShortAddress(), afterPosition.getHealthFactorPercent());

            // ── Lighthouse Transparency Log ───────────────────────────
            lighthouseService.uploadTransparencyLog("EMERGENCY_REBALANCE", Map.of(
                "user", position.getUserAddress(),
                "hfBefore", position.getHealthFactorPercent(),
                "hfAfter", afterPosition.getHealthFactorPercent(),
                "dotSold", dotToSellFormatted,
                "txHash", txHash,
                "strategy", config.getStrategyMode()
            ));

        } catch (Exception e) {
            log.error("Emergency rebalance FAILED for {}: {}",
                    position.getShortAddress(), e.getMessage());

            if (e.getMessage().contains("User approval required")) {
                if (telegramService.isWalletLinked(position.getUserAddress())) {
                    telegramService.sendRebalanceFailedAlert(position, 
                        "High-value rebalance needs your signature! Please approve on the Sentinel Dashboard.");
                }
            } else if (telegramService.isWalletLinked(position.getUserAddress())) {
                telegramService.sendRebalanceFailedAlert(position, e.getMessage());
            }
        }
    }

    /**
     * HF < 120% — Position is open to public liquidation.
     * Bot can't auto-liquidate (that needs sUSD in the guardian wallet).
     * Instead: alert user urgently and log for manual intervention.
     */
    private void handleLiquidatable(VaultPosition position) {
        log.error("💀 LIQUIDATABLE: {} HF={} — OPEN TO PUBLIC LIQUIDATION",
                position.getShortAddress(), position.getHealthFactorPercent());

        if (isInCooldown(position.getUserAddress())) return;

        if (telegramService.isWalletLinked(position.getUserAddress())) {
            // Reuse the rebalance failed alert with a clear message
            telegramService.sendRebalanceFailedAlert(position,
                    "Position HF below 120% — now open to public liquidation!");
        }
        updateAlertTime(position.getUserAddress());
    }

    // ─────────────────────────────────────────────
    //  COOLDOWN HELPERS
    // ─────────────────────────────────────────────

    private boolean isInCooldown(String walletAddress) {
        Long lastAlert = lastAlertTime.get(walletAddress.toLowerCase());
        if (lastAlert == null) return false;
        return (System.currentTimeMillis() - lastAlert) < ALERT_COOLDOWN_MS;
    }

    private void updateAlertTime(String walletAddress) {
        lastAlertTime.put(walletAddress.toLowerCase(), System.currentTimeMillis());
    }
}