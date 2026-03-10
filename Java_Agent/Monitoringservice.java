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
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MonitoringService — The brain of the Sentinel bot.
 *
 * Runs every ${sentinel.monitor-interval-ms} (default: 60s).
 * For each user evaluates their Health Factor and acts accordingly:
 *
 *   HF >= 150%      → SAFE, log only
 *   130–150%        → WARNING — Telegram alert
 *   125–130%        → DANGER  — pause position on-chain + alert
 *   120–125%        → CRITICAL — emergency rebalance + alert
 *   < 120%          → LIQUIDATABLE — urgent alert (open to public liquidation)
 *
 * 🛡️ AEGIS BUFFER:
 *   When VolatilityService detects "red flag" market conditions (DOT price
 *   standard deviation exceeds sentinel.aegis-volatility-threshold), the safe
 *   HF target is automatically raised from 150% to sentinel.aegis-elevated-buffer
 *   (default: 170%). This means rebalances will target a higher collateral ratio
 *   during volatile periods, giving positions more breathing room.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonitoringService {

    private final SentinelConfig       config;
    private final VaultContractService vaultService;
    private final TelegramAlertService telegramService;
    private final PythOracleService    oracleService;
    private final VolatilityService    volatilityService;

    private final Map<String, Long> lastAlertTime   = new ConcurrentHashMap<>();
    private final Set<String> rebalancedThisCycle   = ConcurrentHashMap.newKeySet();
    private static final long ALERT_COOLDOWN_MS     = 30 * 1000; // 30 seconds (demo mode)

    @Scheduled(fixedDelayString = "${sentinel.monitor-interval-ms}")
    public void monitorAllPositions() {
        log.info("═══════════════════════════════════════════");
        log.info("Sentinel monitoring cycle started...");

        // ── Step 1: Get live DOT price and feed volatility tracker ──────────────
        BigDecimal dotPrice;
        try {
            dotPrice = oracleService.getDOTPriceUSD();
            volatilityService.addPrice(dotPrice);
            log.info("DOT/USD price: ${} (Volatility σ={})",
                    dotPrice.toPlainString(),
                    volatilityService.calculateShortTermVolatility().toPlainString());
        } catch (Exception e) {
            log.error("Cannot fetch DOT price — skipping cycle: {}", e.getMessage());
            return;
        }

        // ── Step 2: Determine Aegis effective safe threshold ─────────────────────
        boolean aegisActive = volatilityService.isRedFlagCondition(config.getAegisVolatilityThreshold());
        int effectiveSafeThreshold = aegisActive
                ? config.getAegisElevatedBuffer()
                : config.getHfSafeThreshold();

        if (aegisActive) {
            log.warn("╔═══════════════════════════════════════════╗");
            log.warn("║  🛡️  AEGIS BUFFER ACTIVATED — RED FLAG    ║");
            log.warn("║  {}  ║",
                    volatilityService.getAegisBufferDescription(config.getAegisVolatilityThreshold()));
            log.warn("║  Safe HF target raised: {}% → {}%          ║",
                    config.getHfSafeThreshold(), effectiveSafeThreshold);
            log.warn("╚═══════════════════════════════════════════╝");
        } else {
            log.info("🛡️ Aegis: {}", volatilityService.getAegisBufferDescription(config.getAegisVolatilityThreshold()));
        }

        // ── Step 3: Fetch all vault users ────────────────────────────────────────
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

        // ── Step 4: Check each position ──────────────────────────────────────────
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
                    case WARNING     -> { warning++;     handleWarning(position, aegisActive); }
                    case DANGER      -> { danger++;      handleDanger(position, aegisActive); }
                    case CRITICAL    -> { critical++;    handleCritical(position, dotPrice, effectiveSafeThreshold); }
                    case LIQUIDATABLE -> { liquidatable++; handleLiquidatable(position); }
                }

                // ── AI Prediction Layer (enhanced with Aegis status) ──────────────
                int riskPct = volatilityService.predictLiquidationRisk(
                        position.getHealthFactorPercent().contains("%")
                                ? Double.parseDouble(position.getHealthFactorPercent().replace("%", "")) / 100.0
                                : 2.0,
                        dotPrice);

                if (riskPct > 50 && !isInCooldown(userAddress + "_predict")) {
                    log.info("🤖 SENTINEL PREDICT: User {} — {}% liquidation risk within 30m. Aegis: {}",
                            position.getShortAddress(), riskPct,
                            aegisActive ? "ELEVATED" : "NORMAL");
                    updateAlertTime(userAddress + "_predict");
                }

            } catch (Exception e) {
                log.error("Error checking position for {}: {}", userAddress, e.getMessage());
            }
        }

        log.info("Cycle complete — Safe:{} Warn:{} Danger:{} Critical:{} Liquidatable:{}",
                safe, warning, danger, critical, liquidatable);
        log.info("═══════════════════════════════════════════");
    }

    // ─────────────────────────────────────────────
    //  RISK HANDLERS
    // ─────────────────────────────────────────────

    private void handleWarning(VaultPosition position, boolean aegisActive) {
        log.warn("⚠️  WARNING: {} HF={} [Aegis: {}]",
                position.getShortAddress(), position.getHealthFactorPercent(),
                aegisActive ? "ELEVATED" : "NORMAL");
        if (isInCooldown(position.getUserAddress())) return;
        if (telegramService.isWalletLinked(position.getUserAddress())) {
            telegramService.sendWarningAlert(position, aegisActive);
            updateAlertTime(position.getUserAddress());
        }
    }

    private void handleDanger(VaultPosition position, boolean aegisActive) {
        log.warn("🚨 DANGER: {} HF={} — pausing position [Aegis: {}]",
                position.getShortAddress(), position.getHealthFactorPercent(),
                aegisActive ? "ELEVATED" : "NORMAL");

        if (position.isPaused()) {
            log.info("Position {} is already paused.", position.getShortAddress());
            return;
        }

        try {
            String aegisNote = aegisActive
                    ? " [Aegis buffer active — elevated market risk detected]"
                    : "";
            String reason = "Health Factor dropped to " + position.getHealthFactorPercent()
                    + " — below 125% danger threshold" + aegisNote;
            String txHash = vaultService.pausePosition(position.getUserAddress(), reason);
            log.warn("Position paused! TX: {}", txHash);
            if (telegramService.isWalletLinked(position.getUserAddress())) {
                telegramService.sendPausedAlert(position, txHash);
            }
            updateAlertTime(position.getUserAddress());
        } catch (Exception e) {
            log.error("Failed to pause position {}: {}", position.getShortAddress(), e.getMessage());
        }
    }

    /**
     * Triggers emergency rebalance.
     *
     * When Aegis is active, the rebalance target is raised to `effectiveSafeThreshold`
     * (e.g. 170%) instead of the normal 150%, so the position has more buffer after
     * the rebalance during a volatile market period.
     */
    private void handleCritical(VaultPosition position, BigDecimal dotPrice, int effectiveSafeThreshold) {
        log.error("🔴 CRITICAL: {} HF={} — triggering emergency rebalance (target HF: {}%)",
                position.getShortAddress(), position.getHealthFactorPercent(), effectiveSafeThreshold);

        if (rebalancedThisCycle.contains(position.getUserAddress())) return;

        try {
            BigDecimal collateralUSD = new BigDecimal(position.getCollateralUSD())
                    .divide(BigDecimal.TEN.pow(18), 8, RoundingMode.HALF_UP);
            BigDecimal mintedSUSD = new BigDecimal(position.getMintedSUSD())
                    .divide(BigDecimal.TEN.pow(18), 8, RoundingMode.HALF_UP);

            // Use the Aegis-adjusted target (170% if red-flag, 150% otherwise)
            BigDecimal targetDebt = collateralUSD.divide(
                    BigDecimal.valueOf(effectiveSafeThreshold)
                            .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP),
                    8, RoundingMode.HALF_UP);

            BigDecimal debtToRepayUSD = mintedSUSD.subtract(targetDebt);
            if (debtToRepayUSD.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("Rebalance calc shows no debt to repay for {} (target {}%) — skipping",
                        position.getShortAddress(), effectiveSafeThreshold);
                return;
            }

            BigDecimal dotToSellDecimal = debtToRepayUSD.divide(dotPrice, 8, RoundingMode.HALF_UP);
            BigInteger dotToSellWei = dotToSellDecimal
                    .multiply(BigDecimal.TEN.pow(18)).toBigInteger();

            if (dotToSellWei.compareTo(position.getCollateralDOT()) > 0) {
                dotToSellWei = position.getCollateralDOT();
            }

            BigInteger expectedSUSDOut = debtToRepayUSD
                    .multiply(BigDecimal.TEN.pow(18))
                    .multiply(BigDecimal.valueOf(0.98))
                    .toBigInteger();

            String dotToSellFormatted = dotToSellDecimal.setScale(4, RoundingMode.HALF_UP).toPlainString();

            if (telegramService.isWalletLinked(position.getUserAddress())) {
                telegramService.sendRebalanceStartedAlert(position, dotToSellFormatted, effectiveSafeThreshold);
            }

            String txHash = vaultService.emergencyRebalance(
                    position.getUserAddress(), dotToSellWei, expectedSUSDOut);
            rebalancedThisCycle.add(position.getUserAddress());

            Thread.sleep(3000); // wait for tx to mine
            VaultPosition afterPosition = vaultService.getPosition(position.getUserAddress());
            afterPosition.setDotPriceUSD(dotPrice);

            if (telegramService.isWalletLinked(position.getUserAddress())) {
                telegramService.sendRebalanceSuccessAlert(position, afterPosition, txHash);
            }

            updateAlertTime(position.getUserAddress());
            log.info("Emergency rebalance complete for {}. New HF: {} (target was {}%)",
                    position.getShortAddress(), afterPosition.getHealthFactorPercent(), effectiveSafeThreshold);

        } catch (Exception e) {
            log.error("Emergency rebalance FAILED for {}: {}",
                    position.getShortAddress(), e.getMessage());
            if (telegramService.isWalletLinked(position.getUserAddress())) {
                telegramService.sendRebalanceFailedAlert(position, e.getMessage());
            }
        }
    }

    private void handleLiquidatable(VaultPosition position) {
        log.error("💀 LIQUIDATABLE: {} HF={} — OPEN TO PUBLIC LIQUIDATION",
                position.getShortAddress(), position.getHealthFactorPercent());
        if (isInCooldown(position.getUserAddress())) return;
        if (telegramService.isWalletLinked(position.getUserAddress())) {
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
