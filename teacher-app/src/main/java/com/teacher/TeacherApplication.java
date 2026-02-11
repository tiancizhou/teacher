package com.teacher;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 书法AI批改系统 - 启动类。
 */
@SpringBootApplication(scanBasePackages = "com.teacher")
@EnableScheduling
public class TeacherApplication {

    public static void main(String[] args) throws Exception {
        // SQLite 不会自动创建父目录，启动前确保 data/ 存在
        Files.createDirectories(Path.of("data"));
        SpringApplication.run(TeacherApplication.class, args);
    }
}
