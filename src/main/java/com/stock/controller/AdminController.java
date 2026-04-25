package com.stock.controller;

import com.stock.client.NaverFinanceClient;
import com.stock.domain.NewsItem;
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
    private final NaverFinanceClient naverClient;

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

    @PostMapping("/refresh/{code}")
    public Map<String, Object> refreshOne(@PathVariable String code,
                                           @RequestHeader(value = "X-Admin-Token", required = false) String token) {
        verify(token);
        Stock stock = stockRepository.findById(code).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "종목 없음: " + code));
        log.info("[Admin] refresh/{} 호출됨", code);

        long startTs = System.currentTimeMillis();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("code", stock.getCode());
        r.put("name", stock.getName());
        r.put("type", stock.getType().name());

        long t0 = System.currentTimeMillis();
        try {
            liveDataService.loadFullHistory(code);
            long count = priceHistoryRepository.findByStockCodeOrderByTradeDateAsc(code).size();
            r.put("priceRows", count);
            if (count == 0) {
                String fetchErr = naverClient.getLastError("daily:" + code);
                if (fetchErr != null) r.put("priceFetchError", fetchErr);
            }
        } catch (Exception e) {
            r.put("priceError", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        r.put("priceMs", System.currentTimeMillis() - t0);

        long t1 = System.currentTimeMillis();
        try {
            liveDataService.refreshStockNews(code);
            long c = newsItemRepository.findTop10ByScopeAndScopeKeyOrderByPublishedAtDesc(
                    NewsItem.Scope.STOCK, code).size();
            r.put("newsCount", c);
        } catch (Exception e) {
            r.put("newsError", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        r.put("newsMs", System.currentTimeMillis() - t1);

        long t2 = System.currentTimeMillis();
        try {
            liveDataService.refreshConsensus(code);
            Optional<StockConsensus> c = consensusRepository.findByStockCode(code);
            r.put("hasConsensus", c.isPresent() && c.get().getPriceTargetMean() != null);
            String integrationErr = naverClient.getLastError("integration:" + code);
            if (integrationErr != null) r.put("integrationFetchError", integrationErr);
        } catch (Exception e) {
            r.put("consensusError", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        r.put("consensusMs", System.currentTimeMillis() - t2);

        r.put("totalMs", System.currentTimeMillis() - startTs);
        return r;
    }

    @PostMapping("/refresh-missing")
    public Map<String, Object> refreshMissing(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        verify(token);
        long startTs = System.currentTimeMillis();
        log.info("[Admin] refresh-missing 호출됨");

        List<Stock> targets = stockRepository.findAll().stream()
                .filter(s -> priceHistoryRepository.findByStockCodeOrderByTradeDateAsc(s.getCode()).isEmpty())
                .toList();

        List<Map<String, Object>> results = new ArrayList<>();
        int ok = 0, fail = 0;
        for (Stock stock : targets) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("code", stock.getCode());
            r.put("name", stock.getName());
            r.put("type", stock.getType().name());
            try {
                liveDataService.loadFullHistory(stock.getCode());
                long count = priceHistoryRepository.findByStockCodeOrderByTradeDateAsc(stock.getCode()).size();
                r.put("priceRows", count);
                if (count > 0) ok++;
                else {
                    fail++;
                    String fetchErr = naverClient.getLastError("daily:" + stock.getCode());
                    if (fetchErr != null) r.put("priceFetchError", fetchErr);
                }
            } catch (Exception e) {
                fail++;
                r.put("priceError", e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            results.add(r);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("elapsedSec", (System.currentTimeMillis() - startTs) / 1000);
        summary.put("targetCount", targets.size());
        summary.put("ok", ok);
        summary.put("fail", fail);
        summary.put("results", results);
        log.info("[Admin] refresh-missing 완료 ({}초): {}/{} 성공",
                (System.currentTimeMillis() - startTs) / 1000, ok, targets.size());
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
