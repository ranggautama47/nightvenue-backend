package com.nightvenue.api.bottle;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(BottleProperties.class)
public class BottleConfiguration {
}

