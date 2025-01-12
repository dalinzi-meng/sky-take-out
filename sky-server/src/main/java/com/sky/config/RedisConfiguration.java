package com.sky.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
@Slf4j
public class RedisConfiguration {
    @Bean
    public RedisTemplate redisTemplate(RedisConnectionFactory redisConnectionFactory) {
        log.info("开始创建redis模板对象");
        RedisTemplate redisTemplate = new RedisTemplate();
        // 设置redis的连接工厂对象
        redisTemplate.setConnectionFactory(redisConnectionFactory);
        // 设置redis key的序列化器
        redisTemplate.setKeySerializer(new StringRedisSerializer());
        // 设置redis value的序列化器
        redisTemplate.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        // 设置hash key的序列化器
        redisTemplate.setHashKeySerializer(new StringRedisSerializer());
        // 设置hash value的序列化器
        redisTemplate.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());

        // 初始化后执行
        redisTemplate.afterPropertiesSet();
        return redisTemplate;
    }
}
