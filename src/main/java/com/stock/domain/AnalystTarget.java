package com.stock.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

@Entity
@Table(name = "analyst_targets",
        indexes = @Index(name = "idx_analyst_code", columnList = "stock_code,publishedAt"))
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class AnalystTarget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stock_code", nullable = false, length = 16)
    private String stockCode;

    @Column(nullable = false, length = 64)
    private String firmName;

    @Column(nullable = false)
    private long targetPrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Opinion opinion;

    @Column(nullable = false)
    private LocalDate publishedAt;

    public enum Opinion {
        STRONG_BUY("적극매수"),
        BUY("매수"),
        HOLD("중립"),
        SELL("매도");

        private final String label;
        Opinion(String label) { this.label = label; }
        public String getLabel() { return label; }
    }
}
