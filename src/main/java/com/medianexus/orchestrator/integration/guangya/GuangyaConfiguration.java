package com.medianexus.orchestrator.integration.guangya;

import com.medianexus.orchestrator.config.GuangyaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GuangyaProperties.class)
public class GuangyaConfiguration {}
