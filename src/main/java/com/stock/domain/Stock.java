package com.stock.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "stocks")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Stock {

    @Id
    @Column(length = 16)
    private String code;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(length = 32)
    private String market;

    @Column(length = 64)
    private String sector;

    @Enumerated(EnumType.STRING)
    @Column(length = 16, nullable = false)
    @Builder.Default
    private AssetType type = AssetType.STOCK;

    public enum AssetType { STOCK, ETF }
}
