package it.unicam.cs.enrollment.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The notification service.
 *
 * <pre>
 *   mvn spring-boot:run -f notification-service/pom.xml
 *   -> http://localhost:8282
 * </pre>
 *
 * <p>Ninety lines of application code in total. That is deliberate: chapter 33
 * says a junior on a microservices team "writes ordinary code in one small
 * service", and this is what one small service actually looks like.
 */
@SpringBootApplication
public class NotificationApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationApplication.class, args);
    }
}
