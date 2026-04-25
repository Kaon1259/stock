package com.stock.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class NaverFinanceClient {

    private static final String UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36";
    private static final DateTimeFormatter NAVER_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter NAVER_DATETIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final ObjectMapper mapper = new ObjectMapper();

    private final WebClient mClient = WebClient.builder()
            .baseUrl("https://m.stock.naver.com")
            .defaultHeader("User-Agent", UA)
            .defaultHeader("Referer", "https://m.stock.naver.com")
            .build();

    private final WebClient apiClient = WebClient.builder()
            .baseUrl("https://api.stock.naver.com")
            .defaultHeader("User-Agent", UA)
            .defaultHeader("Referer", "https://m.stock.naver.com")
            .build();

    public Snapshot fetchSnapshot(String code) {
        IntegrationResponse resp = fetchIntegration(code);
        return resp == null ? null : resp.snapshot;
    }

    public IntegrationResponse fetchIntegration(String code) {
        try {
            String body = mClient.get()
                    .uri("/api/stock/{code}/integration", code)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(15))
                    .block();
            JsonNode root = mapper.readTree(body);
            String stockName = root.path("stockName").asText("");

            long currentPrice = 0;
            long previousClose = 0;
            long volume = 0;
            String marketValue = "";
            String foreignRate = "";

            JsonNode totalInfos = root.path("totalInfos");
            for (JsonNode item : totalInfos) {
                String c = item.path("code").asText();
                String v = item.path("value").asText();
                switch (c) {
                    case "lastClosePrice" -> previousClose = parseLong(v);
                    case "accumulatedTradingVolume" -> volume = parseLong(v);
                    case "marketValue" -> marketValue = v;
                    case "foreignRate" -> foreignRate = v;
                }
            }
            JsonNode dealTrend = root.path("dealTrendInfos").path(0);
            if (!dealTrend.isMissingNode()) {
                currentPrice = dealTrend.path("closePrice").asLong(0);
            }
            if (currentPrice == 0) {
                JsonNode close = findInfo(totalInfos, "closePrice");
                if (close != null) currentPrice = parseLong(close.path("value").asText());
            }
            if (currentPrice == 0) currentPrice = previousClose;

            Snapshot snap = new Snapshot(code, stockName, currentPrice, previousClose, volume, marketValue, foreignRate);

            ConsensusInfo consensus = null;
            JsonNode cInfo = root.path("consensusInfo");
            if (!cInfo.isMissingNode() && !cInfo.isNull()) {
                Long target = parseNullableLong(cInfo.path("priceTargetMean").asText(""));
                Double opinion = parseNullableDouble(cInfo.path("recommMean").asText(""));
                LocalDate calc = parseLocalDate(cInfo.path("createDate").asText(""));
                if (target != null || opinion != null) {
                    consensus = new ConsensusInfo(target, opinion, calc);
                }
            }

            List<Research> researches = new ArrayList<>();
            for (JsonNode r : root.path("researches")) {
                String externalId = r.path("id").asText("");
                String firm = r.path("bnm").asText("");
                String title = r.path("tit").asText("");
                LocalDate published = parseLocalDate(r.path("wdt").asText(""));
                if (firm.isEmpty() || title.isEmpty()) continue;
                researches.add(new Research(externalId, firm, title, published));
            }

            return new IntegrationResponse(snap, consensus, researches);
        } catch (Exception e) {
            log.warn("[Naver] integration 실패 {}: {}", code, e.getMessage());
            return null;
        }
    }

    private Long parseNullableLong(String s) {
        long v = parseLong(s);
        return v == 0 && (s == null || s.isEmpty()) ? null : (v == 0 ? null : v);
    }

    private Double parseNullableDouble(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return Double.parseDouble(s.replace(",", "")); } catch (NumberFormatException e) { return null; }
    }

    private LocalDate parseLocalDate(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            if (s.contains("-")) return LocalDate.parse(s);
            if (s.length() == 8) return LocalDate.parse(s, NAVER_DATE);
        } catch (Exception ignore) {}
        return null;
    }

    public List<DailyOhlcv> fetchDailyHistory(String code, int recentDays) {
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays((long) recentDays + 100);
        return fetchDailyHistory(code, from, to);
    }

    public List<DailyOhlcv> fetchDailyHistory(String code, LocalDate from, LocalDate to) {
        try {
            String start = from.atStartOfDay().format(NAVER_DATETIME);
            String end = to.atTime(23, 59, 59).format(NAVER_DATETIME);

            String body = apiClient.get()
                    .uri(uri -> uri.path("/chart/domestic/item/{code}/day")
                            .queryParam("startDateTime", start)
                            .queryParam("endDateTime", end)
                            .build(code))
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(20))
                    .block();

            JsonNode arr = mapper.readTree(body);
            List<DailyOhlcv> rows = new ArrayList<>();
            for (JsonNode n : arr) {
                String dateStr = n.path("localDate").asText();
                if (dateStr.isEmpty()) continue;
                LocalDate date = LocalDate.parse(dateStr, NAVER_DATE);
                rows.add(new DailyOhlcv(
                        date,
                        Math.round(n.path("openPrice").asDouble(0)),
                        Math.round(n.path("highPrice").asDouble(0)),
                        Math.round(n.path("lowPrice").asDouble(0)),
                        Math.round(n.path("closePrice").asDouble(0)),
                        n.path("accumulatedTradingVolume").asLong(0)
                ));
            }
            return rows;
        } catch (Exception e) {
            log.warn("[Naver] daily history 실패 {}: {}", code, e.getMessage());
            return List.of();
        }
    }

    private JsonNode findInfo(JsonNode totalInfos, String code) {
        for (JsonNode item : totalInfos) {
            if (item.path("code").asText().equals(code)) return item;
        }
        return null;
    }

    private long parseLong(String s) {
        if (s == null || s.isEmpty()) return 0;
        String cleaned = s.replaceAll("[^0-9-]", "");
        if (cleaned.isEmpty() || cleaned.equals("-")) return 0;
        try { return Long.parseLong(cleaned); } catch (NumberFormatException e) { return 0; }
    }

    @Getter
    public static class Snapshot {
        private final String code;
        private final String name;
        private final long currentPrice;
        private final long previousClose;
        private final long volume;
        private final String marketValue;
        private final String foreignRate;

        public Snapshot(String code, String name, long currentPrice, long previousClose,
                        long volume, String marketValue, String foreignRate) {
            this.code = code; this.name = name; this.currentPrice = currentPrice;
            this.previousClose = previousClose; this.volume = volume;
            this.marketValue = marketValue; this.foreignRate = foreignRate;
        }
    }

    public record DailyOhlcv(LocalDate date, long open, long high, long low, long close, long volume) {}

    public record IntegrationResponse(Snapshot snapshot, ConsensusInfo consensus, List<Research> researches) {}

    public record ConsensusInfo(Long priceTargetMean, Double opinionMean, LocalDate calculatedAt) {}

    public record Research(String externalId, String firmName, String title, LocalDate publishedAt) {}
}
