package com.example.meituan.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot Web 后端主程序
 * 端口: 8080
 */
@SpringBootApplication
@ComponentScan(basePackages = "com.example.meituan")
@EnableScheduling
public class WebApplication {

    public static void main(String[] args) {
        // 强制禁用 Headless 模式
        System.setProperty("java.awt.headless", "false");

        SpringApplication.run(WebApplication.class, args);
    }
}
