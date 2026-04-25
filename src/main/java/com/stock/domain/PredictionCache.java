package com.stock.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "prediction_cache",
        uniqueConstraints = @UniqueConstraint(columnNames = {"stock_code"}),
        indexes = @Index(name = "idx_pred_code_expires", columnList = "stock_code,expires_at"))
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class PredictionCache {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stock_code", nullable = false, length = 16)
    private String stockCode;

    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String contentJson;

    @Column(name = "generated_at", nullable = false)
    private LocalDateTime generatedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;
}
