package com.stock.controller;

import com.stock.repository.StockRepository;
import com.stock.service.WatchlistService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@RequiredArgsConstructor
public class HomeController {

    private final WatchlistService watchlistService;
    private final StockRepository stockRepository;

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("watchlist", watchlistService.listSummaries());
        model.addAttribute("totalStocks", stockRepository.count());
        model.addAttribute("active", "home");
        return "index";
    }

    @GetMapping("/picks")
    public String picks(Model model) {
        model.addAttribute("active", "picks");
        return "picks";
    }
}
