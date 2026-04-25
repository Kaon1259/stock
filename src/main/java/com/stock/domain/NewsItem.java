package com.stock.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "news_items",
        indexes = {
                @Index(name = "idx_news_scope", columnList = "scope,scopeKey,publishedAt"),
        })
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class NewsItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Scope scope;

    @Column(length = 32)
    private String scopeKey;

    @Column(nullable = false, length = 256)
    private String title;

    @Column(length = 512)
    private String url;

    @Column(length = 64)
    private String source;

    @Column(length = 1024)
    private String summary;

    @Column(nullable = false)
    private LocalDateTime publishedAt;

    public enum Scope { STOCK, MACRO, GLOBAL }
}
