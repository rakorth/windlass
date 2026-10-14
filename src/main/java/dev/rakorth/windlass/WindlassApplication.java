package dev.rakorth.windlass;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class WindlassApplication {

    public static void main(String[] args) {
        SpringApplication.run(WindlassApplication.class, args);
    }

}
