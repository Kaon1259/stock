package com.stock.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "stock_consensus")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class StockConsensus {

    @Id
    @Column(length = 16)
    private String stockCode;

    private Long priceTargetMean;

    private Double opinionMean;

    private LocalDate calculatedAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touch() { updatedAt = LocalDateTime.now(); }
}
