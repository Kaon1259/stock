package com.stock.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "deep_analyses",
        uniqueConstraints = @UniqueConstraint(columnNames = {"stock_code", "analysis_date"}),
        indexes = @Index(name = "idx_deep_code_date", columnList = "stock_code,analysis_date"))
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class DeepAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stock_code", nullable = false, length = 16)
    private String stockCode;

    @Column(name = "analysis_date", nullable = false)
    private LocalDate analysisDate;

    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String contentJson;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() { if (createdAt == null) createdAt = LocalDateTime.now(); }
}
