package com.lookahead.domain.cloud;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Scheduling exists only in cloud mode: the admission cleanup is the sole scheduled task (DLV-969). */
@Configuration
@EnableScheduling
@ConditionalOnExpression("'${app.deployment-environment:}' == 'dev' || '${app.deployment-environment:}' == 'prod'")
public class CloudSchedulingConfiguration {}
