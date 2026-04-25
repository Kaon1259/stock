package com.stock.service;

import com.stock.domain.AnalystReport;
import com.stock.domain.StockConsensus;
import com.stock.dto.AnalystConsensus;
import com.stock.repository.AnalystReportRepository;
import com.stock.repository.StockConsensusRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AnalystService {

    private final StockConsensusRepository consensusRepository;
    private final AnalystReportRepository reportRepository;

    public AnalystConsensus consensusFor(String stockCode, long currentPrice) {
        Optional<StockConsensus> consensusOpt = consensusRepository.findByStockCode(stockCode);
        List<AnalystReport> reports = reportRepository.findByStockCodeOrderByPublishedAtDesc(stockCode);

        if (consensusOpt.isEmpty() && reports.isEmpty()) {
            return AnalystConsensus.builder().hasData(false).reports(List.of()).build();
        }

        Long target = consensusOpt.map(StockConsensus::getPriceTargetMean).orElse(null);
        Double opinion = consensusOpt.map(StockConsensus::getOpinionMean).orElse(null);

        Double upside = null;
        if (target != null && currentPrice > 0) {
            upside = Math.round((target - currentPrice) * 10000.0 / currentPrice) / 100.0;
        }

        return AnalystConsensus.builder()
                .hasData(true)
                .averageTarget(target)
                .opinionMean(opinion)
                .opinionLabel(opinionLabel(opinion))
                .opinionEmoji(opinionEmoji(opinion))
                .upsidePercent(upside)
                .calculatedAt(consensusOpt.map(StockConsensus::getCalculatedAt).orElse(null))
                .reports(reports)
                .build();
    }

    private String opinionLabel(Double mean) {
        if (mean == null) return "정보 없음";
        if (mean >= 4.5) return "적극매수";
        if (mean >= 3.5) return "매수 추천";
        if (mean >= 2.5) return "중립";
        if (mean >= 1.5) return "매도 권고";
        return "적극매도";
    }

    private String opinionEmoji(Double mean) {
        if (mean == null) return "❔";
        if (mean >= 3.5) return "🟢";
        if (mean >= 2.5) return "🟡";
        return "🔴";
    }
}
