package com.haoforyou.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Hao-for-you Gateway API")
                        .description("LINE Bot 智慧助理 Gateway — Google Calendar / Gmail / OAuth 端點文件")
                        .version("0.1.0"))
                .servers(List.of(
                        new Server().url("http://localhost:8008").description("本機開發")
                ));
    }
}
