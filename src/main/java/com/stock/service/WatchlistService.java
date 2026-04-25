package com.stock.service;

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
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class WatchlistService {

    private final WatchlistRepository watchlistRepository;
    private final StockRepository stockRepository;
    private final StockPriceService stockPriceService;

    public List<StockSummary> listSummaries() {
        List<Watchlist> entries = watchlistRepository.findAllByOrderByAddedAtAsc();
        List<StockSummary> result = new ArrayList<>();
        for (Watchlist w : entries) {
            stockRepository.findById(w.getStockCode())
                    .ifPresent(s -> result.add(stockPriceService.buildSummary(s)));
        }
        return result;
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
