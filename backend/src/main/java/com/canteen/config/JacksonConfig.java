package com.canteen.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 统一 LocalDateTime 入参契约：同时接受 ISO（前端 el-date-picker value-format）与
 * 「yyyy-MM-dd HH:mm:ss」（第三方常用），避免 spring.jackson.date-format 对
 * LocalDateTime 不生效导致 500。
 */
@Configuration
public class JacksonConfig {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final DateTimeFormatter SPACE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer localDateTimeCustomizer() {
        return builder -> builder.deserializerByType(LocalDateTime.class, new JsonDeserializer<LocalDateTime>() {
            @Override
            public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                String raw = p.getValueAsString();
                if (raw == null || raw.isBlank()) {
                    return null;
                }
                try {
                    return LocalDateTime.parse(raw, ISO);
                } catch (DateTimeParseException e1) {
                    try {
                        return LocalDateTime.parse(raw, SPACE);
                    } catch (DateTimeParseException e2) {
                        throw new InvalidFormatException(p,
                                "日期格式应为 yyyy-MM-ddTHH:mm:ss 或 yyyy-MM-dd HH:mm:ss",
                                raw, LocalDateTime.class);
                    }
                }
            }
        });
    }
}
