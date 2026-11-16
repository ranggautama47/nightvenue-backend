package com.nightvenue.api.bottle;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "nightvenue.bottle.expiry", name = "enabled", havingValue = "true", matchIfMissing = false)
@EnableScheduling
public class BottleExpirySchedulingConfiguration {
}
