package com.stock.config;

import com.stock.domain.Stock;
import com.stock.domain.Stock.AssetType;
import com.stock.domain.Watchlist;
import com.stock.repository.PriceHistoryRepository;
import com.stock.repository.StockRepository;
import com.stock.repository.WatchlistRepository;
import com.stock.service.LiveDataService;
import com.stock.service.StockSummaryCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class DataSeeder {

    private final StockRepository stockRepository;
    private final WatchlistRepository watchlistRepository;
    private final LiveDataService liveDataService;
    private final PriceHistoryRepository priceHistoryRepository;
    private final StockSummaryCache stockSummaryCache;

    @Bean
    ApplicationRunner seedData() {
        return args -> {
            List<SeedStock> seeds = List.of(
                    new SeedStock("005930", "삼성전자", "KOSPI", "반도체", AssetType.STOCK),
                    new SeedStock("000660", "SK하이닉스", "KOSPI", "반도체", AssetType.STOCK),
                    new SeedStock("035420", "NAVER", "KOSPI", "IT 서비스", AssetType.STOCK),
                    new SeedStock("035720", "카카오", "KOSPI", "IT 서비스", AssetType.STOCK),
                    new SeedStock("005380", "현대차", "KOSPI", "자동차", AssetType.STOCK),
                    new SeedStock("000270", "기아", "KOSPI", "자동차", AssetType.STOCK),
                    new SeedStock("373220", "LG에너지솔루션", "KOSPI", "2차전지", AssetType.STOCK),
                    new SeedStock("207940", "삼성바이오로직스", "KOSPI", "바이오", AssetType.STOCK),
                    new SeedStock("005490", "POSCO홀딩스", "KOSPI", "철강", AssetType.STOCK),
                    new SeedStock("051910", "LG화학", "KOSPI", "화학", AssetType.STOCK),
                    new SeedStock("055550", "신한지주", "KOSPI", "금융", AssetType.STOCK),
                    new SeedStock("105560", "KB금융", "KOSPI", "금융", AssetType.STOCK),

                    new SeedStock("069500", "KODEX 200", "KOSPI", "지수형 ETF", AssetType.ETF),
                    new SeedStock("229200", "KODEX 코스닥150", "KOSPI", "지수형 ETF", AssetType.ETF),
                    new SeedStock("122630", "KODEX 레버리지", "KOSPI", "레버리지 ETF", AssetType.ETF),
                    new SeedStock("114800", "KODEX 인버스", "KOSPI", "인버스 ETF", AssetType.ETF),
                    new SeedStock("252670", "KODEX 200선물인버스2X", "KOSPI", "인버스 ETF", AssetType.ETF),
                    new SeedStock("360750", "TIGER 미국S&P500", "KOSPI", "해외주식 ETF", AssetType.ETF),
                    new SeedStock("133690", "TIGER 미국나스닥100", "KOSPI", "해외주식 ETF", AssetType.ETF),
                    new SeedStock("381180", "TIGER 미국필라델피아반도체나스닥", "KOSPI", "해외주식 ETF", AssetType.ETF),
                    new SeedStock("371460", "TIGER 차이나전기차SOLACTIVE", "KOSPI", "해외주식 ETF", AssetType.ETF),
                    new SeedStock("305720", "KODEX 2차전지산업", "KOSPI", "테마 ETF", AssetType.ETF),
                    new SeedStock("091160", "KODEX 반도체", "KOSPI", "테마 ETF", AssetType.ETF),
                    new SeedStock("139660", "KODEX 200TR", "KOSPI", "지수형 ETF", AssetType.ETF)
            );

            int created = 0;
            for (SeedStock s : seeds) {
                if (!stockRepository.existsById(s.code)) {
                    stockRepository.save(Stock.builder()
                            .code(s.code).name(s.name).market(s.market).sector(s.sector).type(s.type).build());
                    created++;
                }
            }
            log.info("[Seed] 종목 메타데이터 {}건 신규 등록 (총 {}건)", created, seeds.size());

            if (watchlistRepository.count() == 0) {
                List.of("005930", "000660", "035420", "069500").forEach(code ->
                        watchlistRepository.save(Watchlist.builder().stockCode(code).build()));
                log.info("[Seed] 기본 관심 종목 4개 등록");
            }

            Thread liveDataLoader = new Thread(() -> {
                log.info("[LiveData] 백그라운드 초기 적재 시작 — 시세/뉴스/컨센서스 fetch");
                long startTs = System.currentTimeMillis();
                int loaded = 0, skipped = 0;
                for (SeedStock s : seeds) {
                    try {
                        boolean hasData = !priceHistoryRepository.findByStockCodeOrderByTradeDateAsc(s.code).isEmpty();
                        if (hasData) {
                            skipped++;
                            continue;
                        }
                        liveDataService.initializeStock(s.code);
                        loaded++;
                    } catch (Exception e) {
                        log.warn("[LiveData] {} 초기화 실패: {}", s.code, e.getMessage());
                    }
                }
                try {
                    liveDataService.refreshMacroNews();
                } catch (Exception e) {
                    log.warn("[LiveData] 매크로 뉴스 실패: {}", e.getMessage());
                }
                stockSummaryCache.warmUpAll();
                log.info("[LiveData] 초기 적재 완료 ({}초) — 신규 적재 {}건, 기존 데이터 스킵 {}건",
                        (System.currentTimeMillis() - startTs) / 1000, loaded, skipped);
            }, "live-data-init");
            liveDataLoader.setDaemon(true);
            liveDataLoader.start();
        };
    }

    private record SeedStock(String code, String name, String market, String sector, AssetType type) {}
}
