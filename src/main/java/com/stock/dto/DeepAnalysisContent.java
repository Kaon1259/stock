package com.stock.dto;

import lombok.*;

import java.util.List;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class DeepAnalysisContent {
    private String recommendation;
    private String headline;
    private String oneLine;
    private String buyPriceHint;
    private String holdPeriod;
    private String companyOverview;
    private List<String> businessHighlights;
    private String earningsTrend;
    private String marketEnvironment;
    private List<String> risks;
    private String finalAdvice;
    private String generatedAt;
}
