package com.stock.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.domain.DailyPick;
import com.stock.domain.PriceHistory;
import com.stock.domain.Stock;
import com.stock.domain.StockConsensus;
import com.stock.dto.RecommendedStock;
import com.stock.dto.StockSummary;
import com.stock.repository.StockConsensusRepository;
import com.stock.repository.DailyPickRepository;
import com.stock.repository.PriceHistoryRepository;
import com.stock.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DailyPickService {

    private final StockRepository stockRepository;
    private final PriceHistoryRepository priceHistoryRepository;
    private final StockConsensusRepository consensusRepository;
    private final DailyPickRepository dailyPickRepository;
    private final StockPriceService stockPriceService;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${claude.api-key}")
    private String apiKey;

    @Value("${claude.model}")
    private String model;

    @Transactional
    public List<RecommendedStock> getTodaysPicks() {
        LocalDate today = LocalDate.now();
        Optional<DailyPick> cached = dailyPickRepository.findByPickDate(today);
        if (cached.isPresent()) {
            try {
                return mapper.readValue(cached.get().getContentJson(),
                        new TypeReference<List<RecommendedStock>>() {});
            } catch (Exception e) {
                log.warn("[DailyPick] 캐시 파싱 실패: {}", e.getMessage());
            }
        }

        List<RecommendedStock> picks = generate();
        if (picks.size() < 2) {
            log.warn("[DailyPick] 결과 {}건뿐이라 캐시 저장 스킵 (다음 호출에서 재시도)", picks.size());
            return picks;
        }
        try {
            String json = mapper.writeValueAsString(picks);
            dailyPickRepository.save(DailyPick.builder()
                    .pickDate(today).contentJson(json).build());
        } catch (Exception e) {
            log.warn("[DailyPick] 캐시 저장 실패: {}", e.getMessage());
        }
        return picks;
    }

    private List<RecommendedStock> generate() {
        List<Stock> stocks = stockRepository.findAll().stream()
                .filter(s -> s.getType() == Stock.AssetType.STOCK)
                .toList();

        List<Candidate> ranked = stocks.stream()
                .map(this::scoreCandidate)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingDouble(Candidate::compositeScore).reversed())
                .limit(6)
                .toList();

        if (ranked.isEmpty()) return List.of();

        try {
            return askClaude(ranked);
        } catch (Exception e) {
            log.warn("[DailyPick] Claude 호출 실패, 휴리스틱 결과만 사용: {}", e.getMessage());
            return ranked.stream().limit(3).map(this::toBasicRecommendation).collect(Collectors.toList());
        }
    }

    private Candidate scoreCandidate(Stock stock) {
        List<PriceHistory> history = priceHistoryRepository.findByStockCodeOrderByTradeDateAsc(stock.getCode());
        if (history.size() < 30) return null;

        PriceHistory last = history.get(history.size() - 1);
        PriceHistory fiveDaysAgo = history.get(Math.max(0, history.size() - 6));
        double momentum = (last.getClosePrice() - fiveDaysAgo.getClosePrice()) * 100.0 / fiveDaysAgo.getClosePrice();

        int n = Math.min(20, history.size());
        List<PriceHistory> tail = history.subList(history.size() - n, history.size());
        double mean = tail.stream().mapToLong(PriceHistory::getClosePrice).average().orElse(1);
        double variance = tail.stream()
                .mapToDouble(p -> Math.pow(p.getClosePrice() - mean, 2))
                .average().orElse(0);
        double volatility = Math.sqrt(variance) / mean * 100.0;
        double stability = Math.max(0, 5 - volatility);

        StockConsensus consensus = consensusRepository.findByStockCode(stock.getCode()).orElse(null);
        double upsidePct = 0;
        if (consensus != null && consensus.getPriceTargetMean() != null && last.getClosePrice() > 0) {
            upsidePct = (consensus.getPriceTargetMean() - last.getClosePrice()) * 100.0 / last.getClosePrice();
        }

        double composite = momentum * 0.25 + upsidePct * 0.45 + stability * 4.0;

        return new Candidate(stock, last.getClosePrice(), momentum, upsidePct, volatility, composite);
    }

    private List<RecommendedStock> askClaude(List<Candidate> candidates) throws Exception {
        StringBuilder block = new StringBuilder();
        for (Candidate c : candidates) {
            block.append(String.format("- %s (%s, %s)%n  현재가 %,d원, 5일 모멘텀 %+.2f%%, 컨센서스 상승여력 %+.1f%%, 변동성 %.2f%%%n",
                    c.stock.getName(), c.stock.getCode(),
                    c.stock.getSector() == null ? "기타" : c.stock.getSector(),
                    c.currentPrice, c.momentum, c.upside, c.volatility));
        }

        String prompt = """
            너는 50대 이상 비전문 개인 투자자에게 종목을 추천하는 한국 주식 자문가야.
            아래는 휴리스틱으로 추린 후보 종목들이야. 이 중에서 **오늘 사기에 가장 좋은 3개**를 골라줘.

            기준:
            - 안정성 우선 (변동성이 너무 큰 건 피해)
            - 적당한 상승 여력
            - 섹터 분산 (가능하면 같은 섹터 중복 피하기)

            [후보]
            %s

            === 출력 규칙 ===
            - 전문용어 절대 금지 (PER, ROE, EPS 등). 풀어쓰기.
            - 비유 적극 활용 (예: "외국인 큰손이 줄 서서 사는 모습")
            - 모든 텍스트 한국어, 50대 이상 비전문가 톤
            - tag: 한 단어 (안정형/성장형/배당주/회복중/저평가/대장주 등)
            - summary: 한 문장(40~60자)으로 왜 추천하는지
            - reasons: 3개. 각 30~50자, 핵심 근거를 하나씩
            - warnings: 1~2개. 노후 자금 관점 주의사항

            아래 JSON 배열로만 응답. 마크다운/코드블록 절대 금지:
            [
              {
                "code": "005930",
                "tag": "안정형",
                "summary": "외국인 매수가 꾸준하고 반도체 경기가 회복 중이라 지금 사두면 좋아요",
                "reasons": [
                  "전문가들이 5명 중 4명이 매수 추천하고 있어요",
                  "외국인 큰손들이 5일 연속 사고 있어요",
                  "하반기 실적이 작년보다 크게 좋아질 전망이에요"
                ],
                "warnings": [
                  "단기간에 많이 올랐으니 한 번에 다 사지 말고 나눠서 사세요"
                ]
              }
            ]
            """.formatted(block.toString());

        Map<String, Object> body = Map.of(
                "model", model,
                "max_tokens", 2500,
                "messages", List.of(Map.of("role", "user", "content", prompt))
        );

        String response = WebClient.builder()
                .baseUrl("https://api.anthropic.com")
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", "2023-06-01")
                .defaultHeader("content-type", "application/json")
                .build()
                .post()
                .uri("/v1/messages")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(60))
                .block();

        JsonNode root = mapper.readTree(response);
        String text = root.path("content").path(0).path("text").asText("").trim();
        if (text.startsWith("```")) {
            text = text.replaceAll("^```(?:json)?", "").replaceAll("```$", "").trim();
        }
        int lb = text.indexOf('[');
        int rb = text.lastIndexOf(']');
        if (lb >= 0 && rb > lb) text = text.substring(lb, rb + 1);

        JsonNode arr = mapper.readTree(text);
        List<RecommendedStock> result = new ArrayList<>();
        for (JsonNode item : arr) {
            String code = item.path("code").asText("");
            Candidate c = candidates.stream().filter(x -> x.stock.getCode().equals(code)).findFirst().orElse(null);
            if (c == null) continue;
            StockSummary summary = stockPriceService.buildSummary(c.stock);

            List<String> reasons = new ArrayList<>();
            if (item.has("reasons")) item.get("reasons").forEach(n -> reasons.add(n.asText()));
            List<String> warnings = new ArrayList<>();
            if (item.has("warnings")) item.get("warnings").forEach(n -> warnings.add(n.asText()));

            String summaryText = item.path("summary").asText(item.path("comment").asText(""));

            result.add(RecommendedStock.builder()
                    .code(c.stock.getCode())
                    .name(c.stock.getName())
                    .sector(c.stock.getSector())
                    .currentPrice(summary.getCurrentPrice())
                    .changeRate(summary.getChangeRate())
                    .summary(summaryText)
                    .tag(item.path("tag").asText(""))
                    .reasons(reasons)
                    .warnings(warnings)
                    .build());
        }
        return result;
    }

    private RecommendedStock toBasicRecommendation(Candidate c) {
        StockSummary summary = stockPriceService.buildSummary(c.stock);
        return RecommendedStock.builder()
                .code(c.stock.getCode())
                .name(c.stock.getName())
                .sector(c.stock.getSector())
                .currentPrice(summary.getCurrentPrice())
                .changeRate(summary.getChangeRate())
                .summary("최근 흐름과 전문가 의견이 좋은 편이에요.")
                .tag("주목")
                .reasons(List.of())
                .warnings(List.of())
                .build();
    }

    private record Candidate(Stock stock, long currentPrice, double momentum, double upside,
                             double volatility, double compositeScore) {}
}
