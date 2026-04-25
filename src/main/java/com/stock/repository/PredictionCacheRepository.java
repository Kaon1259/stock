package com.stock.repository;

import com.stock.domain.PredictionCache;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PredictionCacheRepository extends JpaRepository<PredictionCache, Long> {
    Optional<PredictionCache> findByStockCode(String stockCode);

    @Modifying
    @Query("DELETE FROM PredictionCache p WHERE p.stockCode = :code")
    int deleteAllByStockCode(@Param("code") String code);
}
