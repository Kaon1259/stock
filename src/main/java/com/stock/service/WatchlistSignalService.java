package com.stock.service;

import com.stock.domain.PriceHistory;
import com.stock.domain.Stock;
import com.stock.domain.StockConsensus;
import com.stock.domain.Watchlist;
import com.stock.config.CacheConfig;
import com.stock.dto.WatchlistSignal;
import com.stock.repository.PriceHistoryRepository;
import com.stock.repository.StockConsensusRepository;
import com.stock.repository.StockRepository;
import com.stock.repository.WatchlistRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class WatchlistSignalService {

    private final WatchlistRepository watchlistRepository;
    private final StockRepository stockRepository;
    private final PriceHistoryRepository priceHistoryRepository;
    private final StockConsensusRepository consensusRepository;

    @Cacheable(value = CacheConfig.WATCHLIST_SIGNALS, key = "'all'")
    public List<WatchlistSignal> signalsForWatchlist() {
        List<Watchlist> entries = watchlistRepository.findAllByOrderByAddedAtAsc();
        List<WatchlistSignal> result = new ArrayList<>();
        for (Watchlist w : entries) {
            stockRepository.findById(w.getStockCode()).ifPresent(stock ->
                    result.add(computeSignal(stock)));
        }
        return result;
    }

    private WatchlistSignal computeSignal(Stock stock) {
        List<PriceHistory> history = priceHistoryRepository.findByStockCodeOrderByTradeDateAsc(stock.getCode());
        if (history.size() < 7) {
            return WatchlistSignal.builder()
                    .code(stock.getCode()).signal("HOLD").label("정보 부족").emoji("⏸️")
                    .reason("시세 이력이 충분하지 않아요.").build();
        }
        PriceHistory last = history.get(history.size() - 1);
        PriceHistory fiveBack = history.get(Math.max(0, history.size() - 6));
        double momentum = (last.getClosePrice() - fiveBack.getClosePrice()) * 100.0 / fiveBack.getClosePrice();

        Optional<StockConsensus> consOpt = consensusRepository.findByStockCode(stock.getCode());
        Double opinion = consOpt.map(StockConsensus::getOpinionMean).orElse(null);
        Long target = consOpt.map(StockConsensus::getPriceTargetMean).orElse(null);
        double upside = (target != null && last.getClosePrice() > 0)
                ? (target - last.getClosePrice()) * 100.0 / last.getClosePrice() : 0;

        double score = 0;
        if (opinion != null) score += (opinion - 3.0) * 25;
        score += upside * 0.6;
        score += momentum * 0.4;

        String signal, label, emoji, reason;
        if (score >= 18) {
            signal = "BUY"; label = "매수 추천"; emoji = "🟢";
            reason = buildBuyReason(opinion, upside, momentum);
        } else if (score <= -10) {
            signal = "SELL"; label = "매도 권고"; emoji = "🔴";
            reason = buildSellReason(opinion, upside, momentum);
        } else {
            signal = "HOLD"; label = "관망"; emoji = "🟡";
            reason = buildHoldReason(opinion, upside, momentum);
        }

        return WatchlistSignal.builder()
                .code(stock.getCode())
                .signal(signal).label(label).emoji(emoji).reason(reason)
                .build();
    }

    private String buildBuyReason(Double opinion, double upside, double momentum) {
        StringBuilder sb = new StringBuilder();
        if (opinion != null && opinion >= 4.0) sb.append("전문가 매수 의견 우세");
        if (upside > 15) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(String.format("목표가 +%.0f%% 여력", upside));
        }
        if (momentum > 3) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(String.format("최근 5일 +%.1f%%", momentum));
        }
        return sb.length() == 0 ? "긍정적 신호 종합" : sb.toString();
    }

    private String buildSellReason(Double opinion, double upside, double momentum) {
        StringBuilder sb = new StringBuilder();
        if (upside < 0) sb.append(String.format("목표가 대비 %.0f%% 고평가", upside));
        if (momentum < -3) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(String.format("최근 5일 %.1f%%", momentum));
        }
        if (opinion != null && opinion < 3) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("전문가 의견 약세");
        }
        return sb.length() == 0 ? "부정적 신호 종합" : sb.toString();
    }

    private String buildHoldReason(Double opinion, double upside, double momentum) {
        if (opinion != null && upside != 0) {
            return String.format("전문가 의견 %.1f/5, 목표가 %+.0f%% 여력 — 좀 더 지켜보세요",
                    opinion, upside);
        }
        return "뚜렷한 방향성이 없어요. 좀 더 지켜보세요.";
    }
}
