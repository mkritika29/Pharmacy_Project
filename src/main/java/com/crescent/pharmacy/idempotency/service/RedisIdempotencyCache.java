package com.crescent.pharmacy.idempotency.service;

import com.crescent.pharmacy.idempotency.dto.PaymentResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Thin wrapper around Redis that caches completed payment results.
 *
 * Cache key format: {@code idem:result:{merchantId}:{idempotencyKey}}
 *
 * Only COMPLETED or FAILED results are cached (not PENDING).
 * TTL mirrors the idempotency-key expiration time.
 *
 * All Redis failures are swallowed and logged so they never block a payment.
 * This implements the "Graceful Degradation" stretch goal: if Redis is down,
 * the service falls back to PostgreSQL lookups.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisIdempotencyCache {

    private static final String PREFIX = "idem:result:";

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    /** Returns the cached payment response, or empty if not found / Redis error. */
    public Optional<PaymentResponse> get(String merchantId, String idempotencyKey) {
        String key = cacheKey(merchantId, idempotencyKey);
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json != null) {
                log.debug("Redis cache HIT: key={}, merchant={}", idempotencyKey, merchantId);
                return Optional.of(objectMapper.readValue(json, PaymentResponse.class));
            }
        } catch (Exception ex) {
            log.warn("Redis cache read failed (key={}, merchant={}): {} – falling back to DB",
                     idempotencyKey, merchantId, ex.getMessage());
        }
        return Optional.empty();
    }

    /** Stores the result in Redis with a TTL matching the key expiry. */
    public void store(String merchantId, String idempotencyKey,
                      PaymentResponse response, Instant expiresAt) {
        String key = cacheKey(merchantId, idempotencyKey);
        try {
            Duration ttl = Duration.between(Instant.now(), expiresAt);
            if (ttl.isNegative() || ttl.isZero()) {
                return; // Already expired – don't cache
            }
            String json = objectMapper.writeValueAsString(response);
            redisTemplate.opsForValue().set(key, json, ttl);
            log.debug("Redis cache STORE: key={}, merchant={}, ttl={}s",
                      idempotencyKey, merchantId, ttl.getSeconds());
        } catch (Exception ex) {
            log.warn("Redis cache write failed (key={}, merchant={}): {}",
                     idempotencyKey, merchantId, ex.getMessage());
        }
    }

    public void evict(String merchantId, String idempotencyKey) {
        try {
            redisTemplate.delete(cacheKey(merchantId, idempotencyKey));
        } catch (Exception ex) {
            log.warn("Redis cache eviction failed: {}", ex.getMessage());
        }
    }

    private String cacheKey(String merchantId, String idempotencyKey) {
        return PREFIX + merchantId + ":" + idempotencyKey;
    }
}
