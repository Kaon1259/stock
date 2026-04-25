package com.stock.repository;

import com.stock.domain.AnalystReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AnalystReportRepository extends JpaRepository<AnalystReport, Long> {
    List<AnalystReport> findByStockCodeOrderByPublishedAtDesc(String stockCode);
    Optional<AnalystReport> findByStockCodeAndExternalId(String stockCode, String externalId);

    @Modifying
    @Query("DELETE FROM AnalystReport r WHERE r.stockCode = :code")
    int deleteAllByStockCode(@Param("code") String code);
}
