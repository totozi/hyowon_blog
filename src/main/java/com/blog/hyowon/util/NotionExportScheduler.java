package com.blog.hyowon.util;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.*;

@Component
public class NotionExportScheduler {

    private final NotionConfig notionConfig;

    public NotionExportScheduler(NotionConfig notionConfig) {
        this.notionConfig = notionConfig;
    }

    // 테스트: 3분마다
    @Scheduled(cron = "0 */1 * * * *")
    // @Scheduled(cron = "0 0 3 * * *")
    public void exportNotion() throws Exception {
        String token  = notionConfig.getApiKey();
        String pageId = notionConfig.getPageId();

        Path outDir = Paths.get("src/main/resources/static/notion_export");
        new NotionExporter(token).exportSite(pageId, outDir);

        long htmlCount = Files.walk(outDir).filter(p -> p.toString().endsWith(".html")).count();
        long imgCount  = Files.exists(outDir.resolve("assets/images"))
                ? Files.walk(outDir.resolve("assets/images")).filter(Files::isRegularFile).count() : 0;

        System.out.printf("[%s] Export 완료 - HTML:%d, IMG:%d%n",
                java.time.LocalDateTime.now(), htmlCount, imgCount);
    }
}
