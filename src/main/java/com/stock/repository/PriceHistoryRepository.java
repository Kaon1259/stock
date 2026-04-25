package com.stock.repository;

import com.stock.domain.PriceHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PriceHistoryRepository extends JpaRepository<PriceHistory, Long> {
    List<PriceHistory> findByStockCodeOrderByTradeDateAsc(String stockCode);
    List<PriceHistory> findByStockCodeAndTradeDateGreaterThanEqualOrderByTradeDateAsc(String stockCode, LocalDate from);
    Optional<PriceHistory> findTopByStockCodeOrderByTradeDateDesc(String stockCode);

    @Modifying
    @Query("DELETE FROM PriceHistory p WHERE p.stockCode = :code")
    int deleteAllByStockCode(@Param("code") String code);
}
