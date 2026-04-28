package com.stock.service;

import com.stock.client.NaverFinanceClient;
import com.stock.domain.Stock;
import com.stock.domain.Watchlist;
import com.stock.dto.StockSummary;
import com.stock.config.CacheConfig;
import com.stock.repository.StockRepository;
import com.stock.repository.WatchlistRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class WatchlistService {

    private final WatchlistRepository watchlistRepository;
    private final StockRepository stockRepository;
    private final StockSummaryCache stockSummaryCache;
    private final NaverFinanceClient naverFinanceClient;

    public List<StockSummary> listSummaries() {
        List<Watchlist> entries = watchlistRepository.findAllByOrderByAddedAtAsc();
        return entries.parallelStream()
                .map(w -> buildLiveSummary(w.getStockCode()))
                .filter(Objects::nonNull)
                .toList();
    }

    private StockSummary buildLiveSummary(String code) {
        StockSummary base = stockSummaryCache.get(code);
        Stock stock = stockRepository.findById(code).orElse(null);
        if (base == null && stock == null) return null;

        NaverFinanceClient.Snapshot snap = naverFinanceClient.fetchSnapshot(code);
        long current = snap != null && snap.getCurrentPrice() > 0 ? snap.getCurrentPrice()
                : (base != null ? base.getCurrentPrice() : 0);
        long prev = snap != null && snap.getPreviousClose() > 0 ? snap.getPreviousClose()
                : (base != null ? base.getPreviousClose() : 0);
        long volume = snap != null && snap.getVolume() > 0 ? snap.getVolume()
                : (base != null ? base.getVolume() : 0);
        long change = current - prev;
        double rate = prev == 0 ? 0 : Math.round((change * 10000.0) / prev) / 100.0;

        return StockSummary.builder()
                .code(code)
                .name(stock != null ? stock.getName() : (base != null ? base.getName() : code))
                .market(stock != null ? stock.getMarket() : (base != null ? base.getMarket() : ""))
                .sector(stock != null ? stock.getSector() : (base != null ? base.getSector() : ""))
                .type(stock != null && stock.getType() != null ? stock.getType().name()
                        : (base != null ? base.getType() : "STOCK"))
                .currentPrice(current)
                .previousClose(prev)
                .change(change)
                .changeRate(rate)
                .volume(volume)
                .build();
    }

    @Transactional
    @CacheEvict(value = CacheConfig.WATCHLIST_SIGNALS, allEntries = true)
    public void add(String code) {
        Optional<Stock> stock = stockRepository.findById(code);
        if (stock.isEmpty()) throw new IllegalArgumentException("등록되지 않은 종목 코드: " + code);
        if (watchlistRepository.existsByStockCode(code)) return;
        watchlistRepository.save(Watchlist.builder().stockCode(code).build());
    }

    @Transactional
    @CacheEvict(value = CacheConfig.WATCHLIST_SIGNALS, allEntries = true)
    public void remove(String code) {
        watchlistRepository.deleteByStockCode(code);
    }

    public boolean contains(String code) {
        return watchlistRepository.existsByStockCode(code);
    }
}
