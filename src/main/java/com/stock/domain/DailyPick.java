package com.stock.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "daily_picks",
        uniqueConstraints = @UniqueConstraint(columnNames = {"pick_date"}))
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class DailyPick {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pick_date", nullable = false)
    private LocalDate pickDate;

    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String contentJson;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() { if (createdAt == null) createdAt = LocalDateTime.now(); }
}
