package com.sentinel.bot;

import com.sentinel.config.SentinelConfig;
import com.sentinel.model.VaultPosition;
import com.sentinel.oracle.PythOracleService;
import com.sentinel.service.AegisReportService;
import com.sentinel.service.VaultContractService;
import com.sentinel.service.VolatilityService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
public class TelegramAlertService extends TelegramLongPollingBot {

    private final SentinelConfig       config;
    private final AegisReportService   aegisReportService;
    private final VaultContractService vaultContractService;
    private final PythOracleService    oracleService;
    private final VolatilityService    volatilityService;

    // wallet address (lowercase) → Telegram chat ID
    private final Map<String, Long> walletToChatId = new ConcurrentHashMap<>();

    // Thread pool for /why analysis — handles multiple users simultaneously
    private final ExecutorService analysisExecutor = Executors.newFixedThreadPool(10);

    public TelegramAlertService(SentinelConfig config,
                                AegisReportService aegisReportService,
                                VaultContractService vaultContractService,
                                PythOracleService oracleService,
                                VolatilityService volatilityService) {
        super(config.getTelegramBotToken());
        this.config               = config;
        this.aegisReportService   = aegisReportService;
        this.vaultContractService = vaultContractService;
        this.oracleService        = oracleService;
        this.volatilityService    = volatilityService;
    }

    @Override
    public String getBotUsername() {
        return config.getTelegramBotUsername();
    }

    // ─────────────────────────────────────────────
    //  INCOMING MESSAGE HANDLER
    // ─────────────────────────────────────────────

    @Override
    public void onUpdateReceived(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText()) return;

        long     chatId = update.getMessage().getChatId();
        String   text   = update.getMessage().getText().trim();
        String[] parts  = text.split("\\s+");

