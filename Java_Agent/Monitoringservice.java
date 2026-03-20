package com.sentinel.service;

import com.sentinel.bot.TelegramAlertService;
import com.sentinel.config.SentinelConfig;
import com.sentinel.model.VaultPosition;
import com.sentinel.oracle.PythOracleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MonitoringService — The brain of the Sentinel bot.
 *
 * Runs every ${sentinel.monitor-interval-ms} (default: 30s).
 * For each user evaluates their Health Factor and acts accordingly:
 *
 *   HF >= 150%       → SAFE, log only
 *   130–150%         → WARNING   — Telegram alert
 *   125–130%         → DANGER    — pause position on-chain + alert
 *   120–125%         → CRITICAL  — batchRepay via SentinelRebalancer + alert
 *   < 120%           → LIQUIDATABLE — also triggers batchRepay (better than nothing)
 *
 * NOTE: vaultService.emergencyRebalance() is NOT used because the DEX router
 * is address(0) on this testnet deployment. Instead, batchRepay() on
 * SentinelRebalancer pulls pre-approved sUSD from users and burns it
 * directly — no DEX swap needed.
 */
@Slf4j
@Service
public class MonitoringService {

    private final SentinelConfig       config;
    private final VaultContractService vaultService;
    private final TelegramAlertService telegramService;
    private final PythOracleService    oracleService;
    private final VolatilityService    volatilityService;
    private final RebalancerService    rebalancerService;

    private final Map<String, Long> lastAlertTime       = new ConcurrentHashMap<>();
    private final Set<String>       rebalancedThisCycle = ConcurrentHashMap.newKeySet();
    private final Set<String>       activeRebalances    = ConcurrentHashMap.newKeySet();

    private static final long ALERT_COOLDOWN_MS = 30 * 1000;

    public MonitoringService(SentinelConfig config,
                             VaultContractService vaultService,
                             TelegramAlertService telegramService,
                             PythOracleService oracleService,
                             VolatilityService volatilityService,
                             RebalancerService rebalancerService) {
        this.config            = config;
        this.vaultService      = vaultService;
        this.telegramService   = telegramService;
        this.oracleService     = oracleService;
        this.volatilityService = volatilityService;
        this.rebalancerService = rebalancerService;
    }

    @Scheduled(fixedDelayString = "${sentinel.monitor-interval-ms}")
    public void monitorAllPositions() {
        log.info("═══════════════════════════════════════════");
        log.info("Sentinel monitoring cycle started...");

        // ── Step 1: Get live DOT price ───────────────────────────────────────────
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

        // ── Step 2: Aegis effective threshold ────────────────────────────────────
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
            log.info("🛡️ Aegis: {}",
                    volatilityService.getAegisBufferDescription(config.getAegisVolatilityThreshold()));
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
                    case WARNING      -> { warning++;      handleWarning(position, aegisActive); }
                    case DANGER       -> { danger++;       handleDanger(position, aegisActive); }
                    case CRITICAL     -> { critical++;     handleCritical(position, dotPrice, effectiveSafeThreshold); }
                    case LIQUIDATABLE -> { liquidatable++; handleLiquidatable(position, dotPrice, effectiveSafeThreshold); }
                }

