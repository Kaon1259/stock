package com.stock.controller;

import com.stock.repository.StockRepository;
import com.stock.service.WatchlistService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequiredArgsConstructor
@RequestMapping("/watchlist")
public class WatchlistController {

    private final WatchlistService watchlistService;
    private final StockRepository stockRepository;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("watchlist", watchlistService.listSummaries());
        model.addAttribute("allStocks", stockRepository.findAll());
        model.addAttribute("active", "watchlist");
        return "watchlist";
    }

    @PostMapping("/add")
    public String add(@RequestParam String code, RedirectAttributes redirect) {
        try {
            watchlistService.add(code);
            redirect.addFlashAttribute("message", "관심 종목에 추가했습니다.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/watchlist";
    }

    @PostMapping("/remove")
    public String remove(@RequestParam String code, RedirectAttributes redirect) {
        watchlistService.remove(code);
        redirect.addFlashAttribute("message", "관심 종목에서 제거했습니다.");
        return "redirect:/watchlist";
    }
}
