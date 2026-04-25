package com.stock.repository;

import com.stock.domain.Watchlist;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WatchlistRepository extends JpaRepository<Watchlist, Long> {
    List<Watchlist> findAllByOrderByAddedAtAsc();
    Optional<Watchlist> findByStockCode(String stockCode);
    void deleteByStockCode(String stockCode);
    boolean existsByStockCode(String stockCode);
}
