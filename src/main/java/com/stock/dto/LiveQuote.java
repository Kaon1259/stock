package com.stock.dto;

import lombok.*;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class LiveQuote {
    private String code;
    private String name;
    private long currentPrice;
    private long previousClose;
    private long change;
    private double changeRate;
    private long volume;
    private long timestamp;
}
