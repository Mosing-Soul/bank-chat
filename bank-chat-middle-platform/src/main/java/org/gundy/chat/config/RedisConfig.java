package org.gundy.chat.config;

import org.gundy.chat.entity.HistoryMessage;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.util.List;

@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, List<HistoryMessage>> redisTemplate(RedisConnectionFactory factory) {

        RedisTemplate<String, List<HistoryMessage>> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        // 使用 Jackson2JsonRedisSerializer 序列化 value
        Jackson2JsonRedisSerializer<List<HistoryMessage>> serializer = 
            new Jackson2JsonRedisSerializer<>((Class<List<HistoryMessage>>) (Class<?>) List.class);
        template.setValueSerializer(serializer);
        template.setKeySerializer(new StringRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }
}