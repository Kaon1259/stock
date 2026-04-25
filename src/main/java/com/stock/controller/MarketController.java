package com.stock.controller;

import com.stock.service.NewsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@RequiredArgsConstructor
public class MarketController {

    private final NewsService newsService;

    @GetMapping("/market")
    public String market(Model model) {
        model.addAttribute("macroNews", newsService.macroNews());
        model.addAttribute("globalNews", newsService.globalNews());
        model.addAttribute("active", "market");
        return "market";
    }
}
