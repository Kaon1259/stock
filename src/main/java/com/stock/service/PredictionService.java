package com.stock.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.domain.AnalystReport;
import com.stock.domain.NewsItem;
import com.stock.domain.PredictionCache;
import com.stock.domain.PriceHistory;
import com.stock.domain.StockConsensus;
import com.stock.dto.InvestmentPlan;
import com.stock.dto.Prediction;
import com.stock.dto.ScoreItem;
import com.stock.dto.StockSummary;
import com.stock.repository.AnalystReportRepository;
import com.stock.repository.PredictionCacheRepository;
import com.stock.repository.StockConsensusRepository;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class PredictionService {

    private final StockPriceService stockPriceService;
    private final NewsService newsService;
    private final StockConsensusRepository consensusRepository;
    private final AnalystReportRepository reportRepository;
    private final PredictionCacheRepository predictionCacheRepository;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final Duration PREDICTION_TTL = Duration.ofHours(6);

    @Value("${claude.api-key}")
    private String apiKey;

    @Value("${claude.model}")
    private String model;

    @Value("${claude.max-tokens:1500}")
    private int maxTokens;

    private WebClient client() {
        return WebClient.builder()
                .baseUrl("https://api.anthropic.com")
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", "2023-06-01")
                .defaultHeader("content-type", "application/json")
                .build();
    }

    @Transactional
    public Prediction predict(StockSummary summary) {
        LocalDateTime now = LocalDateTime.now();
        Optional<PredictionCache> cached = predictionCacheRepository.findByStockCode(summary.getCode());
        if (cached.isPresent() && cached.get().getExpiresAt().isAfter(now)) {
            try {
                Prediction p = mapper.readValue(cached.get().getContentJson(), Prediction.class);
                log.debug("[Prediction] 캐시 히트 {} (만료까지 {}분)",
                        summary.getCode(),
                        Duration.between(now, cached.get().getExpiresAt()).toMinutes());
                return p;
            } catch (Exception e) {
                log.warn("[Prediction] 캐시 파싱 실패 — 재생성: {}", e.getMessage());
            }
        }

        try {
            String prompt = buildPrompt(summary);
            Map<String, Object> body = Map.of(
                    "model", model,
                    "max_tokens", maxTokens,
                    "messages", List.of(Map.of("role", "user", "content", prompt))
            );

            String response = client().post()
                    .uri("/v1/messages")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(60))
                    .block();

            Prediction prediction = parse(response);
            try {
                String json = mapper.writeValueAsString(prediction);
                PredictionCache entry = cached.orElseGet(() -> PredictionCache.builder()
                        .stockCode(summary.getCode()).build());
                entry.setContentJson(json);
                entry.setGeneratedAt(now);
                entry.setExpiresAt(now.plus(PREDICTION_TTL));
                predictionCacheRepository.save(entry);
                log.info("[Prediction] {} 새 분석 캐싱 (6시간)", summary.getCode());
            } catch (Exception ce) {
                log.warn("[Prediction] 캐시 저장 실패: {}", ce.getMessage());
            }
            return prediction;
        } catch (Exception e) {
            log.warn("Claude 추천 호출 실패: {}", e.getMessage());
            return Prediction.builder()
                    .recommendation("HOLD")
                    .headline("⏸️ 잠시 기다려 주세요")
                    .summary("AI 분석 중 일시 오류가 났어요. 잠시 후 새로고침해 주세요.")
                    .reasons(List.of())
                    .warnings(List.of())
                    .scores(List.of())
                    .confidence("LOW")
                    .build();
        }
    }

    @Transactional
    public void invalidate(String stockCode) {
        predictionCacheRepository.deleteAllByStockCode(stockCode);
    }

    private String buildPrompt(StockSummary s) {
        List<PriceHistory> recent = stockPriceService.recentDays(s.getCode(), 14);
        StringBuilder priceBlock = new StringBuilder();
        for (PriceHistory p : recent) {
            priceBlock.append(String.format("- %s 종가 %,d원 (거래량 %,d)%n",
                    p.getTradeDate(), p.getClosePrice(), p.getVolume()));
        }

        StringBuilder stockNewsBlock = new StringBuilder();
        for (NewsItem n : newsService.stockNews(s.getCode())) {
            stockNewsBlock.append("- ").append(n.getTitle())
                    .append(" — ").append(n.getSummary() == null ? "" : n.getSummary()).append("\n");
        }

        StringBuilder macroBlock = new StringBuilder();
        for (NewsItem n : newsService.macroNews()) {
            macroBlock.append("- [국내] ").append(n.getTitle()).append(" — ").append(n.getSummary()).append("\n");
        }
        for (NewsItem n : newsService.globalNews()) {
            macroBlock.append("- [해외] ").append(n.getTitle()).append(" — ").append(n.getSummary()).append("\n");
        }

        StringBuilder analystBlock = new StringBuilder();
        StockConsensus consensus = consensusRepository.findByStockCode(s.getCode()).orElse(null);
        List<AnalystReport> reports = reportRepository.findByStockCodeOrderByPublishedAtDesc(s.getCode());
        if (consensus != null && consensus.getPriceTargetMean() != null) {
            double upside = s.getCurrentPrice() == 0 ? 0
                    : (consensus.getPriceTargetMean() - s.getCurrentPrice()) * 100.0 / s.getCurrentPrice();
            analystBlock.append(String.format("- 평균 목표주가: %,d원 (현재가 대비 %+.1f%%)%n",
                    consensus.getPriceTargetMean(), upside));
            if (consensus.getOpinionMean() != null) {
                analystBlock.append(String.format("- 평균 투자의견: %.2f / 5.0 (5=적극매수, 1=적극매도)%n",
                        consensus.getOpinionMean()));
            }
        }
        if (!reports.isEmpty()) {
            analystBlock.append("- 최근 증권사 리포트:\n");
            int max = Math.min(5, reports.size());
            for (int i = 0; i < max; i++) {
                AnalystReport r = reports.get(i);
                analystBlock.append(String.format("  · [%s] %s (%s)%n",
                        r.getFirmName(), r.getTitle(), r.getPublishedAt()));
            }
        }
        if (analystBlock.length() == 0) analystBlock.append("(컨센서스 정보 없음 — ETF 등)\n");

        return """
            너는 50대 이상 비전문 개인 투자자에게 친절히 조언하는 한국 주식 자문가야.
            전문용어를 절대 쓰지 말고, 비유를 적극 사용해서 누구나 알아듣게 설명해.
            판단의 핵심은: 이 종목을 지금 사도 되는가? 기다려야 하는가? 피해야 하는가?

            [종목]
            %s (%s)
            현재가: %,d원, 등락: %+d원 (%.2f%%)

            [최근 14일 종가 추이]
            %s
            [종목 관련 뉴스]
            %s
            [시장 전반 / 해외 매크로 뉴스]
            %s
            [애널리스트 컨센서스]
            %s

            === 출력 규칙 ===
            1. recommendation: BUY / HOLD / SELL
            2. headline: 신호등 이모지 + 한 줄 결론
            3. summary: 30~50자, 비유로 이해하기 쉽게
            4. reasons: 3~4개, 각 50자 이내, 비유 활용, 전문용어 금지
            5. warnings: 1~3개 위험 신호 (노후 자금 관점)
            6. monitorPoints: 1~3개. 투자 후/투자 전에 **계속 지켜봐야 할 이슈** (예: "다음 분기 실적 발표일", "원/달러 환율 1,400원 돌파 여부", "미국 연준 5월 금리 결정")
            7. scores: 정확히 4개 (실적/차트/가격/환경), 별점 1~5
            8. confidence: HIGH / MEDIUM / LOW

            === investmentPlan (가장 중요!) ===
            - **BUY일 때**: buyPlan/sellPlan/stopLoss/positionSize/holdPeriod/entryStrategy 모두 채우기
            - **HOLD일 때**: buyPlan은 빈 배열, sellPlan/stopLoss는 빈 문자열, positionSize/entryStrategy로 "지금은 매수 보류" 같은 안내
            - **SELL일 때**: 보유 중인 사람용 sellPlan만 채우고 나머지 빈 값

            buyPlan 작성 규칙 (BUY일 때):
            - 정확히 3단계로 분할매수 가격 제시
            - 형식: "1차: {가격}원 부근 (전체 매수 예정금액의 {%%})"
            - 예: ["1차: 71,000원 부근 (전체의 40%%)", "2차: 67,000원 부근 (전체의 30%%)", "3차: 63,000원 부근 (전체의 30%%)"]

            sellPlan 작성 규칙 (BUY/SELL일 때):
            - 1차/2차 익절 가격 (BUY일 때) 또는 매도 권고가 (SELL일 때)
            - 예 (BUY): ["1차 익절: 86,000원에 보유분의 50%%", "2차 익절: 95,000원에 나머지"]
            - 예 (SELL): ["현재가 부근에서 분할 매도 권장 (10%% 단위로 5회)"]

            stopLoss: "63,000원 — 이 아래로 떨어지면 손절 (현재가 대비 -12%%)"
            positionSize: "전체 자산의 5~10%% 이내 권장 (노후 자금이라면 5%% 미만)"
            holdPeriod: "3~6개월 보유 권장"
            entryStrategy: 한 문장 — 분할매수 이유와 타이밍 (예: "한 번에 다 사지 마세요. 단기 변동성이 크니 가격이 내려올 때마다 나눠서 사는 게 안전합니다.")

            가격 산정 기준:
            - buyPlan 1차: 현재가 또는 약간 아래 (안전마진 5%% 정도)
            - 2차: 1차 -5%%, 3차: 2차 -5%% 정도
            - sellPlan 1차 익절: 평균 매수가 대비 +15~25%%
            - 2차 익절: 평균 매수가 대비 +35~50%%
            - stopLoss: 평균 매수가 대비 -10~15%%
            - 위 비율은 종목 변동성에 따라 조정 (안정주는 좁게, 변동성 큰 종목은 넓게)

            아래 JSON으로만 응답해. 마크다운/코드블록 금지. 반드시 큰따옴표 사용.
            {
              "recommendation": "BUY",
              "headline": "👍 사도 괜찮은 편입니다",
              "summary": "...",
              "investmentPlan": {
                "buyPlan": ["1차: 71,000원 부근 (전체의 40%%)", "2차: 67,000원 부근 (전체의 30%%)", "3차: 63,000원 부근 (전체의 30%%)"],
                "sellPlan": ["1차 익절: 86,000원에 보유분의 50%%", "2차 익절: 95,000원에 나머지"],
                "stopLoss": "63,000원 — 이 아래로 떨어지면 손절 (-12%%)",
                "positionSize": "전체 자산의 5~10%% 이내 권장",
                "holdPeriod": "3~6개월 보유 권장",
                "entryStrategy": "한 번에 다 사지 마세요. 가격이 내려올 때마다 나눠서 사는 게 안전합니다."
              },
              "reasons": ["...", "...", "..."],
              "warnings": ["..."],
              "monitorPoints": ["다음 분기 실적 발표 (예정일 확인)", "원/달러 환율 1,400원 돌파 여부", "미국 연준 5월 금리 결정"],
              "scores": [
                {"label": "실적", "stars": 4, "comment": "..."},
                {"label": "차트", "stars": 3, "comment": "..."},
                {"label": "가격", "stars": 3, "comment": "..."},
                {"label": "환경", "stars": 4, "comment": "..."}
              ],
              "confidence": "MEDIUM"
            }
            """.formatted(
                s.getName(), s.getCode(),
                s.getCurrentPrice(), s.getChange(), s.getChangeRate(),
                priceBlock.toString(),
                stockNewsBlock.length() == 0 ? "(없음)\n" : stockNewsBlock.toString(),
                macroBlock.toString(),
                analystBlock.toString()
        );
    }

    private Prediction parse(String response) throws Exception {
        JsonNode root = mapper.readTree(response);
        String text = root.path("content").path(0).path("text").asText("").trim();
        if (text.startsWith("```")) {
            text = text.replaceAll("^```(?:json)?", "").replaceAll("```$", "").trim();
        }
        int lb = text.indexOf('{');
        int rb = text.lastIndexOf('}');
        if (lb >= 0 && rb > lb) text = text.substring(lb, rb + 1);

        JsonNode pred = mapper.readTree(text);
        List<String> reasons = stringList(pred, "reasons");
        List<String> warnings = stringList(pred, "warnings");
        List<String> monitor = stringList(pred, "monitorPoints");

        List<ScoreItem> scores = new ArrayList<>();
        if (pred.has("scores")) {
            pred.get("scores").forEach(n -> scores.add(ScoreItem.builder()
                    .label(n.path("label").asText(""))
                    .stars(Math.max(1, Math.min(5, n.path("stars").asInt(3))))
                    .comment(n.path("comment").asText(""))
                    .build()));
        }

        InvestmentPlan plan = null;
        if (pred.has("investmentPlan") && !pred.get("investmentPlan").isNull()) {
            JsonNode p = pred.get("investmentPlan");
            plan = InvestmentPlan.builder()
                    .buyPlan(stringList(p, "buyPlan"))
                    .sellPlan(stringList(p, "sellPlan"))
                    .stopLoss(p.path("stopLoss").asText(""))
                    .positionSize(p.path("positionSize").asText(""))
                    .holdPeriod(p.path("holdPeriod").asText(""))
                    .entryStrategy(p.path("entryStrategy").asText(""))
                    .build();
        }

        return Prediction.builder()
                .recommendation(pred.path("recommendation").asText("HOLD"))
                .headline(pred.path("headline").asText(""))
                .summary(pred.path("summary").asText(""))
                .investmentPlan(plan)
                .reasons(reasons)
                .warnings(warnings)
                .monitorPoints(monitor)
                .scores(scores)
                .confidence(pred.path("confidence").asText("MEDIUM"))
                .build();
    }

    private List<String> stringList(JsonNode node, String field) {
        List<String> out = new ArrayList<>();
        if (node.has(field) && node.get(field).isArray()) {
            node.get(field).forEach(n -> out.add(n.asText()));
        }
        return out;
    }
}
