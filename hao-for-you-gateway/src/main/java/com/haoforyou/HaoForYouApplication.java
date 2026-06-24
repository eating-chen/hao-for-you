package com.haoforyou;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.haoforyou.config.AppProperties;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class HaoForYouApplication {
    public static void main(String[] args) {
        SpringApplication.run(HaoForYouApplication.class, args);
    }
}