                // AI Prediction Layer
                int riskPct = volatilityService.predictLiquidationRisk(
                        position.getHealthFactorPercent().contains("%")
                                ? Double.parseDouble(
                                        position.getHealthFactorPercent().replace("%", "")) / 100.0
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
                    ? " [Aegis buffer active — elevated market risk detected]" : "";
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
     * FIXED: Uses SentinelRebalancer.batchRepay() instead of vault.emergencyRebalance()
     * because the vault DEX router is address(0) on this testnet deployment.
     *
     * Flow:
     *   1. Check user is registered with SentinelRebalancer
     *   2. Send batchRepay() — contract burns minimum sUSD to restore HF
     *   3. Send Telegram alerts before and after
     */
    private void handleCritical(VaultPosition position, BigDecimal dotPrice, int effectiveSafeThreshold) {
        String userAddress = position.getUserAddress();

        log.error("🔴 CRITICAL: {} HF={} — triggering batchRepay via SentinelRebalancer (target: {}%)",
                position.getShortAddress(), position.getHealthFactorPercent(), effectiveSafeThreshold);

        // Prevent duplicate rebalance in same cycle
        if (rebalancedThisCycle.contains(userAddress)) {
            log.info("[Rebalancer] Already rebalanced {} this cycle — skipping",
                    position.getShortAddress());
            return;
        }

        // Prevent concurrent rebalance for same user
        if (activeRebalances.contains(userAddress.toLowerCase())) {
            log.info("[Rebalancer] Rebalance already in progress for {} — skipping",
                    position.getShortAddress());
            return;
        }

        // Check user is registered with rebalancer (has pre-approved sUSD)
        boolean isRegistered = rebalancerService.isUserRegistered(userAddress);
        if (!isRegistered) {
            log.warn("[Rebalancer] User {} NOT registered with SentinelRebalancer — cannot auto-repay",
                    position.getShortAddress());
            if (telegramService.isWalletLinked(userAddress)) {
                telegramService.sendRebalanceFailedAlert(position,
                        "Auto-Guardian not enabled. Please click 'Enable Guardian' "
                        + "on the Sentinel dApp to activate automatic protection.");
            }
            return;
        }

        activeRebalances.add(userAddress.toLowerCase());

        try {
            // Send "starting" alert
            if (telegramService.isWalletLinked(userAddress)) {
                telegramService.sendRebalanceStartedAlert(
                        position, "minimum sUSD", effectiveSafeThreshold);
            }

            // Trigger batchRepay — one tx covers all critical users
            String txHash = rebalancerService.triggerBatchRepay();
            rebalancedThisCycle.add(userAddress);

            log.info("[Rebalancer] ✅ batchRepay tx sent: {}", txHash);

            // Wait for tx to mine then fetch updated position
            Thread.sleep(15_000);

            VaultPosition afterPosition = vaultService.getPosition(userAddress);
            afterPosition.setDotPriceUSD(dotPrice);

            // Send success alert
            if (telegramService.isWalletLinked(userAddress)) {
                telegramService.sendRebalanceSuccessAlert(position, afterPosition, txHash);
            }

            updateAlertTime(userAddress);
            log.info("[Rebalancer] ✅ Complete for {}. HF: {} → {} (target {}%)",
                    position.getShortAddress(),
                    position.getHealthFactorPercent(),
                    afterPosition.getHealthFactorPercent(),
                    effectiveSafeThreshold);

        } catch (Exception e) {
            log.error("[Rebalancer] FAILED for {}: {}", position.getShortAddress(), e.getMessage());
            if (telegramService.isWalletLinked(userAddress)) {
                telegramService.sendRebalanceFailedAlert(position, e.getMessage());
            }
        } finally {
            // Release lock after 60s cooldown — prevents immediate re-trigger
            new Thread(() -> {
                try { Thread.sleep(60_000); } catch (InterruptedException ignored) {}
                activeRebalances.remove(userAddress.toLowerCase());
            }).start();
        }
    }

    /**
     * HF < 120% — still attempt batchRepay, it's better than doing nothing.
     * The SentinelRebalancer will repay as much as the user's sUSD balance allows.
     */
    private void handleLiquidatable(VaultPosition position, BigDecimal dotPrice, int effectiveSafeThreshold) {
        log.error("💀 LIQUIDATABLE: {} HF={} — attempting emergency repay via SentinelRebalancer",
                position.getShortAddress(), position.getHealthFactorPercent());

        // Route through handleCritical — same batchRepay logic applies
        handleCritical(position, dotPrice, effectiveSafeThreshold);
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