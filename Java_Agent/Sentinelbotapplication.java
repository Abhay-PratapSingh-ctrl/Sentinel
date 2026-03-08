package com.sentinel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import com.sentinel.bot.TelegramAlertService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;

/**
 * SentinelBotApplication
 *
 * Entry point for the Sentinel AI Agent.
 *
 * Run with:
 *   java -jar sentinel-bot.jar
 *
 * Or with env vars (recommended for deployment):
 *   SENTINEL_PRIVATE_KEY=0x... \
 *   TELEGRAM_BOT_TOKEN=123:abc... \
 *   java -jar sentinel-bot.jar
 *
 * What happens on startup:
 *   1. Spring Boot loads application.properties
 *   2. Web3j connects to Polkadot Hub EVM node
 *   3. Guardian wallet is loaded from private key
 *   4. Telegram bot registers with the API
 *   5. @Scheduled monitoring loop starts (first run after 60s)
 *   6. Bot is ready — users can /link their wallets
 */
@Slf4j
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties
public class SentinelBotApplication {

    public static void main(String[] args) {
        log.info("╔══════════════════════════════════════╗");
        log.info("║     SENTINEL — AGENTIC DEFI BOT      ║");
        log.info("║        Polkadot Hub Guardian         ║");
        log.info("╚══════════════════════════════════════╝");
        SpringApplication.run(SentinelBotApplication.class, args);
    }

    /**
     * Registers the Telegram bot with the Telegram API after startup.
     * This starts the long-polling loop that listens for /link commands.
     */
    @Bean
    public CommandLineRunner registerTelegramBot(TelegramAlertService sentinelBot) {
        return args -> {
            try {
                TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
                botsApi.registerBot(sentinelBot);
                log.info("✅ Sentinel Telegram bot registered successfully.");
                log.info("📱 Message your bot on Telegram to link your wallet.");
            } catch (TelegramApiException e) {
                log.error("❌ Failed to register Telegram bot: {}", e.getMessage());
                log.error("Check your TELEGRAM_BOT_TOKEN in application.properties");
            }
        };
    }
}