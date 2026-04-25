package com.stock.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

@Entity
@Table(name = "analyst_reports",
        uniqueConstraints = @UniqueConstraint(columnNames = {"stock_code", "external_id"}),
        indexes = @Index(name = "idx_report_code_date", columnList = "stock_code,publishedAt"))
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class AnalystReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stock_code", nullable = false, length = 16)
    private String stockCode;

    @Column(name = "external_id", length = 32)
    private String externalId;

    @Column(nullable = false, length = 64)
    private String firmName;

    @Column(nullable = false, length = 256)
    private String title;

    @Column(nullable = false)
    private LocalDate publishedAt;
}
