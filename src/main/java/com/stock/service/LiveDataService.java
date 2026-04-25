package com.stock.service;

import com.stock.client.GoogleNewsClient;
import com.stock.client.NaverFinanceClient;
import com.stock.config.CacheConfig;
import com.stock.domain.AnalystReport;
import com.stock.domain.NewsItem;
import com.stock.domain.PriceHistory;
import com.stock.domain.Stock;
import com.stock.domain.StockConsensus;
import com.stock.repository.AnalystReportRepository;
import com.stock.repository.NewsItemRepository;
import com.stock.repository.PriceHistoryRepository;
import com.stock.repository.StockConsensusRepository;
import com.stock.repository.StockRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class LiveDataService {

    private final StockRepository stockRepository;
    private final PriceHistoryRepository priceHistoryRepository;
    private final NewsItemRepository newsItemRepository;
    private final StockConsensusRepository consensusRepository;
    private final AnalystReportRepository reportRepository;
    private final NaverFinanceClient naverClient;
    private final GoogleNewsClient googleNewsClient;

    private final Map<String, LocalDateTime> lastSnapshotAt = new ConcurrentHashMap<>();

    @Transactional
    public void initializeStock(String code) {
        loadFullHistory(code);
        refreshStockNews(code);
        refreshConsensus(code);
    }

    @Transactional
    public void refreshConsensus(String code) {
        NaverFinanceClient.IntegrationResponse resp = naverClient.fetchIntegration(code);
        if (resp == null) return;

        if (resp.consensus() != null) {
            StockConsensus c = consensusRepository.findByStockCode(code).orElseGet(() ->
                    StockConsensus.builder().stockCode(code).build());
            c.setPriceTargetMean(resp.consensus().priceTargetMean());
            c.setOpinionMean(resp.consensus().opinionMean());
            c.setCalculatedAt(resp.consensus().calculatedAt());
            consensusRepository.save(c);
        }

        List<NaverFinanceClient.Research> researches = resp.researches();
        if (researches != null && !researches.isEmpty()) {
            reportRepository.deleteAllByStockCode(code);
            reportRepository.flush();
            List<AnalystReport> rows = new ArrayList<>();
            for (NaverFinanceClient.Research r : researches) {
                if (r.publishedAt() == null) continue;
                rows.add(AnalystReport.builder()
                        .stockCode(code)
                        .externalId(r.externalId())
                        .firmName(r.firmName())
                        .title(r.title())
                        .publishedAt(r.publishedAt())
                        .build());
            }
            reportRepository.saveAll(rows);
            log.info("[Live] {} 컨센서스/리포트 갱신 (리포트 {}건, 평균 목표가 {})",
                    code, rows.size(),
                    resp.consensus() == null ? "없음" : resp.consensus().priceTargetMean());
        }
    }

    @Transactional
    @Caching(evict = {
            @CacheEvict(value = CacheConfig.STOCK_SUMMARY, key = "#code"),
            @CacheEvict(value = CacheConfig.CHART_DATA, allEntries = true),
            @CacheEvict(value = CacheConfig.WATCHLIST_SIGNALS, allEntries = true)
    })
    public void loadFullHistory(String code) {
        List<NaverFinanceClient.DailyOhlcv> rows = naverClient.fetchDailyHistory(code, 400);
        if (rows.isEmpty()) {
            log.warn("[Live] {} 일별 시세 응답 없음 — 기존 데이터 유지", code);
            return;
        }
        priceHistoryRepository.deleteAllByStockCode(code);
        priceHistoryRepository.flush();
        List<PriceHistory> toSave = new ArrayList<>();
        for (NaverFinanceClient.DailyOhlcv r : rows) {
            toSave.add(PriceHistory.builder()
                    .stockCode(code).tradeDate(r.date())
                    .openPrice(r.open()).highPrice(r.high()).lowPrice(r.low())
                    .closePrice(r.close()).volume(r.volume())
                    .build());
        }
        priceHistoryRepository.saveAll(toSave);
        log.info("[Live] {} 일별 시세 {}건 적재", code, toSave.size());
    }

    @Transactional
    @Caching(evict = {
            @CacheEvict(value = CacheConfig.STOCK_SUMMARY, key = "#code"),
            @CacheEvict(value = CacheConfig.CHART_DATA, allEntries = true),
            @CacheEvict(value = CacheConfig.WATCHLIST_SIGNALS, allEntries = true)
    })
    public void refreshSnapshot(String code) {
        NaverFinanceClient.IntegrationResponse resp = naverClient.fetchIntegration(code);
        if (resp == null || resp.snapshot() == null) return;
        NaverFinanceClient.Snapshot snap = resp.snapshot();
        if (snap.getCurrentPrice() == 0) return;

        LocalDate today = LocalDate.now();
        PriceHistory todayRow = priceHistoryRepository.findByStockCodeOrderByTradeDateAsc(code).stream()
                .filter(p -> p.getTradeDate().equals(today)).findFirst().orElse(null);

        if (todayRow == null) {
            priceHistoryRepository.save(PriceHistory.builder()
                    .stockCode(code).tradeDate(today)
                    .openPrice(snap.getCurrentPrice())
                    .highPrice(snap.getCurrentPrice())
                    .lowPrice(snap.getCurrentPrice())
                    .closePrice(snap.getCurrentPrice())
                    .volume(snap.getVolume())
                    .build());
        } else {
            todayRow.setClosePrice(snap.getCurrentPrice());
            if (snap.getCurrentPrice() > todayRow.getHighPrice()) todayRow.setHighPrice(snap.getCurrentPrice());
            if (snap.getCurrentPrice() < todayRow.getLowPrice() || todayRow.getLowPrice() == 0)
                todayRow.setLowPrice(snap.getCurrentPrice());
            todayRow.setVolume(snap.getVolume());
            priceHistoryRepository.save(todayRow);
        }

        if (resp.consensus() != null) {
            StockConsensus c = consensusRepository.findByStockCode(code).orElseGet(() ->
                    StockConsensus.builder().stockCode(code).build());
            c.setPriceTargetMean(resp.consensus().priceTargetMean());
            c.setOpinionMean(resp.consensus().opinionMean());
            c.setCalculatedAt(resp.consensus().calculatedAt());
            consensusRepository.save(c);
        }
        lastSnapshotAt.put(code, LocalDateTime.now());
    }

    @Transactional
    @CacheEvict(value = CacheConfig.STOCK_NEWS, key = "#code")
    public void refreshStockNews(String code) {
        Stock stock = stockRepository.findById(code).orElse(null);
        if (stock == null) return;
        List<GoogleNewsClient.Article> articles = googleNewsClient.search(stock.getName(), 8);
        replaceNews(NewsItem.Scope.STOCK, code, articles);
    }

    @Transactional
    @CacheEvict(value = CacheConfig.MACRO_NEWS, allEntries = true)
    public void refreshMacroNews() {
        List<GoogleNewsClient.Article> domestic = googleNewsClient.search("코스피 시황", 8);
        replaceNews(NewsItem.Scope.MACRO, null, domestic);

        List<GoogleNewsClient.Article> global = googleNewsClient.search("미국 증시", 8);
        replaceNews(NewsItem.Scope.GLOBAL, null, global);
    }

    private void replaceNews(NewsItem.Scope scope, String key, List<GoogleNewsClient.Article> articles) {
        if (articles.isEmpty()) return;
        List<NewsItem> existing = key == null
                ? newsItemRepository.findTop10ByScopeOrderByPublishedAtDesc(scope)
                : newsItemRepository.findTop10ByScopeAndScopeKeyOrderByPublishedAtDesc(scope, key);
        Set<String> existingUrls = new HashSet<>();
        existing.forEach(n -> existingUrls.add(n.getUrl()));

        List<NewsItem> toSave = new ArrayList<>();
        for (GoogleNewsClient.Article a : articles) {
            if (existingUrls.contains(a.url())) continue;
            toSave.add(NewsItem.builder()
                    .scope(scope).scopeKey(key)
                    .title(truncate(a.title(), 250))
                    .url(truncate(a.url(), 500))
                    .source(truncate(a.source(), 60))
                    .summary(truncate(a.summary(), 1000))
                    .publishedAt(a.publishedAt())
                    .build());
        }
        if (!toSave.isEmpty()) {
            newsItemRepository.saveAll(toSave);
            log.info("[Live] news scope={} key={} 신규 {}건", scope, key, toSave.size());
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
