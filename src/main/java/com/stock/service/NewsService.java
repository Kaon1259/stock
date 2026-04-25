package com.stock.service;

import com.stock.config.CacheConfig;
import com.stock.domain.NewsItem;
import com.stock.repository.NewsItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class NewsService {

    private final NewsItemRepository newsItemRepository;

    @Cacheable(value = CacheConfig.STOCK_NEWS, key = "#code")
    public List<NewsItem> stockNews(String code) {
        return newsItemRepository.findTop10ByScopeAndScopeKeyOrderByPublishedAtDesc(NewsItem.Scope.STOCK, code);
    }

    @Cacheable(value = CacheConfig.MACRO_NEWS, key = "'macro'")
    public List<NewsItem> macroNews() {
        return newsItemRepository.findTop10ByScopeOrderByPublishedAtDesc(NewsItem.Scope.MACRO);
    }

    @Cacheable(value = CacheConfig.MACRO_NEWS, key = "'global'")
    public List<NewsItem> globalNews() {
        return newsItemRepository.findTop10ByScopeOrderByPublishedAtDesc(NewsItem.Scope.GLOBAL);
    }
}
