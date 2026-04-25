package com.stock.repository;

import com.stock.domain.StockConsensus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StockConsensusRepository extends JpaRepository<StockConsensus, String> {
    Optional<StockConsensus> findByStockCode(String stockCode);
}
