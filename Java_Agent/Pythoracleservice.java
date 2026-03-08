package com.sentinel.oracle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.config.SentinelConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.TimeUnit;

/**
 * PythOracleService
 *
 * ─────────────────────────────────────────────────────────────
 * WHAT IT SHOULD DO:
 *   Fetch the real-time price of DOT/USD so the bot can verify
 *   the on-chain oracle is accurate AND independently calculate
 *   health factors without trusting the contract alone.
 *
 * WHAT IT ACTUALLY DOES:
 *   1. Calls the Pyth Hermes REST API with the DOT/USD price feed ID.
 *   2. Parses the JSON response to extract price + exponent.
 *   3. Applies the exponent: price = rawPrice * 10^expo
 *      (Pyth returns price as integer + negative exponent)
 *   4. Returns a BigDecimal representing USD price with 8 decimal places.
 *   5. Caches the last successful price so the bot can still function
 *      during brief API outages (uses stale price with a warning).
 * ─────────────────────────────────────────────────────────────
 *
 * Example Pyth response for DOT/USD:
 * {
 *   "parsed": [{
 *     "price": {
 *       "price": "742500000",    ← raw price integer
 *       "conf":  "250000",       ← confidence interval
 *       "expo":  -8,             ← multiply by 10^-8 → $7.425
 *       "publish_time": 1234567
 *     }
 *   }]
 * }
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PythOracleService {

    private final SentinelConfig config;
    private final ObjectMapper   objectMapper = new ObjectMapper();

    // HTTP client with timeouts — we don't want the bot hanging on slow API calls
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    // Cache last known good price (used during API outages)
    private BigDecimal lastKnownPrice = BigDecimal.ZERO;
    private long       lastFetchTime  = 0;
    private static final long STALE_THRESHOLD_MS = 5 * 60 * 1000; // 5 minutes

    /**
     * Fetches the current DOT/USD price from Pyth Hermes.
     *
     * @return DOT price in USD as BigDecimal (e.g., 7.42500000)
     * @throws RuntimeException if API is unreachable AND cache is stale
     */
    public BigDecimal getDOTPriceUSD() {
        String url = config.getPythApiUrl()
                + "?ids[]=" + config.getDotPriceFeedId();

        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {

            if (!response.isSuccessful() || response.body() == null) {
                log.warn("Pyth API returned non-200: {}. Using cached price.", response.code());
                return getCachedPriceOrThrow();
            }

            String body = response.body().string();
            BigDecimal price = parsePythResponse(body);

            // Update cache on success
            lastKnownPrice = price;
            lastFetchTime  = System.currentTimeMillis();

            log.debug("DOT/USD price fetched: ${}", price.toPlainString());
            return price;

        } catch (Exception e) {
            log.error("Failed to fetch DOT price from Pyth: {}", e.getMessage());
            return getCachedPriceOrThrow();
        }
    }

    /**
     * Parses the Pyth Hermes JSON response.
     *
     * The raw price is an integer and must be scaled by 10^expo.
     * Pyth always uses a negative exponent, so:
     *   price = rawPrice * 10^expo
     *   e.g.:  742500000 * 10^-8 = 7.425 USD
     */
    private BigDecimal parsePythResponse(String json) throws Exception {
        JsonNode root     = objectMapper.readTree(json);
        JsonNode priceNode = root.path("parsed").get(0).path("price");

        String rawPrice = priceNode.path("price").asText();
        int    expo     = priceNode.path("expo").asInt();
        long   pubTime  = priceNode.path("publish_time").asLong();

        // Warn if Pyth price is older than 60 seconds (stale feed)
        long ageSeconds = System.currentTimeMillis() / 1000 - pubTime;
        if (ageSeconds > 60) {
            log.warn("Pyth price is {}s old — feed may be stale!", ageSeconds);
        }

        // Apply exponent: expo is negative (e.g. -8), so divide by 10^|expo|
        BigDecimal price = new BigDecimal(rawPrice)
                .scaleByPowerOfTen(expo)          // multiply by 10^expo (expo is negative)
                .setScale(8, RoundingMode.HALF_UP);

        return price;
    }

    /**
     * Returns cached price if fresh enough, otherwise throws.
     * Allows the bot to keep running through brief API blips.
     */
    private BigDecimal getCachedPriceOrThrow() {
        long age = System.currentTimeMillis() - lastFetchTime;
        if (lastKnownPrice.compareTo(BigDecimal.ZERO) > 0 && age < STALE_THRESHOLD_MS) {
            log.warn("Using cached DOT price ({}s old): ${}",
                    age / 1000, lastKnownPrice.toPlainString());
            return lastKnownPrice;
        }
        throw new RuntimeException(
                "DOT price unavailable — Pyth API down and cache is stale (age: " + age / 1000 + "s)");
    }

    /**
     * Returns true if the price feed is fresh (updated within last 60s).
     * Used by the monitoring loop to decide if it's safe to act.
     */
    public boolean isPriceFresh() {
        return System.currentTimeMillis() - lastFetchTime < 60_000;
    }
}