        switch (parts[0].toLowerCase()) {
            case "/start":
            case "/help":
                sendMessage(chatId, buildHelpMessage());
                break;
            case "/link":
                handleLinkCommand(chatId, parts);
                break;
            case "/unlink":
                handleUnlinkCommand(chatId);
                break;
            case "/why":
                handleWhyCommand(chatId);
                break;
            default:
                sendMessage(chatId, "❓ Unknown command. Type /help to see available commands.");
        }
    }

    private void handleLinkCommand(long chatId, String[] parts) {
        if (parts.length < 2 || !parts[1].startsWith("0x")) {
            sendMessage(chatId,
                    "❌ Please provide a valid wallet address.\n\nUsage: `/link 0xYOUR_WALLET_ADDRESS`");
            return;
        }

        String wallet = parts[1].toLowerCase();
        walletToChatId.put(wallet, chatId);

        sendMessage(chatId,
                "✅ *Wallet linked successfully!*\n\n"
                + "🔗 Wallet: `" + wallet + "`\n\n"
                + "🛡️ Sentinel is now monitoring your vault.\n"
                + "You'll receive alerts if your position becomes at risk.\n\n"
                + "💡 Tip: Send /why at any time to get an AI risk analysis of your position.");

        log.info("Wallet {} linked to chat ID {}", wallet, chatId);
    }

    private void handleUnlinkCommand(long chatId) {
        walletToChatId.entrySet().removeIf(e -> e.getValue().equals(chatId));
        sendMessage(chatId, "🔓 Your wallet has been unlinked. Sentinel will no longer alert you.");
    }

    // ─────────────────────────────────────────────
    //  /why COMMAND — fixed version
    //  - runs in background thread (non-blocking)
    //  - handles empty positions gracefully
    //  - handles RPC / LLM timeouts with fallback
    //  - supports multiple concurrent users
    // ─────────────────────────────────────────────

    private void handleWhyCommand(long chatId) {
        // Find linked wallet
        String linkedWallet = walletToChatId.entrySet().stream()
                .filter(e -> e.getValue().equals(chatId))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);

        if (linkedWallet == null) {
            sendMessage(chatId,
                    "⚠️ *No wallet linked.*\n\n"
                    + "Please link your wallet first:\n"
                    + "`/link 0xYOUR_WALLET_ADDRESS`\n\n"
                    + "Then use /why to get your AI risk analysis.");
            return;
        }

        // Acknowledge immediately — don't make user wait silently
        sendMessage(chatId, "🔍 _Analysing your position and market conditions..._");

        // Run in background thread — bot stays responsive to other users
        final String wallet = linkedWallet;
        analysisExecutor.submit(() -> {
            try {
                // Step 1 — fetch vault position (can timeout on slow RPC)
                VaultPosition position;
                try {
                    position = vaultContractService.getPosition(wallet);
                } catch (Exception e) {
                    log.error("/why RPC timeout for {}: {}", wallet, e.getMessage());
                    sendMessage(chatId,
                            "❌ *Could not fetch vault position.*\n\n"
                            + "The Polkadot Hub RPC timed out.\n"
                            + "Please try again in 30 seconds.");
                    return;
                }

                // Step 2 — check if wallet has any position at all
                boolean hasPosition = position != null
                        && (position.getCollateralDOT() != null
                                && position.getCollateralDOT().compareTo(BigInteger.ZERO) > 0
                            || position.getMintedSUSD() != null
                                && position.getMintedSUSD().compareTo(BigInteger.ZERO) > 0);

                if (!hasPosition) {
                    sendMessage(chatId,
                            "📭 *No vault position found.*\n\n"
                            + "Wallet: `" + wallet + "`\n\n"
                            + "This wallet has not deposited any collateral yet.\n\n"
                            + "Visit the Sentinel dApp to:\n"
                            + "• Deposit DOT as collateral\n"
                            + "• Mint sUSD stablecoin\n"
                            + "• Activate the Auto-Guardian");
                    return;
                }

                // Step 3 — fetch live DOT price (fallback silently if unavailable)
                try {
                    BigDecimal dotPrice = oracleService.getDOTPriceUSD();
                    position.setDotPriceUSD(dotPrice);
                } catch (Exception e) {
                    log.warn("/why Pyth timeout for {} — continuing with cached price", wallet);
                    // Don't fail the whole report just because price refresh timed out
                }

                // Step 4 — generate LLM report (fallback to template if LLM fails)
                String report;
                try {
                    report = aegisReportService.generateReport(position);
                } catch (Exception e) {
                    log.warn("/why LLM failed for {} — using fallback template: {}", wallet, e.getMessage());
                    report = buildFallbackReport(position);
                }

                sendMessage(chatId, report);
                log.info("/why report sent for wallet: {}", wallet);

            } catch (Exception e) {
                log.error("/why unexpected error for {}: {}", wallet, e.getMessage());
                sendMessage(chatId,
                        "❌ *Analysis failed.*\n\n"
                        + "An unexpected error occurred. Please try again in a few seconds.");
            }
        });
    }

    /**
     * Fallback report when LLM / Groq is unavailable.
     * Shows live on-chain data without AI commentary.
     */
    private String buildFallbackReport(VaultPosition position) {
        String hf     = position.getHealthFactorPercent();
        String status = "⚠️ Unknown";

        try {
            double hfVal = Double.parseDouble(hf.replace("%", "").trim());
            if      (hfVal >= 150) status = "✅ SAFE";
            else if (hfVal >= 130) status = "⚠️ WARNING";
            else if (hfVal >= 120) status = "🚨 DANGER";
            else                   status = "🔴 CRITICAL — liquidation risk";
        } catch (NumberFormatException ignored) { /* keep Unknown */ }

        return "📊 *Sentinel Risk Report*\n\n"
                + "👤 Wallet: `" + position.getShortAddress() + "`\n"
                + "💊 Health Factor: *" + hf + "* — " + status + "\n"
                + "💎 Collateral: " + position.getCollateralUSDFormatted() + "\n"
                + "💸 Debt: " + position.getMintedSUSDFormatted() + "\n"
                + "💵 DOT Price: $" + (position.getDotPriceUSD() != null
                        ? position.getDotPriceUSD().toPlainString() : "N/A") + "\n\n"
                + "⚡ *Recommended actions:*\n"
                + "• Keep Health Factor above 150% (170% during high volatility)\n"
                + "• Add collateral or repay sUSD if HF is below 130%\n\n"
                + "_⚠️ AI analysis temporarily unavailable — showing live data._\n"
                + "_Try /why again in 30 seconds for a full AI report._";
    }

    // ─────────────────────────────────────────────
    //  ALERT METHODS — called by MonitoringService
    // ─────────────────────────────────────────────

    public void sendWarningAlert(VaultPosition position, boolean aegisActive) {
        Long chatId = walletToChatId.get(position.getUserAddress().toLowerCase());
        if (chatId == null) {
            log.warn("No Telegram chat linked for wallet: {}", position.getUserAddress());
            return;
        }

        String aegisNote = aegisActive
                ? "\n\n🛡️ *Aegis Buffer: ACTIVE*\n"
                  + "High market volatility detected — Sentinel has raised its safety target to "
                  + config.getAegisElevatedBuffer() + "%. "
                  + "Your position needs a higher Health Factor than usual to be considered safe."
                : "";

        String message = "⚠️ *SENTINEL WARNING*\n\n"
                + "Your vault position is approaching the danger zone.\n\n"
                + "👤 Wallet: `" + position.getShortAddress() + "`\n"
                + "💊 Health Factor: *" + position.getHealthFactorPercent() + "*\n"
                + "💎 Collateral: " + position.getCollateralDOTFormatted()
                + " (" + position.getCollateralUSDFormatted() + ")\n"
                + "💸 Debt: " + position.getMintedSUSDFormatted()
                + aegisNote + "\n\n"
                + "⚡ *Recommended actions:*\n"
                + "• Add more DOT collateral to your vault\n"
                + "• Repay some sUSD debt\n"
                + "• Health Factor below 125% will trigger automatic pause\n\n"
                + "💡 Ask /why for a detailed AI explanation of your risk level.\n"
                + "_Sentinel is watching your position every 60 seconds._";

        sendMessage(chatId, message);
        log.info("WARNING alert sent to wallet: {}", position.getShortAddress());
    }

    public void sendWarningAlert(VaultPosition position) {
        sendWarningAlert(position, false);
    }

    public void sendPausedAlert(VaultPosition position, String txHash) {
        Long chatId = walletToChatId.get(position.getUserAddress().toLowerCase());
        if (chatId == null) return;

        String explorerLink = "https://polkadot-hub-testnet.blockscout.com/tx/" + txHash;

        String message = "🚨 *SENTINEL ALERT: POSITION PAUSED*\n\n"
                + "Your vault has been temporarily frozen to protect your collateral.\n\n"
                + "👤 Wallet: `" + position.getShortAddress() + "`\n"
                + "💊 Health Factor: *" + position.getHealthFactorPercent() + "* _(critical)_\n"
                + "💎 Collateral: " + position.getCollateralUSDFormatted() + "\n"
                + "💸 Debt: " + position.getMintedSUSDFormatted() + "\n\n"
                + "🔒 *Your position is now paused.*\n\n"
                + "✅ *To resume:*\n"
                + "Deposit more DOT collateral to bring your Health Factor above 140%.\n\n"
                + "💡 Send /why to get a full AI risk analysis.\n"
                + "🔗 [View transaction on explorer](" + explorerLink + ")";

        sendMessage(chatId, message);
        log.warn("PAUSED alert sent to wallet: {}", position.getShortAddress());
    }

    public void sendRebalanceStartedAlert(VaultPosition position, String dotToSell, int targetHfPct) {
        Long chatId = walletToChatId.get(position.getUserAddress().toLowerCase());
        if (chatId == null) return;

        boolean aegisActive = targetHfPct > 150;
        String aegisNote = aegisActive
                ? "\n🛡️ *Aegis Buffer ACTIVE* — rebalancing to *" + targetHfPct
                  + "%* (vs normal 150%) due to elevated market volatility."
                : "";

        String message = "🔴 *SENTINEL: EMERGENCY REBALANCE TRIGGERED*\n\n"
                + "Sentinel has detected your position is near liquidation and is automatically rebalancing.\n\n"
                + "👤 Wallet: `" + position.getShortAddress() + "`\n"
                + "💊 Health Factor: *" + position.getHealthFactorPercent() + "* _(liquidation risk)_\n"
                + "⚙️ Action: Selling *" + dotToSell + " DOT* via Hydration DEX\n"
                + "🎯 Goal: Restore Health Factor to *" + targetHfPct + "%*"
                + aegisNote + "\n\n"
                + "⏳ Transaction is being submitted on-chain...";

        sendMessage(chatId, message);
    }

    public void sendRebalanceStartedAlert(VaultPosition position, String dotToSell) {
        sendRebalanceStartedAlert(position, dotToSell, 150);
    }

    public void sendRebalanceSuccessAlert(VaultPosition before, VaultPosition after, String txHash) {
        Long chatId = walletToChatId.get(before.getUserAddress().toLowerCase());
        if (chatId == null) return;

        String explorerLink = "https://polkadot-hub-testnet.blockscout.com/tx/" + txHash;

        String message = "✅ *SENTINEL: REBALANCE SUCCESSFUL*\n\n"
                + "Your position has been automatically saved from liquidation!\n\n"
                + "👤 Wallet: `" + before.getShortAddress() + "`\n\n"
                + "📊 *Before → After:*\n"
                + "• Health Factor: *" + before.getHealthFactorPercent()
                + "* → *" + after.getHealthFactorPercent() + "*\n"
                + "• Collateral: " + before.getCollateralDOTFormatted()
                + " → " + after.getCollateralDOTFormatted() + "\n"
                + "• Debt: " + before.getMintedSUSDFormatted()
                + " → " + after.getMintedSUSDFormatted() + "\n\n"
                + "🔗 [View on Polkadot Hub Explorer](" + explorerLink + ")\n\n"
                + "💡 Send /why anytime to understand your current risk level.\n"
                + "_Sentinel saved your position. No action needed._";

        sendMessage(chatId, message);
        log.info("REBALANCE SUCCESS alert sent to wallet: {}", before.getShortAddress());
    }

    public void sendRebalanceFailedAlert(VaultPosition position, String error) {
        Long chatId = walletToChatId.get(position.getUserAddress().toLowerCase());
        if (chatId == null) return;

        String message = "❌ *SENTINEL: AUTO-REBALANCE FAILED*\n\n"
                + "⚠️ Sentinel attempted to rebalance your position but the transaction failed.\n"
                + "⚠️ *MANUAL ACTION REQUIRED IMMEDIATELY.*\n\n"
                + "👤 Wallet: `" + position.getShortAddress() + "`\n"
                + "💊 Health Factor: *" + position.getHealthFactorPercent() + "* _(critical)_\n"
                + "❗ Error: " + error + "\n\n"
                + "🔴 *Please deposit more DOT collateral NOW to avoid liquidation.*\n"
                + "💡 Send /why for a full AI analysis of your situation.";

        sendMessage(chatId, message);
        log.error("REBALANCE FAILED for wallet: {} — {}", position.getShortAddress(), error);
    }

    // ─────────────────────────────────────────────
    //  HELPERS
    // ─────────────────────────────────────────────

    private void sendMessage(long chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId);
        message.setText(text);
        message.setParseMode("Markdown");
        message.disableWebPagePreview();
        try {
            execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send Telegram message to chatId {}: {}", chatId, e.getMessage());
        }
    }

    private String buildHelpMessage() {
        return "🛡️ *Sentinel DeFi Guardian*\n\n"
                + "I monitor your vault on Polkadot Hub and protect you from liquidation.\n\n"
                + "📋 *Commands:*\n"
                + "/link `0xWALLET` — Link your wallet address\n"
                + "/unlink — Stop monitoring your wallet\n"
                + "/why — 🤖 Ask AI: \"Why is my risk level this?\"\n"
                + "/help — Show this message\n\n"
                + "🔔 *Alert levels:*\n"
                + "⚠️ Warning — Health Factor below 130%\n"
                + "🚨 Danger — Position paused at 125%\n"
                + "🔴 Critical — Auto-rebalance at 120%\n\n"
                + "🛡️ *Aegis Dynamic Buffer:*\n"
                + "During high-volatility markets, Sentinel raises its safety target\n"
                + "from 150% to 170% — giving your position extra protection.\n\n"
                + "💡 Health Factor = (Collateral Value / Debt) × 100\n"
                + "Keep it above 150% (170% during volatile markets) to stay safe.";
    }

    public boolean isWalletLinked(String walletAddress) {
        return walletToChatId.containsKey(walletAddress.toLowerCase());
    }
}