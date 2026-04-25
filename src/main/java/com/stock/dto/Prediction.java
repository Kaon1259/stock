package com.stock.dto;

import lombok.*;

import java.util.List;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Prediction {
    private String recommendation;
    private String headline;
    private String summary;
    private InvestmentPlan investmentPlan;
    private List<String> reasons;
    private List<String> warnings;
    private List<String> monitorPoints;
    private List<ScoreItem> scores;
    private String confidence;
}
