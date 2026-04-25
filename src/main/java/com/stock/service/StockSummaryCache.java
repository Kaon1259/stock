package com.stock.service;

import com.stock.domain.PriceHistory;
import com.stock.domain.Stock;
import com.stock.dto.StockSummary;
import com.stock.repository.PriceHistoryRepository;
import com.stock.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class StockSummaryCache {

    private final StockRepository stockRepository;
    private final PriceHistoryRepository priceHistoryRepository;

    private final Map<String, StockSummary> cache = new ConcurrentHashMap<>();

    public StockSummary get(String code) {
        StockSummary cached = cache.get(code);
        if (cached != null) return cached;
        return refresh(code);
    }

    public StockSummary refresh(String code) {
        try {
            StockSummary computed = stockRepository.findById(code)
                    .map(this::compute)
                    .orElse(null);
            if (computed == null) {
                cache.remove(code);
            } else {
                cache.put(code, computed);
            }
            return computed;
        } catch (Exception e) {
            log.warn("[StockSummaryCache] {} refresh 실패: {}", code, e.getMessage());
            return cache.get(code);
        }
    }

    public void invalidate(String code) {
        cache.remove(code);
    }

    public int warmUpAll() {
        long startTs = System.currentTimeMillis();
        int count = 0;
        for (Stock stock : stockRepository.findAll()) {
            StockSummary s = compute(stock);
            if (s != null) {
                cache.put(stock.getCode(), s);
                count++;
            }
        }
        log.info("[StockSummaryCache] 워밍업 완료: {}/{}건 ({}ms)",
                count, count, System.currentTimeMillis() - startTs);
        return count;
    }

    public int size() {
        return cache.size();
    }

    private StockSummary compute(Stock stock) {
        List<PriceHistory> history = priceHistoryRepository.findByStockCodeOrderByTradeDateAsc(stock.getCode());
        String typeName = stock.getType() == null ? "STOCK" : stock.getType().name();
        if (history.isEmpty()) {
            return StockSummary.builder()
                    .code(stock.getCode()).name(stock.getName())
                    .market(stock.getMarket()).sector(stock.getSector())
                    .type(typeName)
                    .build();
        }
        PriceHistory last = history.get(history.size() - 1);
        long prev = history.size() >= 2 ? history.get(history.size() - 2).getClosePrice() : last.getClosePrice();
        long change = last.getClosePrice() - prev;
        double rate = prev == 0 ? 0 : (change * 100.0) / prev;

        return StockSummary.builder()
                .code(stock.getCode()).name(stock.getName())
                .market(stock.getMarket()).sector(stock.getSector())
                .type(typeName)
                .currentPrice(last.getClosePrice())
                .previousClose(prev)
                .change(change)
                .changeRate(Math.round(rate * 100) / 100.0)
                .volume(last.getVolume())
                .build();
    }
}
