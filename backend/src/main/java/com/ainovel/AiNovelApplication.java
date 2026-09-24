package com.ainovel;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;



//接口文档: http://localhost:8080/doc.html
@EnableAsync
@EnableCaching
@EnableScheduling
@MapperScan("com.ainovel.module.**.dao")
@SpringBootApplication
public class AiNovelApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiNovelApplication.class, args);

    }
}
