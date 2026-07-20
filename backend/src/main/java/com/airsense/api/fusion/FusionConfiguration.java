package com.airsense.api.fusion;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(FusionCacheProperties.class)
public class FusionConfiguration {
}
