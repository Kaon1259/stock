package com.stock.dto;

import lombok.*;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class ChartPoint {
    private String label;
    private long close;
}
