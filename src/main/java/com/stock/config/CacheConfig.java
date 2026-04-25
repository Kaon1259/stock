package com.stock.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

@Configuration
@EnableCaching
public class CacheConfig {

    public static final String STOCK_SUMMARY = "stockSummary";
    public static final String CHART_DATA = "chartData";
    public static final String CONSENSUS = "consensus";
    public static final String STOCK_NEWS = "stockNews";
    public static final String MACRO_NEWS = "macroNews";
    public static final String WATCHLIST_SIGNALS = "watchlistSignals";

    @Bean
    public CacheManager cacheManager() {
        SimpleCacheManager manager = new SimpleCacheManager();
        manager.setCaches(List.of(
                build(STOCK_SUMMARY, Duration.ofSeconds(60), 200),
                build(CHART_DATA, Duration.ofSeconds(60), 500),
                build(CONSENSUS, Duration.ofHours(1), 200),
                build(STOCK_NEWS, Duration.ofMinutes(10), 200),
                build(MACRO_NEWS, Duration.ofMinutes(10), 8),
                build(WATCHLIST_SIGNALS, Duration.ofMinutes(5), 16)
        ));
        return manager;
    }

    private CaffeineCache build(String name, Duration ttl, long maxSize) {
        return new CaffeineCache(name,
                Caffeine.newBuilder()
                        .expireAfterWrite(ttl)
                        .maximumSize(maxSize)
                        .recordStats()
                        .build());
    }
}
