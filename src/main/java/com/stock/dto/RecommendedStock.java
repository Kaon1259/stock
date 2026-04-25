package com.stock.dto;

import lombok.*;

import java.util.List;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class RecommendedStock {
    private String code;
    private String name;
    private String sector;
    private long currentPrice;
    private double changeRate;
    private String tag;
    private String summary;
    private List<String> reasons;
    private List<String> warnings;
}
