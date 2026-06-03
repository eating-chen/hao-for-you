package com.haoforyou.service;

import com.haoforyou.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class OllamaService {

    private static final Logger log = LoggerFactory.getLogger(OllamaService.class);
    private final AppProperties appProperties;

    public OllamaService(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    public String generate(String prompt) {
        log.info("Ollama generate stub called, prompt length={}", prompt == null ? 0 : prompt.length());
        return "Ollama service temporarily unavailable. Please configure AI dependency.";
    }

    public String chat(List<Map<String, String>> history, String userMessage) {
        log.info("Ollama chat stub called, history size={}, message={}", history == null ? 0 : history.size(), userMessage);
        return "Ollama chat service temporarily unavailable. Please configure AI dependency.";
    }
}
