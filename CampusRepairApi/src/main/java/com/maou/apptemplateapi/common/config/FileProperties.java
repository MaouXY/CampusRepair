package com.maou.apptemplateapi.common.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.file")
public class FileProperties {

    private long maxSize = 10 * 1024 * 1024L;
    private List<String> allowedContentTypes = new ArrayList<>();
    private long tempExpireHours = 24;
    private String cleanupCron = "0 0 * * * *";
}

