package com.maou.apptemplateapi.common.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.storage")
public class StorageProperties {

    private String basePath = "app-storage";
    private long previewExpireMinutes = 30;
    private Rustfs rustfs = new Rustfs();

    @Getter
    @Setter
    public static class Rustfs {

        private String endpoint = "http://127.0.0.1:9000";
        private String accessKey = "rustfsadmin";
        private String secretKey = "rustfsadmin";
        private String bucket = "app-storage";
        private String region = "us-east-1";
        private boolean forcePathStyle = true;
    }
}

