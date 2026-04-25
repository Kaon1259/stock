package com.stock.service;

import com.stock.domain.PriceHistory;
import com.stock.domain.Stock;
import com.stock.dto.ChartPoint;
import com.stock.dto.ChartSeries;
import com.stock.dto.StockSummary;
import com.stock.config.CacheConfig;
import com.stock.repository.PriceHistoryRepository;
import com.stock.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StockPriceService {

    private final StockRepository stockRepository;
    private final PriceHistoryRepository priceHistoryRepository;

    public boolean hasHistory(String code) {
        return priceHistoryRepository.findTopByStockCodeOrderByTradeDateDesc(code).isPresent();
    }

    @Cacheable(value = CacheConfig.STOCK_SUMMARY, key = "#stock.code")
    public StockSummary buildSummary(Stock stock) {
        List<PriceHistory> history = priceHistoryRepository.findByStockCodeOrderByTradeDateAsc(stock.getCode());
        if (history.isEmpty()) {
            return StockSummary.builder()
                    .code(stock.getCode()).name(stock.getName())
                    .market(stock.getMarket()).sector(stock.getSector())
                    .type(stock.getType() == null ? "STOCK" : stock.getType().name())
                    .build();
        }
        PriceHistory last = history.get(history.size() - 1);
        long prev = history.size() >= 2 ? history.get(history.size() - 2).getClosePrice() : last.getClosePrice();
        long change = last.getClosePrice() - prev;
        double rate = prev == 0 ? 0 : (change * 100.0) / prev;

        return StockSummary.builder()
                .code(stock.getCode())
                .name(stock.getName())
                .market(stock.getMarket())
                .sector(stock.getSector())
                .type(stock.getType() == null ? "STOCK" : stock.getType().name())
                .currentPrice(last.getClosePrice())
                .previousClose(prev)
                .change(change)
                .changeRate(Math.round(rate * 100) / 100.0)
                .volume(last.getVolume())
                .build();
    }

    @Cacheable(value = CacheConfig.CHART_DATA, key = "#code + '-' + (#range == null ? 'weekly' : #range)")
    public ChartSeries buildSeries(String code, String range) {
        List<PriceHistory> history = priceHistoryRepository.findByStockCodeOrderByTradeDateAsc(code);
        if (history.isEmpty()) return ChartSeries.builder().range(range).points(List.of()).build();

        return switch (range == null ? "weekly" : range.toLowerCase()) {
            case "daily" -> buildDaily(history);
            case "monthly" -> buildAggregated(history, "month");
            case "quarterly" -> buildAggregated(history, "quarter");
            default -> buildAggregated(history, "week");
        };
    }

    private ChartSeries buildDaily(List<PriceHistory> history) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MM-dd");
        int start = Math.max(0, history.size() - 60);
        List<PriceHistory> window = history.subList(start, history.size());
        List<ChartPoint> points = window.stream()
                .map(p -> ChartPoint.builder()
                        .label(p.getTradeDate().format(fmt))
                        .close(p.getClosePrice())
                        .build())
                .collect(Collectors.toList());
        return ChartSeries.builder()
                .range("daily")
                .points(points)
                .forecast(forecastNextDays(history, 3))
                .build();
    }

    private List<ChartPoint> forecastNextDays(List<PriceHistory> history, int days) {
        if (history.size() < 5) return List.of();
        int n = Math.min(14, history.size());
        List<PriceHistory> tail = history.subList(history.size() - n, history.size());

        double xMean = (n - 1) / 2.0;
        double yMean = tail.stream().mapToLong(PriceHistory::getClosePrice).average().orElse(0);
        double num = 0, den = 0;
        for (int i = 0; i < n; i++) {
            double x = i - xMean;
            double y = tail.get(i).getClosePrice() - yMean;
            num += x * y;
            den += x * x;
        }
        double slope = den == 0 ? 0 : num / den;
        double lastClose = tail.get(n - 1).getClosePrice();

        Random rng = new Random(history.get(0).getStockCode().hashCode() + (long) lastClose);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MM-dd");

        List<ChartPoint> out = new ArrayList<>();
        LocalDate cursor = tail.get(n - 1).getTradeDate();
        double projected = lastClose;
        for (int i = 1; i <= days; i++) {
            cursor = nextTradingDay(cursor);
            double noise = rng.nextGaussian() * lastClose * 0.005;
            projected = projected + slope + noise;
            out.add(ChartPoint.builder()
                    .label(cursor.format(fmt))
                    .close(Math.max(1, Math.round(projected)))
                    .build());
        }
        return out;
    }

    private LocalDate nextTradingDay(LocalDate from) {
        LocalDate d = from.plusDays(1);
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
            d = d.plusDays(1);
        }
        return d;
    }

    private ChartSeries buildAggregated(List<PriceHistory> history, String unit) {
        Map<String, PriceHistory> lastByBucket = new LinkedHashMap<>();
        for (PriceHistory p : history) {
            String key = bucketKey(p.getTradeDate(), unit);
            lastByBucket.put(key, p);
        }
        List<ChartPoint> all = lastByBucket.entrySet().stream()
                .map(e -> ChartPoint.builder()
                        .label(formatBucket(e.getValue().getTradeDate(), unit))
                        .close(e.getValue().getClosePrice())
                        .build())
                .collect(Collectors.toList());

        int limit = switch (unit) {
            case "month" -> 12;
            case "quarter" -> 8;
            default -> 26;
        };
        int start = Math.max(0, all.size() - limit);
        return ChartSeries.builder()
                .range(unit.equals("week") ? "weekly" : unit + "ly")
                .points(all.subList(start, all.size()))
                .build();
    }

    private String bucketKey(LocalDate d, String unit) {
        return switch (unit) {
            case "month" -> d.getYear() + "-" + String.format("%02d", d.getMonthValue());
            case "quarter" -> d.getYear() + "-Q" + d.get(IsoFields.QUARTER_OF_YEAR);
            default -> {
                int week = d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
                int year = d.get(IsoFields.WEEK_BASED_YEAR);
                yield year + "-W" + String.format("%02d", week);
            }
        };
    }

    private String formatBucket(LocalDate lastTradingDay, String unit) {
        return switch (unit) {
            case "month" -> lastTradingDay.format(DateTimeFormatter.ofPattern("yyyy-MM"));
            case "quarter" -> lastTradingDay.getYear() + " Q" + lastTradingDay.get(IsoFields.QUARTER_OF_YEAR);
            default -> lastTradingDay.format(DateTimeFormatter.ofPattern("MM-dd"));
        };
    }

    public List<PriceHistory> recentDays(String code, int days) {
        List<PriceHistory> all = priceHistoryRepository.findByStockCodeOrderByTradeDateAsc(code);
        int start = Math.max(0, all.size() - days);
        return all.subList(start, all.size());
    }
}
