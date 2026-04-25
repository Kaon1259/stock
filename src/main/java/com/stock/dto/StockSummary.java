package com.stock.dto;

import lombok.*;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class StockSummary {
    private String code;
    private String name;
    private String market;
    private String sector;
    private String type;
    private long currentPrice;
    private long previousClose;
    private long change;
    private double changeRate;
    private long volume;

    public String getChangeSign() {
        if (change > 0) return "+";
        if (change < 0) return "";
        return "";
    }

    public String getChangeClass() {
        if (change > 0) return "up";
        if (change < 0) return "down";
        return "flat";
    }
}
