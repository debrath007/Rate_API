package com.example.aprapi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "apr.excel")
public record ExcelProperties(String path) {
}
