package com.stock.repository;

import com.stock.domain.DeepAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface DeepAnalysisRepository extends JpaRepository<DeepAnalysis, Long> {
    Optional<DeepAnalysis> findByStockCodeAndAnalysisDate(String stockCode, LocalDate date);
}
