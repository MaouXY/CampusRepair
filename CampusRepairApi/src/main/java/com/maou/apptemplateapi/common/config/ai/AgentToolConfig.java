package com.maou.apptemplateapi.common.config.ai;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AgentToolProperties.class)
public class AgentToolConfig {
}

