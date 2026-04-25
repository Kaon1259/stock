package com.stock.dto;

import lombok.*;

import java.util.List;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class ChartSeries {
    private String range;
    private List<ChartPoint> points;
    private List<ChartPoint> forecast;
}
