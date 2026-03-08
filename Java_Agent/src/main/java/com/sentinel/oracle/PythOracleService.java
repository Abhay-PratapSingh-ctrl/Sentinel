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
 * Fetches the real-time DOT/USD price from Pyth Hermes REST API.
 * Caches the last successful price for resilience during brief outages.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PythOracleService {

    private final SentinelConfig config;
    private final ObjectMapper   objectMapper = new ObjectMapper();

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    private BigDecimal lastKnownPrice = BigDecimal.ZERO;
    private long       lastFetchTime  = 0;
    private static final long STALE_THRESHOLD_MS = 5 * 60 * 1000; // 5 minutes

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

            lastKnownPrice = price;
            lastFetchTime  = System.currentTimeMillis();

            log.debug("DOT/USD price fetched: ${}", price.toPlainString());
            return price;

        } catch (Exception e) {
            log.error("Failed to fetch DOT price from Pyth: {}", e.getMessage());
            return getCachedPriceOrThrow();
        }
    }

    private BigDecimal parsePythResponse(String json) throws Exception {
        JsonNode root      = objectMapper.readTree(json);
        JsonNode priceNode = root.path("parsed").get(0).path("price");

        String rawPrice = priceNode.path("price").asText();
        int    expo     = priceNode.path("expo").asInt();
        long   pubTime  = priceNode.path("publish_time").asLong();

        long ageSeconds = System.currentTimeMillis() / 1000 - pubTime;
        if (ageSeconds > 60) {
            log.warn("Pyth price is {}s old — feed may be stale!", ageSeconds);
        }

        return new BigDecimal(rawPrice)
                .scaleByPowerOfTen(expo)
                .setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal getCachedPriceOrThrow() {
        long age = System.currentTimeMillis() - lastFetchTime;
        if (lastKnownPrice.compareTo(BigDecimal.ZERO) > 0 && age < STALE_THRESHOLD_MS) {
            log.warn("Using cached DOT price ({}s old): ${}", age / 1000, lastKnownPrice.toPlainString());
            return lastKnownPrice;
        }
        throw new RuntimeException(
                "DOT price unavailable — Pyth API down and cache is stale (age: " + age / 1000 + "s)");
    }

    public boolean isPriceFresh() {
        return System.currentTimeMillis() - lastFetchTime < 60_000;
    }
}
