package com.maou.apptemplateapi.common.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties({StorageProperties.class, FileProperties.class})
public class StorageConfig {

    private final StorageProperties storageProperties;

    @Bean
    public S3Client rustfsS3Client() {
        StorageProperties.Rustfs rustfs = storageProperties.getRustfs();
        return S3Client.builder()
                .endpointOverride(URI.create(rustfs.getEndpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(rustfs.getAccessKey(), rustfs.getSecretKey())))
                .region(Region.of(rustfs.getRegion()))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(rustfs.isForcePathStyle())
                        .build())
                .build();
    }

    @Bean
    public S3Presigner rustfsS3Presigner() {
        StorageProperties.Rustfs rustfs = storageProperties.getRustfs();
        return S3Presigner.builder()
                .endpointOverride(URI.create(rustfs.getEndpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(rustfs.getAccessKey(), rustfs.getSecretKey())))
                .region(Region.of(rustfs.getRegion()))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(rustfs.isForcePathStyle())
                        .build())
                .build();
    }
}

