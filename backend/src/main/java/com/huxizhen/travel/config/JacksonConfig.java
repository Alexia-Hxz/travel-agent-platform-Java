package com.huxizhen.travel.config;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalTimeSerializer;
import java.time.format.DateTimeFormatter;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * JSON 契约对齐 Pydantic v2 的输出。
 *
 * <p>三处必须逐字一致，否则前端"不报错但页面空白"：
 * <ul>
 *   <li>{@code LocalDate} → {@code 2026-10-01}</li>
 *   <li>{@code LocalTime} → {@code 09:00:00}（Java 默认 ISO_LOCAL_TIME 会省略秒，前端按
 *       {@code [:5]} 与字符串比较会错位，必须补零到秒）</li>
 *   <li>{@code LocalDateTime} → {@code 2026-10-01T10:00:00}（无时区、无毫秒，与
 *       {@code datetime.utcnow()} 的序列化一致）</li>
 * </ul>
 *
 * <p>反序列化保留 ISO 宽松解析：{@code 09:00} 与 {@code 09:00:00} 都接受（Pydantic 同样接受）。
 */
@Configuration
public class JacksonConfig {

    public static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    public static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    public static final DateTimeFormatter DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer travelJacksonCustomizer() {
        return builder -> builder
                .serializers(
                        new LocalDateSerializer(DATE_FORMAT),
                        new LocalTimeSerializer(TIME_FORMAT),
                        new LocalDateTimeSerializer(DATE_TIME_FORMAT))
                .deserializers(
                        new LocalDateDeserializer(DateTimeFormatter.ISO_LOCAL_DATE),
                        new LocalTimeDeserializer(DateTimeFormatter.ISO_LOCAL_TIME),
                        new LocalDateTimeDeserializer(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
