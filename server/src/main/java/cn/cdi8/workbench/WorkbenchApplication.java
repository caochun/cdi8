package cn.cdi8.workbench;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class WorkbenchApplication {
    public static void main(String[] args) {
        SpringApplication.run(WorkbenchApplication.class, args);
    }
}
