package com.aegis.alpha;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.aegis.alpha.mapper")
public class AlphaApplication {
    public static void main(String[] args) {
        SpringApplication.run(AlphaApplication.class, args);
    }
}
