package com.stock.controller;

import com.stock.domain.PriceHistory;
import com.stock.domain.Stock;
import com.stock.domain.StockConsensus;
import com.stock.repository.NewsItemRepository;
import com.stock.repository.PriceHistoryRepository;
import com.stock.repository.StockConsensusRepository;
import com.stock.repository.StockRepository;
import com.stock.service.LiveDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final StockRepository stockRepository;
    private final PriceHistoryRepository priceHistoryRepository;
    private final StockConsensusRepository consensusRepository;
    private final NewsItemRepository newsItemRepository;
    private final LiveDataService liveDataService;

    @Value("${admin.token:dev-only-token}")
    private String adminToken;

    @PostMapping("/refresh-all")
    public Map<String, Object> refreshAll(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        verify(token);
        long startTs = System.currentTimeMillis();
        log.info("[Admin] refresh-all 호출됨");

        List<Stock> stocks = stockRepository.findAll();
        List<Map<String, Object>> results = new ArrayList<>();
        int priceOk = 0, priceFail = 0;
        int newsOk = 0, newsFail = 0;
        int consensusOk = 0, consensusFail = 0;

        for (Stock stock : stocks) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("code", stock.getCode());
            r.put("name", stock.getName());
            r.put("type", stock.getType().name());

            try {
                liveDataService.loadFullHistory(stock.getCode());
                long count = priceHistoryRepository.findByStockCodeOrderByTradeDateAsc(stock.getCode()).size();
                r.put("priceRows", count);
                if (count > 0) priceOk++; else priceFail++;
            } catch (Exception e) {
                r.put("priceError", e.getClass().getSimpleName() + ": " + e.getMessage());
                priceFail++;
            }

            try {
                liveDataService.refreshStockNews(stock.getCode());
                long c = newsItemRepository.findTop10ByScopeAndScopeKeyOrderByPublishedAtDesc(
                        com.stock.domain.NewsItem.Scope.STOCK, stock.getCode()).size();
                r.put("newsCount", c);
                newsOk++;
            } catch (Exception e) {
                r.put("newsError", e.getClass().getSimpleName() + ": " + e.getMessage());
                newsFail++;
            }

            try {
                liveDataService.refreshConsensus(stock.getCode());
                Optional<StockConsensus> c = consensusRepository.findByStockCode(stock.getCode());
                r.put("hasConsensus", c.isPresent() && c.get().getPriceTargetMean() != null);
                consensusOk++;
            } catch (Exception e) {
                r.put("consensusError", e.getClass().getSimpleName() + ": " + e.getMessage());
                consensusFail++;
            }

            results.add(r);
        }

        try {
            liveDataService.refreshMacroNews();
        } catch (Exception e) {
            log.warn("[Admin] 매크로 뉴스 갱신 실패: {}", e.getMessage());
        }

        long elapsedMs = System.currentTimeMillis() - startTs;
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("elapsedSec", elapsedMs / 1000);
        summary.put("totalStocks", stocks.size());
        summary.put("price", Map.of("ok", priceOk, "fail", priceFail));
        summary.put("news", Map.of("ok", newsOk, "fail", newsFail));
        summary.put("consensus", Map.of("ok", consensusOk, "fail", consensusFail));
        summary.put("results", results);
        log.info("[Admin] refresh-all 완료 ({}초): 시세 {}/{}, 뉴스 {}/{}, 컨센서스 {}/{}",
                elapsedMs / 1000, priceOk, stocks.size(), newsOk, stocks.size(), consensusOk, stocks.size());
        return summary;
    }

    @GetMapping("/db-stats")
    public Map<String, Object> dbStats(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        verify(token);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("stocks", stockRepository.count());
        List<Map<String, Object>> perStock = new ArrayList<>();
        for (Stock s : stockRepository.findAll()) {
            List<PriceHistory> hist = priceHistoryRepository.findByStockCodeOrderByTradeDateAsc(s.getCode());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", s.getCode());
            row.put("name", s.getName());
            row.put("type", s.getType().name());
            row.put("priceRows", hist.size());
            row.put("latestPrice", hist.isEmpty() ? null : hist.get(hist.size() - 1).getClosePrice());
            row.put("latestDate", hist.isEmpty() ? null : hist.get(hist.size() - 1).getTradeDate().toString());
            perStock.add(row);
        }
        stats.put("perStock", perStock);
        return stats;
    }

    private void verify(String token) {
        if (token == null || !token.equals(adminToken)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "X-Admin-Token 헤더가 필요합니다.");
        }
    }
}
