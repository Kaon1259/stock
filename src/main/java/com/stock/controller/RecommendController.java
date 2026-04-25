package com.stock.controller;

import com.stock.dto.RecommendedStock;
import com.stock.dto.WatchlistSignal;
import com.stock.service.DailyPickService;
import com.stock.service.WatchlistSignalService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class RecommendController {

    private final DailyPickService dailyPickService;
    private final WatchlistSignalService watchlistSignalService;

    @GetMapping("/api/picks/today")
    @ResponseBody
    public List<RecommendedStock> todaysPicks() {
        return dailyPickService.getTodaysPicks();
    }

    @GetMapping("/api/watchlist/signals")
    @ResponseBody
    public List<WatchlistSignal> watchlistSignals() {
        return watchlistSignalService.signalsForWatchlist();
    }
}
