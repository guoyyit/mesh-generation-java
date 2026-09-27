package com.github.guoyyit.meshgenerationjava.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MeshProperties.class)
public class MeshServiceConfig {

    @Bean(destroyMethod = "close")
    public MeshTaskManager meshTaskManager(MeshProperties properties) {
        return new MeshTaskManager(properties);
    }
}
