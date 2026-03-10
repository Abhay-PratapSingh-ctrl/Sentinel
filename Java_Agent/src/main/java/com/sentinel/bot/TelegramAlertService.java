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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TelegramAlertService
 *
 * ─────────────────────────────────────────────────────────────
 * WHAT IT SHOULD DO:
 *   Send real-time alerts to users about their vault positions
 *   and confirm when the Sentinel bot takes automated actions.
 *
 * WHAT IT ACTUALLY DOES:
 *   1. Runs as a Telegram LongPolling bot (listens for messages).
 *   2. Users link their wallet by sending: /link 0xWALLET_ADDRESS
 *   3. Stores a wallet → chatId mapping in memory (ConcurrentHashMap).
 *   4. When the monitoring loop detects risk, this service sends
 *      a formatted Markdown alert to the user's Telegram chat.
 *   5. Sends 4 types of automated alerts:
 *      - ⚠️  WARNING   — HF below 130%, watch your position
 *      - 🚨  DANGER    — HF below 125%, position is being paused
 *      - 🔴  CRITICAL  — HF below 120%, emergency rebalance triggered
 *      - ✅  RESOLVED  — rebalance succeeded, position is safe again
 *   6. NEW: /why command — returns an AI-generated risk explanation
 *      powered by AegisReportService (GPT-4o-mini or template fallback).
 * ─────────────────────────────────────────────────────────────
 */
@Slf4j
@Service
public class TelegramAlertService extends TelegramLongPollingBot {

    private final SentinelConfig       config;
    private final AegisReportService   aegisReportService;
    private final VaultContractService vaultContractService;
    private final PythOracleService    oracleService;
    private final VolatilityService    volatilityService;

    /**
     * Maps wallet address (lowercase) → Telegram chat ID.
     * When a user sends /link 0xABC, we store "0xabc" → chatId.
     *
     * In production: persist this to a database (H2/PostgreSQL).
     * For the hackathon: in-memory is fine.
     */
    private final Map<String, Long> walletToChatId = new ConcurrentHashMap<>();

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
    //  Listens for /link, /unlink, /status, /why commands
    // ─────────────────────────────────────────────

    /**
     * Called every time a user sends a message to the bot.
     *
     * Commands:
     *   /link 0xWALLET   — links wallet to this Telegram chat
     *   /unlink          — removes the wallet link
     *   /why             — AI explanation of your current risk level (NEW!)
     *   /help            — shows available commands
     */
    @Override
    public void onUpdateReceived(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText()) return;

        long   chatId  = update.getMessage().getChatId();
        String text    = update.getMessage().getText().trim();
        String[] parts = text.split("\\s+");

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

    /**
     * /why command handler.
     *
     * Finds the wallet linked to this chat → fetches live position →
     * calls AegisReportService to produce a natural-language explanation →
     * replies with the result.
     *
     * If the user hasn't linked a wallet yet, prompts them to do so.
     */
    private void handleWhyCommand(long chatId) {
        // Find which wallet is linked to this chat
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

        // Tell the user we're working on it (LLM calls can take a second)
        sendMessage(chatId, "🔍 _Analysing your position and market conditions..._");

        try {
            // Fetch live position
            VaultPosition position = vaultContractService.getPosition(linkedWallet);

            // Enrich with live price
            BigDecimal dotPrice = oracleService.getDOTPriceUSD();
            position.setDotPriceUSD(dotPrice);

            // Generate report (LLM or template)
            String report = aegisReportService.generateReport(position);
            sendMessage(chatId, report);

            log.info("/why report generated for wallet: {}", linkedWallet);

        } catch (Exception e) {
            log.error("Failed to generate /why report for {}: {}", linkedWallet, e.getMessage());
            sendMessage(chatId,
                    "❌ *Could not fetch your position data.*\n\n"
                    + "Error: " + e.getMessage() + "\n\n"
                    + "Please try again in a few seconds.");
        }
    }

    // ─────────────────────────────────────────────
    //  ALERT METHODS — called by MonitoringService
    // ─────────────────────────────────────────────

    /**
     * Sends a ⚠️ WARNING alert.
     * Triggered when HF drops below 130% but above 125%.
     * Now includes Aegis buffer status if active.
     */
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

    /**
     * Overload for backward compatibility (called without aegisActive).
     */
    public void sendWarningAlert(VaultPosition position) {
        sendWarningAlert(position, false);
    }

    /**
     * Sends a 🚨 DANGER alert when position is paused.
     * Triggered when HF drops below 125%.
     * Includes the tx hash so user can verify on-chain.
     */
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
                + "🔒 *Your position is now paused.* You cannot mint more sUSD or withdraw collateral.\n\n"
                + "✅ *To resume:*\n"
                + "Deposit more DOT collateral to bring your Health Factor above 140%.\n\n"
                + "💡 Send /why to get a full AI risk analysis.\n"
                + "🔗 [View transaction on explorer](" + explorerLink + ")";

        sendMessage(chatId, message);
        log.warn("PAUSED alert sent to wallet: {}", position.getShortAddress());
    }

    /**
     * Sends a 🔴 CRITICAL alert when emergency rebalance is triggered.
     * Now shows the effective Aegis target HF so users understand
     * why more DOT is being sold than they might expect.
     */
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
                + "🎯 Goal: Repay sUSD debt to restore Health Factor to *" + targetHfPct + "%*"
                + aegisNote + "\n\n"
                + "⏳ Transaction is being submitted on-chain...";

        sendMessage(chatId, message);
    }

    /**
     * Overload for backward compatibility.
     */
    public void sendRebalanceStartedAlert(VaultPosition position, String dotToSell) {
        sendRebalanceStartedAlert(position, dotToSell, 150);
    }

    /**
     * Sends a ✅ SUCCESS alert after emergency rebalance completes.
     * Includes the tx hash for the demo video explorer screenshot.
     */
    public void sendRebalanceSuccessAlert(VaultPosition before,
                                          VaultPosition after,
                                          String txHash) {
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

    /**
     * Sends a failure alert if the rebalance transaction reverts.
     * Prompts user to take manual action immediately.
     */
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

    /**
     * Returns true if a wallet has a linked Telegram chat.
     * Used by monitoring loop to decide whether to attempt alerts.
     */
    public boolean isWalletLinked(String walletAddress) {
        return walletToChatId.containsKey(walletAddress.toLowerCase());
    }
}
