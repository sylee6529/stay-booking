package com.example.staybooking.application;

import com.example.staybooking.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class StockSyncStartupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StockSyncStartupRunner.class);

    private final AppProperties properties;
    private final StockSyncService stockSyncService;

    public StockSyncStartupRunner(AppProperties properties, StockSyncService stockSyncService) {
        this.properties = properties;
        this.stockSyncService = stockSyncService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isStockSyncOnStartup()) {
            return;
        }
        log.info("stock-sync-on-startup enabled: syncing all product stock from DB to Redis");
        stockSyncService.syncAll();
    }
}
