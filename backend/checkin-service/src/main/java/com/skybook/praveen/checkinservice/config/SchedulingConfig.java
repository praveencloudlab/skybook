package com.skybook.praveen.checkinservice.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Two scheduled jobs share this module (no-show sweep, manifest
 * finalization) - unlike inventory-service's single-job precedent, where
 * @EnableScheduling lives on that one job class, a dedicated config class
 * is the clearer owner once there's more than one user.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
