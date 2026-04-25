package com.stock.repository;

import com.stock.domain.AnalystTarget;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AnalystTargetRepository extends JpaRepository<AnalystTarget, Long> {
    List<AnalystTarget> findByStockCodeOrderByPublishedAtDesc(String stockCode);
    long countByStockCode(String stockCode);
}
