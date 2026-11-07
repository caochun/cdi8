package cn.cdi8.facility;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Independent observation, facility facts and system supervision process. */
@SpringBootApplication
@EnableScheduling
public class FacilityServerApplication {
    public static void main(String[] args) { SpringApplication.run(FacilityServerApplication.class,args); }
}
