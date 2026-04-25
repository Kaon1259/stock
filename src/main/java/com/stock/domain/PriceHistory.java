package com.stock.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

@Entity
@Table(name = "price_history",
        uniqueConstraints = @UniqueConstraint(columnNames = {"stock_code", "trade_date"}),
        indexes = @Index(name = "idx_price_code_date", columnList = "stock_code,trade_date"))
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class PriceHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stock_code", nullable = false, length = 16)
    private String stockCode;

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    private long openPrice;
    private long highPrice;
    private long lowPrice;
    private long closePrice;
    private long volume;
}
