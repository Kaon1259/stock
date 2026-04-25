package com.stock.dto;

import lombok.*;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class WatchlistSignal {
    private String code;
    private String signal;
    private String label;
    private String emoji;
    private String reason;
}
