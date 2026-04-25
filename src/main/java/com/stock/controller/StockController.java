package com.stock.controller;

import com.stock.domain.Stock;
import com.stock.dto.ChartSeries;
import com.stock.dto.DeepAnalysisContent;
import com.stock.dto.Prediction;
import com.stock.dto.StockSummary;
import com.stock.repository.StockRepository;
import com.stock.service.AnalystService;
import com.stock.service.DeepAnalysisService;
import com.stock.service.NewsService;
import com.stock.service.PredictionService;
import com.stock.service.StockPriceService;
import com.stock.service.WatchlistService;
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
}
