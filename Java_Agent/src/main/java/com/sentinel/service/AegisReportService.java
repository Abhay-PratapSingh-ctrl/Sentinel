package com.sentinel.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.config.SentinelConfig;
import com.sentinel.model.VaultPosition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.TimeUnit;

/**
 * AegisReportService
 *
 * ─────────────────────────────────────────────────────────────
 * Powers the /why command in the Telegram bot.
 *
 * When a user asks "Sentinel, why is my risk level 'Danger'?",
 * this service assembles a structured prompt from their live
 * position data (Pyth price, collateral, debt, HF, volatility,
 * Aegis status) and either:
 *
 *   1. Sends it to OpenAI GPT (if sentinel.open-ai-api-key is set),
 *      returning a natural-language, GPT-generated explanation.
 *
 *   2. Falls back to a rich template-based explanation if no API
 *      key is configured — works 100% offline with no extra deps.
 *
 * Uses OkHttp (already in the POM) for the OpenAI REST call.
 * No new Maven dependencies needed.
 * ─────────────────────────────────────────────────────────────
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AegisReportService {

    private static final String OPENAI_API_URL = "https://api.openai.com/v1/chat/completions";
    private static final String GROQ_API_URL   = "https://api.groq.com/openai/v1/chat/completions";
    private static final MediaType JSON_MEDIA   = MediaType.get("application/json; charset=utf-8");

    private final SentinelConfig    config;
    private final VolatilityService volatilityService;
    private final ObjectMapper      objectMapper = new ObjectMapper();

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .build();

    // ─────────────────────────────────────────────
    //  PUBLIC API
    // ─────────────────────────────────────────────

    /**
     * Generates a natural-language risk explanation for the given vault position.
     *
     * @param position  The user's current vault position (already enriched with dotPriceUSD).
     * @return          A Telegram-safe Markdown string explaining the risk level.
     */
    public String generateReport(VaultPosition position) {
        BigDecimal dotPrice = position.getDotPriceUSD() != null
                ? position.getDotPriceUSD()
                : BigDecimal.ZERO;

        boolean aegisActive = volatilityService.isRedFlagCondition(config.getAegisVolatilityThreshold());
        BigDecimal vol      = volatilityService.calculateShortTermVolatility();
        double changePct    = volatilityService.getLatestPriceChangePct();

        // ── Step 1: Try OpenAI ──────────────────────────────────────
        String openAiKey = config.getOpenAiApiKey();
        if (openAiKey != null && !openAiKey.isBlank()) {
            try {
                String prompt = buildPrompt(position, dotPrice, vol, aegisActive);
                String llmResponse = callLlmApi(OPENAI_API_URL, openAiKey, config.getOpenAiModel(), prompt);
                if (llmResponse != null && !llmResponse.isBlank()) {
                    log.info("OpenAI risk report generated for {}", position.getShortAddress());
                    return formatLlmReport(position, llmResponse, dotPrice, vol, aegisActive, "OpenAI");
                }
            } catch (Exception e) {
                log.warn("OpenAI call failed: {}, trying Groq fallback if available.", e.getMessage());
            }
        }

        // ── Step 2: Try Groq (OpenAI-compatible) ───────────────────
        String groqKey = config.getGroqApiKey();
        if (groqKey != null && !groqKey.isBlank()) {
            try {
                String prompt = buildPrompt(position, dotPrice, vol, aegisActive);
                // Default to Llama 3 70B for Groq if no specific model provided
                String model = "llama3-70b-8192"; 
                String llmResponse = callLlmApi(GROQ_API_URL, groqKey, model, prompt);
                if (llmResponse != null && !llmResponse.isBlank()) {
                    log.info("Groq risk report generated for {}", position.getShortAddress());
                    return formatLlmReport(position, llmResponse, dotPrice, vol, aegisActive, "Groq");
                }
            } catch (Exception e) {
                log.warn("Groq call failed: {}, falling back to template.", e.getMessage());
            }
        }

        // Fallback: rich template-based report
        return buildTemplateReport(position, dotPrice, vol, changePct, aegisActive);
    }

    // ─────────────────────────────────────────────
    //  PROMPT BUILDER
    // ─────────────────────────────────────────────

    private String buildPrompt(VaultPosition position, BigDecimal dotPrice,
                                BigDecimal vol, boolean aegisActive) {
        String level = position.getRiskLevel() != null
                ? position.getRiskLevel().name()
                : "UNKNOWN";

        return String.format("""
                You are Sentinel, an AI DeFi guardian bot on Polkadot Hub.
                A user has asked: "Why is my risk level '%s'?"
                
                Use these EXACT facts in your explanation (3-4 sentences, friendly but precise):
                - DOT/USD price (from Pyth oracle): $%s
                - Collateral locked: %s (worth %s)
                - sUSD debt minted: %s
                - Health Factor: %s
                  (Safe = above %d%%, current threshold)
                - Short-term DOT price volatility (σ, 60-min window): $%s USD
                - Aegis elevated buffer: %s
                  %s
                
                End with ONE concrete action the user should take right now.
                Keep the tone calm but urgent if the risk level is DANGER or CRITICAL.
                Do NOT use markdown headers. Use bullet points sparingly.
                """,
                level,
                dotPrice.setScale(4, RoundingMode.HALF_UP).toPlainString(),
                position.getCollateralDOTFormatted(),
                position.getCollateralUSDFormatted(),
                position.getMintedSUSDFormatted(),
                position.getHealthFactorPercent(),
                aegisActive ? config.getAegisElevatedBuffer() : config.getHfSafeThreshold(),
                vol.setScale(4, RoundingMode.HALF_UP).toPlainString(),
                aegisActive ? "YES — market is highly volatile" : "NO — market is calm",
                aegisActive
                        ? String.format("(Safety target raised to %d%% due to high volatility)", config.getAegisElevatedBuffer())
                        : "(Standard 150% safety target applies)"
        );
    }

    // ─────────────────────────────────────────────
    //  OPENAI HTTP CALL
    // ─────────────────────────────────────────────

    private String callLlmApi(String apiUrl, String apiKey, String model, String prompt) throws Exception {
        String requestBody = objectMapper.writeValueAsString(new java.util.LinkedHashMap<>() {{
            put("model", model);
            put("messages", new Object[]{
                new java.util.LinkedHashMap<>() {{
                    put("role", "user");
                    put("content", prompt);
                }}
            });
            put("max_tokens", 400);
            put("temperature", 0.6);
        }});

        Request request = new Request.Builder()
                .url(apiUrl)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(requestBody, JSON_MEDIA))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String err = response.body() != null ? response.body().string() : "No body";
                log.warn("LLM Provider ({}) returned error {}: {}", apiUrl, response.code(), err);
                return null;
            }
            if (response.body() == null) return null;
            JsonNode root = objectMapper.readTree(response.body().string());
            return root.path("choices").get(0)
                       .path("message").path("content").asText();
        }
    }

    // ─────────────────────────────────────────────
    //  REPORT FORMATTERS
    // ─────────────────────────────────────────────

    /**
     * Wraps the raw LLM response in a Telegram Markdown header with stats footer.
     */
    private String formatLlmReport(VaultPosition position, String llmText,
                                   BigDecimal dotPrice, BigDecimal vol, boolean aegisActive, String provider) {
        return "🤖 *Sentinel Risk Analysis (" + provider + ")*\n\n"
                + "👤 Wallet: `" + position.getShortAddress() + "`\n"
                + "💊 Health Factor: *" + position.getHealthFactorPercent() + "*\n\n"
                + llmText.trim() + "\n\n"
                + "📡 _Live data: DOT/USD $" + dotPrice.setScale(4, RoundingMode.HALF_UP).toPlainString()
                + " · Volatility σ=$" + vol.setScale(4, RoundingMode.HALF_UP).toPlainString()
                + (aegisActive ? " · 🛡️ Aegis ACTIVE_" : "_");
    }

    /**
     * Template-based fallback — rich, data-driven, works without any API key.
     */
    private String buildTemplateReport(VaultPosition position, BigDecimal dotPrice,
                                       BigDecimal vol, double changePct, boolean aegisActive) {
        String riskLevel = position.getRiskLevel() != null
                ? position.getRiskLevel().name()
                : "UNKNOWN";
        String emoji = switch (riskLevel) {
            case "SAFE"        -> "✅";
            case "WARNING"     -> "⚠️";
            case "DANGER"      -> "🚨";
            case "CRITICAL"    -> "🔴";
            case "LIQUIDATABLE"-> "💀";
            default            -> "❓";
        };

        String directionIcon = changePct < 0 ? "📉" : "📈";
        String changeFmt     = String.format("%.2f%%", Math.abs(changePct));
        String changeDir     = changePct < 0 ? "down" : "up";

        String advice = switch (riskLevel) {
            case "WARNING"     -> "Add more DOT collateral or repay some sUSD to push your Health Factor above 150%.";
            case "DANGER"      -> "Your position has been paused by Sentinel. Deposit more DOT immediately to unpause and avoid liquidation.";
            case "CRITICAL"    -> "Sentinel is triggering an emergency rebalance. If it fails, deposit DOT or repay sUSD RIGHT NOW to avoid public liquidation.";
            case "LIQUIDATABLE"-> "⚠️ URGENT: Your position is now open to anyone to liquidate. Deposit DOT collateral or repay sUSD debt IMMEDIATELY.";
            default            -> "Keep monitoring your position. Your Health Factor looks healthy.";
        };

        StringBuilder sb = new StringBuilder();
        sb.append(emoji).append(" *Sentinel Risk Analysis*\n\n");
        sb.append("👤 Wallet: `").append(position.getShortAddress()).append("`\n");
        sb.append("💊 Health Factor: *").append(position.getHealthFactorPercent())
          .append("* — ").append(riskLevel).append("\n\n");

        sb.append("🔑 *Why is my risk level '").append(riskLevel).append("'?*\n\n");

        // Collateral & debt breakdown
        sb.append("• DOT price (Pyth oracle): *$")
          .append(dotPrice.setScale(4, RoundingMode.HALF_UP).toPlainString()).append("*\n");
        sb.append("• Your collateral: *").append(position.getCollateralDOTFormatted())
          .append("* = ").append(position.getCollateralUSDFormatted()).append("\n");
        sb.append("• Your sUSD debt: *").append(position.getMintedSUSDFormatted()).append("*\n");
        sb.append("• Health Factor = Collateral ÷ Debt × 100\n\n");

        // Volatility context
        sb.append("📊 *Market Conditions:*\n");
        sb.append("• Short-term DOT volatility (σ): *$")
          .append(vol.setScale(4, RoundingMode.HALF_UP).toPlainString())
          .append("* over last 60 minutes\n");
        sb.append("• Latest price move: ").append(directionIcon).append(" ")
          .append(changeDir).append(" ").append(changeFmt).append("\n");

        // Aegis status
        if (aegisActive) {
            sb.append("\n🛡️ *Aegis Buffer: ACTIVE*\n");
            sb.append("High market volatility has been detected (σ exceeds $")
              .append(config.getAegisVolatilityThreshold()).append(").\n");
            sb.append("Sentinel's safety target has been raised from ")
              .append(config.getHfSafeThreshold()).append("% to *")
              .append(config.getAegisElevatedBuffer())
              .append("%* — rebalances will target this higher ratio to give your position more buffer.\n");
        } else {
            sb.append("\n🛡️ Aegis: NORMAL — standard ")
              .append(config.getHfSafeThreshold()).append("% safety target applies.\n");
        }

        sb.append("\n💡 *Recommended action:*\n").append(advice);

        return sb.toString();
    }
}
