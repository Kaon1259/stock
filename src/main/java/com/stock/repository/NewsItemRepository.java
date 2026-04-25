package com.stock.repository;

import com.stock.domain.NewsItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NewsItemRepository extends JpaRepository<NewsItem, Long> {
    List<NewsItem> findTop10ByScopeAndScopeKeyOrderByPublishedAtDesc(NewsItem.Scope scope, String scopeKey);
    List<NewsItem> findTop10ByScopeOrderByPublishedAtDesc(NewsItem.Scope scope);
}
