package com.stock.controller;

import com.stock.client.NaverFinanceClient;
import com.stock.domain.Stock;
import com.stock.domain.Watchlist;
import com.stock.dto.ChartSeries;
import com.stock.dto.DeepAnalysisContent;
import com.stock.dto.LiveQuote;
import com.stock.dto.Prediction;
import com.stock.dto.StockSummary;
import com.stock.repository.StockRepository;
import com.stock.repository.WatchlistRepository;
import com.stock.service.AnalystService;
import com.stock.service.DeepAnalysisService;
import com.stock.service.NewsService;
import com.stock.service.PredictionService;
import com.stock.service.StockPriceService;
import com.stock.service.WatchlistService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequiredArgsConstructor
public class StockController {

    private final StockRepository stockRepository;
    private final StockPriceService stockPriceService;
    private final NewsService newsService;
    private final WatchlistService watchlistService;
    private final PredictionService predictionService;
    private final AnalystService analystService;
    private final DeepAnalysisService deepAnalysisService;
    private final NaverFinanceClient naverFinanceClient;
    private final WatchlistRepository watchlistRepository;

    @GetMapping("/stocks/{code}")
    public String detail(@PathVariable String code, Model model, RedirectAttributes redirect) {
        Stock stock = stockRepository.findById(code).orElse(null);
        if (stock == null) {
            redirect.addFlashAttribute("error", "존재하지 않는 종목 코드: " + code);
            return "redirect:/";
        }
        StockSummary summary = stockPriceService.buildSummary(stock);
        model.addAttribute("stock", summary);
        model.addAttribute("inWatchlist", watchlistService.contains(code));
        model.addAttribute("consensus", analystService.consensusFor(code, summary.getCurrentPrice()));
        model.addAttribute("stockNews", newsService.stockNews(code));
        model.addAttribute("macroNews", newsService.macroNews());
        model.addAttribute("globalNews", newsService.globalNews());
        model.addAttribute("active", "");
        return "stock-detail";
    }

    @GetMapping("/stocks/{code}/deep")
    public String deepPage(@PathVariable String code, Model model, RedirectAttributes redirect) {
        Stock stock = stockRepository.findById(code).orElse(null);
        if (stock == null) {
            redirect.addFlashAttribute("error", "존재하지 않는 종목 코드: " + code);
            return "redirect:/";
        }
        model.addAttribute("stock", stockPriceService.buildSummary(stock));
        model.addAttribute("active", "");
        return "deep-analysis";
    }

    @GetMapping("/api/stocks/{code}/chart")
    @ResponseBody
    public ChartSeries chart(@PathVariable String code,
                             @RequestParam(defaultValue = "daily") String range) {
        return stockPriceService.buildSeries(code, range);
    }

    @GetMapping("/api/stocks/{code}/prediction")
    @ResponseBody
    public Prediction prediction(@PathVariable String code) {
        Stock stock = stockRepository.findById(code).orElseThrow();
        return predictionService.predict(stockPriceService.buildSummary(stock));
    }

    @GetMapping("/api/stocks/{code}/deep")
    @ResponseBody
    public DeepAnalysisContent deepAnalysis(@PathVariable String code) {
        Stock stock = stockRepository.findById(code).orElseThrow();
        return deepAnalysisService.getOrGenerate(stockPriceService.buildSummary(stock));
    }

    @GetMapping("/api/stocks/{code}/live")
    @ResponseBody
    public LiveQuote live(@PathVariable String code) {
        return buildLiveQuote(code);
    }

    @GetMapping("/api/stocks/{code}/intraday")
    @ResponseBody
    public Map<String, Object> intraday(@PathVariable String code) {
        Stock stock = stockRepository.findById(code).orElseThrow();
        List<NaverFinanceClient.MinutePoint> rows = naverFinanceClient.fetchTodayMinutes(code);
        List<Map<String, Number>> points = new ArrayList<>();
        for (NaverFinanceClient.MinutePoint p : rows) {
            long sec = p.time().getHour() * 3600L + p.time().getMinute() * 60L + p.time().getSecond();
            Map<String, Number> pt = new LinkedHashMap<>();
            pt.put("x", sec);
            pt.put("y", p.price());
            points.add(pt);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code);
        result.put("name", stock.getName());
        result.put("points", points);
        return result;
    }

    @GetMapping("/api/watchlist/intraday")
    @ResponseBody
    public List<Map<String, Object>> watchlistIntraday() {
        List<Watchlist> entries = watchlistRepository.findAllByOrderByAddedAtAsc();
        return entries.parallelStream()
                .map(w -> {
                    try { return intraday(w.getStockCode()); }
                    catch (Exception e) { return null; }
                })
                .filter(Objects::nonNull)
                .toList();
    }

    @GetMapping("/api/watchlist/live")
    @ResponseBody
    public List<LiveQuote> watchlistLive() {
        List<Watchlist> entries = watchlistRepository.findAllByOrderByAddedAtAsc();
        return entries.parallelStream()
                .map(w -> {
                    try { return buildLiveQuote(w.getStockCode()); }
                    catch (Exception e) { return null; }
                })
                .filter(Objects::nonNull)
                .toList();
    }

    private LiveQuote buildLiveQuote(String code) {
        Stock stock = stockRepository.findById(code).orElseThrow();
        StockSummary fallback = stockPriceService.buildSummary(stock);
        NaverFinanceClient.Snapshot snap = naverFinanceClient.fetchSnapshot(code);

        long current = snap != null && snap.getCurrentPrice() > 0 ? snap.getCurrentPrice() : fallback.getCurrentPrice();
        long prev = snap != null && snap.getPreviousClose() > 0 ? snap.getPreviousClose() : fallback.getPreviousClose();
        long volume = snap != null && snap.getVolume() > 0 ? snap.getVolume() : fallback.getVolume();
        long change = current - prev;
        double rate = prev == 0 ? 0 : Math.round((change * 10000.0) / prev) / 100.0;

        return LiveQuote.builder()
                .code(code)
                .name(stock.getName())
                .currentPrice(current)
                .previousClose(prev)
                .change(change)
                .changeRate(rate)
                .volume(volume)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}
