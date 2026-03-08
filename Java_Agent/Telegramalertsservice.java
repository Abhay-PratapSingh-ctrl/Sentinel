package com.sentinel.bot;

import com.sentinel.config.SentinelConfig;
import com.sentinel.model.VaultPosition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

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
 *   5. Sends 4 types of messages:
 *      - ⚠️  WARNING   — HF below 130%, watch your position
 *      - 🚨  DANGER    — HF below 125%, position is being paused
 *      - 🔴  CRITICAL  — HF below 120%, emergency rebalance triggered
 *      - ✅  RESOLVED  — rebalance succeeded, position is safe again
 * ─────────────────────────────────────────────────────────────
 *
 * To use:
 *   1. Message @BotFather on Telegram → /newbot → copy token
 *   2. Set sentinel.telegram-bot-token in application.properties
 *   3. Start the bot, then message it: /link 0xYOUR_WALLET
 *   4. The bot will now DM you when your vault is at risk
 */
@Slf4j
@Service
public class TelegramAlertService extends TelegramLongPollingBot {

    private final SentinelConfig config;

    /**
     * Maps wallet address (lowercase) → Telegram chat ID.
     * When a user sends /link 0xABC, we store "0xabc" → chatId.
     * This is how the bot knows which Telegram user to alert.
     *
     * In production: persist this to a database (H2/PostgreSQL).
     * For the hackathon: in-memory is fine.
     */
    private final Map<String, Long> walletToChatId = new ConcurrentHashMap<>();

    public TelegramAlertService(SentinelConfig config) {
        super(config.getTelegramBotToken());
        this.config = config;
    }

    @Override
    public String getBotUsername() {
        return config.getTelegramBotUsername();
    }

    // ─────────────────────────────────────────────
    //  INCOMING MESSAGE HANDLER
    //  Listens for /link and /status commands from users
    // ─────────────────────────────────────────────

    /**
     * Called every time a user sends a message to the bot.
     *
     * Commands:
     *   /link 0xWALLET   — links wallet to this Telegram chat
     *   /unlink          — removes the wallet link
     *   /status          — shows current position info
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
                + "You'll receive alerts if your position becomes at risk.");

        log.info("Wallet {} linked to chat ID {}", wallet, chatId);
    }

    private void handleUnlinkCommand(long chatId) {
        walletToChatId.entrySet().removeIf(e -> e.getValue().equals(chatId));
        sendMessage(chatId, "🔓 Your wallet has been unlinked. Sentinel will no longer alert you.");
    }

    // ─────────────────────────────────────────────
    //  ALERT METHODS — called by MonitoringService
    // ─────────────────────────────────────────────

    /**
     * Sends a ⚠️ WARNING alert.
     * Triggered when HF drops below 130% but above 125%.
     * Advises user to add collateral or repay debt.
     */
    public void sendWarningAlert(VaultPosition position) {
        Long chatId = walletToChatId.get(position.getUserAddress().toLowerCase());
        if (chatId == null) {
            log.warn("No Telegram chat linked for wallet: {}", position.getUserAddress());
            return;
        }

        String message = "⚠️ *SENTINEL WARNING*\n\n"
                + "Your vault position is approaching the danger zone.\n\n"
                + "👤 Wallet: `" + position.getShortAddress() + "`\n"
                + "💊 Health Factor: *" + position.getHealthFactorPercent() + "*\n"
                + "💎 Collateral: " + position.getCollateralDOTFormatted()
                + " (" + position.getCollateralUSDFormatted() + ")\n"
                + "💸 Debt: " + position.getMintedSUSDFormatted() + "\n\n"
                + "⚡ *Recommended actions:*\n"
                + "• Add more DOT collateral to your vault\n"
                + "• Repay some sUSD debt\n"
                + "• Health Factor below 125% will trigger automatic pause\n\n"
                + "_Sentinel is watching your position every 60 seconds._";

        sendMessage(chatId, message);
        log.info("WARNING alert sent to wallet: {}", position.getShortAddress());
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
                + "🔗 [View transaction on explorer](" + explorerLink + ")";

        sendMessage(chatId, message);
        log.warn("PAUSED alert sent to wallet: {}", position.getShortAddress());
    }

    /**
     * Sends a 🔴 CRITICAL alert when emergency rebalance is triggered.
     * This is the KEY demo moment — shows the bot acting autonomously on-chain.
     */
    public void sendRebalanceStartedAlert(VaultPosition position, String dotToSell) {
        Long chatId = walletToChatId.get(position.getUserAddress().toLowerCase());
        if (chatId == null) return;

        String message = "🔴 *SENTINEL: EMERGENCY REBALANCE TRIGGERED*\n\n"
                + "Sentinel has detected your position is near liquidation and is automatically rebalancing.\n\n"
                + "👤 Wallet: `" + position.getShortAddress() + "`\n"
                + "💊 Health Factor: *" + position.getHealthFactorPercent() + "* _(liquidation risk)_\n"
                + "⚙️ Action: Selling *" + dotToSell + " DOT* via Hydration DEX\n"
                + "🎯 Goal: Repay sUSD debt to restore Health Factor to 150%\n\n"
                + "⏳ Transaction is being submitted on-chain...";

        sendMessage(chatId, message);
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
                + "🔴 *Please deposit more DOT collateral NOW to avoid liquidation.*";

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
                + "/help — Show this message\n\n"
                + "🔔 *Alert levels:*\n"
                + "⚠️ Warning — Health Factor below 130%\n"
                + "🚨 Danger — Position paused at 125%\n"
                + "🔴 Critical — Auto-rebalance at 120%\n\n"
                + "💡 Health Factor = (Collateral Value / Debt) × 100\n"
                + "Keep it above 150% to stay safe.";
    }

    /**
     * Returns true if a wallet has a linked Telegram chat.
     * Used by monitoring loop to decide whether to attempt alerts.
     */
    public boolean isWalletLinked(String walletAddress) {
        return walletToChatId.containsKey(walletAddress.toLowerCase());
    }
}