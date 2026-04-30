package greenecomall;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class GreenEcoMallApplication {

    public static void main(String[] args) {
        SpringApplication.run(GreenEcoMallApplication.class, args);
    }
}
