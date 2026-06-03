package com.maou.apptemplateapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication
public class AppTemplateApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(AppTemplateApiApplication.class, args);
    }

}

