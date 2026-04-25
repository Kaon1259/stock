package com.stock.dto;

import lombok.*;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class ScoreItem {
    private String label;
    private int stars;
    private String comment;
}
