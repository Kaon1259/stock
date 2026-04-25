package com.stock.dto;

import lombok.*;

import java.util.List;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class InvestmentPlan {
    private List<String> buyPlan;
    private List<String> sellPlan;
    private String stopLoss;
    private String positionSize;
    private String holdPeriod;
    private String entryStrategy;
}
