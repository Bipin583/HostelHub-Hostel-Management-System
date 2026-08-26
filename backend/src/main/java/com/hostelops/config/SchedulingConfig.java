package com.hostelops.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on {@code @Scheduled} only when {@code app.scheduling.enabled} is true.
 *
 * <p>Gating the whole scheduler here rather than putting a boolean check inside
 * each job means a disabled scheduler has no timer threads at all, so a test can
 * never race a trigger it thought it had switched off.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "app.scheduling", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
