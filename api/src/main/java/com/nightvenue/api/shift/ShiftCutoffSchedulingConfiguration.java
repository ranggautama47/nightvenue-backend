package com.nightvenue.api.shift;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "nightvenue.shift.cutoff", name = "enabled", havingValue = "true", matchIfMissing = false)
@EnableScheduling
public class ShiftCutoffSchedulingConfiguration {
}
