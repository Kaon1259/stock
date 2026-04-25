package com.stock.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.domain.AnalystReport;
import com.stock.domain.DeepAnalysis;
import com.stock.domain.NewsItem;
import com.stock.domain.PriceHistory;
import com.stock.domain.StockConsensus;
import com.stock.dto.DeepAnalysisContent;
import com.stock.dto.StockSummary;
import com.stock.repository.AnalystReportRepository;
import com.stock.repository.DeepAnalysisRepository;
import com.stock.repository.StockConsensusRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeepAnalysisService {

    private final DeepAnalysisRepository repository;
    private final StockPriceService stockPriceService;
    private final NewsService newsService;
    private final StockConsensusRepository consensusRepository;
    private final AnalystReportRepository reportRepository;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${claude.api-key}")
    private String apiKey;

    @Value("${claude.model}")
    private String model;

    @Transactional
    public DeepAnalysisContent getOrGenerate(StockSummary summary) {
        LocalDate today = LocalDate.now();
        Optional<DeepAnalysis> cached = repository.findByStockCodeAndAnalysisDate(summary.getCode(), today);
        if (cached.isPresent()) {
            log.info("[DeepAnalysis] 캐시 히트: {} ({})", summary.getCode(), today);
            return parse(cached.get().getContentJson(), cached.get().getCreatedAt());
        }

        DeepAnalysisContent content = generate(summary);
        try {
            String json = mapper.writeValueAsString(content);
            repository.save(DeepAnalysis.builder()
                    .stockCode(summary.getCode())
                    .analysisDate(today)
                    .contentJson(json)
                    .build());
        } catch (Exception e) {
            log.warn("[DeepAnalysis] 캐시 저장 실패: {}", e.getMessage());
        }
        return content;
    }

    private DeepAnalysisContent generate(StockSummary summary) {
        try {
            String prompt = buildPrompt(summary);
            Map<String, Object> body = Map.of(
                    "model", model,
                    "max_tokens", 3000,
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
                    .timeout(Duration.ofSeconds(120))
                    .block();

            DeepAnalysisContent content = parseClaude(response);
            content.setGeneratedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
            return content;
        } catch (Exception e) {
            log.warn("[DeepAnalysis] Claude 호출 실패: {}", e.getMessage());
            return DeepAnalysisContent.builder()
                    .recommendation("HOLD")
                    .headline("⏸️ 분석 일시 오류")
                    .oneLine("AI 호출에 실패했습니다. 잠시 후 다시 시도해 주세요.")
                    .companyOverview("")
                    .businessHighlights(List.of())
                    .earningsTrend("")
                    .marketEnvironment("")
                    .risks(List.of())
                    .finalAdvice("")
                    .generatedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
                    .build();
        }
    }

    private String buildPrompt(StockSummary s) {
        StringBuilder priceBlock = new StringBuilder();
        for (PriceHistory p : stockPriceService.recentDays(s.getCode(), 30)) {
            priceBlock.append(String.format("- %s 종가 %,d원%n", p.getTradeDate(), p.getClosePrice()));
        }

        StringBuilder stockNewsBlock = new StringBuilder();
        for (NewsItem n : newsService.stockNews(s.getCode())) {
            stockNewsBlock.append("- ").append(n.getTitle()).append(" — ").append(n.getSummary()).append("\n");
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
                analystBlock.append(String.format("- 평균 투자의견: %.2f / 5.0%n", consensus.getOpinionMean()));
            }
        }
        for (AnalystReport r : reports) {
            analystBlock.append(String.format("- [%s] %s (%s)%n",
                    r.getFirmName(), r.getTitle(), r.getPublishedAt()));
        }

        return """
            너는 50대 이상 비전문 개인 투자자에게 친절히 조언하는 한국 주식 자문가야.
            전문용어를 절대 쓰지 말고(필요하면 풀어쓰기), 비유를 적극 사용해서 누구나 알아듣게 깊이 분석해.
            한 문장은 30자 정도로 짧게. 한 단락은 3~4문장 정도.

            [종목]
            %s (%s) — %s, %s 섹터
            현재가 %,d원, 등락 %+d원 (%.2f%%)

            [최근 30일 종가 추이]
            %s
            [종목 관련 뉴스]
            %s
            [시장 / 매크로 뉴스]
            %s
            [애널리스트 컨센서스]
            %s

            === 출력 규칙 ===
            아래 JSON으로만 응답해. 마크다운/코드블록 절대 금지. 큰따옴표만 사용.
            모든 항목은 한국어로. 전문용어 금지(쓸 거면 괄호로 풀어쓰기).

            {
              "recommendation": "BUY",
              "headline": "👍 사도 괜찮은 편입니다",
              "oneLine": "전체를 한 문장으로 요약 (40자 이내, 비유 활용 환영)",
              "buyPriceHint": "BUY일 때만: '74,000원 이하 분할매수' 형태. 아니면 빈 문자열",
              "holdPeriod": "BUY일 때만: '1~3개월 보유 권장' 형태. 아니면 빈 문자열",
              "companyOverview": "이 회사는 무슨 회사인지 3~4문장. 어머니가 들어도 알 정도로 풀어서. (예: '삼성전자는 휴대폰, TV, 그리고 컴퓨터의 두뇌인 반도체를 만드는 회사예요. 그중에서도 메모리 반도체는 세계 1위입니다.')",
              "businessHighlights": ["요즘 회사가 잘하고 있는 점 1 (50자 이내)", "잘하고 있는 점 2", "잘하고 있는 점 3"],
              "earningsTrend": "매출과 이익 흐름을 풀어서 설명. 비유 활용. (3~4문장)",
              "marketEnvironment": "시장과 매크로(환율, 미국 증시, 금리 등) 환경을 풀어서 설명. (3~4문장)",
              "risks": ["조심할 점 1 (노후 자금 관점)", "조심할 점 2", "조심할 점 3"],
              "finalAdvice": "최종 권고. 구체적인 행동 지침 포함 — 한 번에 다 사지 말고 분할매수, 손절선, 보유 기간 등. (4~5문장)"
            }
            """.formatted(
                s.getName(), s.getCode(), s.getMarket(), s.getSector() == null ? "" : s.getSector(),
                s.getCurrentPrice(), s.getChange(), s.getChangeRate(),
                priceBlock.toString(),
                stockNewsBlock.length() == 0 ? "(없음)\n" : stockNewsBlock.toString(),
                macroBlock.toString(),
                analystBlock.length() == 0 ? "(없음)\n" : analystBlock.toString()
        );
    }

    private DeepAnalysisContent parseClaude(String response) throws Exception {
        JsonNode root = mapper.readTree(response);
        String text = root.path("content").path(0).path("text").asText("").trim();
        if (text.startsWith("```")) {
            text = text.replaceAll("^```(?:json)?", "").replaceAll("```$", "").trim();
        }
        int lb = text.indexOf('{');
        int rb = text.lastIndexOf('}');
        if (lb >= 0 && rb > lb) text = text.substring(lb, rb + 1);
        JsonNode j = mapper.readTree(text);

        List<String> highlights = new ArrayList<>();
        if (j.has("businessHighlights")) j.get("businessHighlights").forEach(n -> highlights.add(n.asText()));
        List<String> risks = new ArrayList<>();
        if (j.has("risks")) j.get("risks").forEach(n -> risks.add(n.asText()));

        return DeepAnalysisContent.builder()
                .recommendation(j.path("recommendation").asText("HOLD"))
                .headline(j.path("headline").asText(""))
                .oneLine(j.path("oneLine").asText(""))
                .buyPriceHint(j.path("buyPriceHint").asText(""))
                .holdPeriod(j.path("holdPeriod").asText(""))
                .companyOverview(j.path("companyOverview").asText(""))
                .businessHighlights(highlights)
                .earningsTrend(j.path("earningsTrend").asText(""))
                .marketEnvironment(j.path("marketEnvironment").asText(""))
                .risks(risks)
                .finalAdvice(j.path("finalAdvice").asText(""))
                .build();
    }

    private DeepAnalysisContent parse(String json, LocalDateTime createdAt) {
        try {
            DeepAnalysisContent content = mapper.readValue(json, DeepAnalysisContent.class);
            if (content.getGeneratedAt() == null || content.getGeneratedAt().isEmpty()) {
                content.setGeneratedAt(createdAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
            }
            return content;
        } catch (Exception e) {
            log.warn("[DeepAnalysis] 캐시 파싱 실패: {}", e.getMessage());
            return DeepAnalysisContent.builder()
                    .recommendation("HOLD")
                    .headline("캐시 읽기 오류")
                    .oneLine("저장된 분석을 불러오지 못했습니다.")
                    .build();
        }
    }
}
