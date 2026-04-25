package com.stock.config;

import com.stock.repository.StockRepository;
import com.stock.service.LiveDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class LiveDataScheduler {

    private final StockRepository stockRepository;
    private final LiveDataService liveDataService;

    @Scheduled(fixedDelay = 600_000, initialDelay = 600_000)
    public void refreshSnapshots() {
        LocalTime now = LocalTime.now();
        if (now.isBefore(LocalTime.of(8, 0)) || now.isAfter(LocalTime.of(18, 0))) {
            log.debug("[Scheduler] 장외 시간 — 스냅샷 스킵");
            return;
        }
        log.info("[Scheduler] 종목 스냅샷 갱신 시작");
        stockRepository.findAll().forEach(stock -> {
            try {
                liveDataService.refreshSnapshot(stock.getCode());
            } catch (Exception e) {
                log.warn("[Scheduler] {} 스냅샷 실패: {}", stock.getCode(), e.getMessage());
            }
        });
        log.info("[Scheduler] 스냅샷 갱신 완료");
    }

    @Scheduled(fixedDelay = 1_800_000, initialDelay = 1_800_000)
    public void refreshNewsAll() {
        log.info("[Scheduler] 뉴스 갱신 시작");
        try {
            liveDataService.refreshMacroNews();
        } catch (Exception e) {
            log.warn("[Scheduler] 매크로 뉴스 실패: {}", e.getMessage());
        }
        stockRepository.findAll().forEach(stock -> {
            try {
                liveDataService.refreshStockNews(stock.getCode());
            } catch (Exception e) {
                log.warn("[Scheduler] {} 뉴스 실패: {}", stock.getCode(), e.getMessage());
            }
        });
        log.info("[Scheduler] 뉴스 갱신 완료");
    }
}
