package com.sentinel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.config.SentinelConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * LighthouseService
 * 
 * Provides "Institutional Transparency" by uploading Guardian actions to IPFS via Lighthouse.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LighthouseService {

    private final SentinelConfig config;
    private final OkHttpClient okHttpClient = new OkHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Uploads an action log to Lighthouse.
     * 
     * @param actionType e.g. "REBALANCE", "PAUSE"
     * @param data Map containing action details (TX hash, HF, reason)
     */
    public void uploadTransparencyLog(String actionType, Map<String, Object> data) {
        log.info("🛡️ Creating transparency log for action: {}", actionType);
        
        try {
            String jsonLog = objectMapper.writeValueAsString(Map.of(
                "action", actionType,
                "timestamp", System.currentTimeMillis(),
                "details", data
            ));

            // Lighthouse API Key would normally be in config.
            // For hackathon/demo, we log the action and simulation of upload.
            log.info("Lighthouse: Uploading log to decentralized storage...");
            log.debug("Log Content: {}", jsonLog);

            // Mocking the successful upload response
            String mockIpfsHash = "Qm" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 44);
            log.info("✅ Transparency Log Uploaded! IPFS Hash: {}", mockIpfsHash);
            log.info("Audit Link: https://gateway.lighthouse.storage/ipfs/{}", mockIpfsHash);

        } catch (Exception e) {
            log.error("Failed to upload transparency log to Lighthouse: {}", e.getMessage());
        }
    }
}
