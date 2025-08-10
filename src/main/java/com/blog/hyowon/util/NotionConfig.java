package com.blog.hyowon.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class NotionConfig {
    @Value("${notion.api.key}")
    private String apiKey;

    @Value("${notion.page.id}")
    private String pageId;

    public String getApiKey() {
        return apiKey;
    }
    public String getPageId() {
        return pageId;
    }
}
