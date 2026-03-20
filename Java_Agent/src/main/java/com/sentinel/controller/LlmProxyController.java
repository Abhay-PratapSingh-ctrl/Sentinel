package com.sentinel.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.config.SentinelConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.RequestBody;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class LlmProxyController {

    private final SentinelConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    private static final String GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";
    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");

    @SuppressWarnings("unchecked")
    @PostMapping("/ask")
    public ResponseEntity<Map<String, Object>> askSentinel(@RequestBody Map<String, Object> requestBody) {
        log.info("LLM Proxy Request received: {}", requestBody.get("messages"));
        String apiKey = config.getGroqApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return ResponseEntity.status(500).body(Map.of("content", List.of(Map.of("text", "LLM Error: Groq API key not configured on backend"))));
        }

        try {
            // Map common format to Groq (OpenAI-compatible) format
            Map<String, Object> groqPayload = new HashMap<>();
            groqPayload.put("model", "llama-3.1-8b-instant");

            List<Map<String, String>> messages = new ArrayList<>();

            // Handle system prompt if present
            String systemPrompt = (String) requestBody.get("system");
            if (systemPrompt != null) {
                messages.add(Map.of("role", "system", "content", systemPrompt));
            }

            // Handle message history
            List<Map<String, String>> history = (List<Map<String, String>>) requestBody.get("messages");
            if (history != null) {
                messages.addAll(history);
            }
            groqPayload.put("messages", messages);

            if (requestBody.containsKey("max_tokens")) {
                groqPayload.put("max_tokens", requestBody.get("max_tokens"));
            }

            String jsonPayload = objectMapper.writeValueAsString(groqPayload);

            Request request = new Request.Builder()
                    .url(GROQ_URL)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .post(okhttp3.RequestBody.create(jsonPayload, JSON_MEDIA))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : "{}";
                if (!response.isSuccessful()) {
                    log.error("Groq API error: {} - {}", response.code(), body);
                    String errorText = "LLM Error: Groq API error: " + response.code();
                    return ResponseEntity.status(response.code()).body(Map.of("content", List.of(Map.of("text", errorText))));
                }

                // Groq response structure is OpenAI-compatible: choices[0].message.content
                Map<String, Object> groqResp = objectMapper.readValue(body, Map.class);
                List<Map<String, Object>> choices = (List<Map<String, Object>>) groqResp.get("choices");
                if (choices != null && !choices.isEmpty()) {
                    Map<String, Object> choice = choices.get(0);
                    Map<String, Object> message = (Map<String, Object>) choice.get("message");
                    if (message != null) {
                        String text = (String) message.get("content");
                        // Wrap in Anthropic-like format for frontend compatibility
                        return ResponseEntity.ok(Map.of("content", List.of(Map.of("text", text))));
                    }
                }

                return ResponseEntity.ok(groqResp);
            }
        } catch (IOException e) {
            log.error("LLM Proxy error", e);
            String errorText = "LLM Error: " + e.getMessage();
            return ResponseEntity.status(500).body(Map.of("content", List.of(Map.of("text", errorText))));
        }
    }
}
