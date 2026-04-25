package com.stock.dto;

import com.stock.domain.AnalystReport;
import lombok.*;

import java.time.LocalDate;
import java.util.List;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class AnalystConsensus {
    private boolean hasData;
    private Long averageTarget;
    private Double opinionMean;
    private String opinionLabel;
    private String opinionEmoji;
    private Double upsidePercent;
    private LocalDate calculatedAt;
    private List<AnalystReport> reports;
}
