package it.unicam.cs.enrollment.spring.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Supplies the application clock.
 *
 * <p>The direct counterpart of ClockProducer in the Jakarta EE application, and
 * the translation is worth staring at for a moment because it is chapter 18 in
 * miniature:
 *
 * <pre>
 *   CDI                                    Spring
 *   ---------------------------------      ---------------------------------
 *   {@literal @}Dependent class ClockProducer         {@literal @}Configuration class ClockConfig
 *   {@literal @}Produces Clock systemClock()          {@literal @}Bean Clock systemClock()
 * </pre>
 *
 * <p>Same idea, same granularity, different vocabulary. A method that returns an
 * object the container should manage, in a class whose job is to declare such
 * methods. Everything else about dependency injection follows the same pattern -
 * which is why moving between the two is a week of vocabulary rather than a
 * month of concepts.
 *
 * <p>WHY A CLOCK IS A BEAN AT ALL. Because Instant.now() inside a service is
 * untestable: there is no way to ask what happens the day after enrollment
 * closes without waiting for it. Injecting Clock lets a test pass
 * Clock.fixed(...) and control time exactly. Fieldbook chapter 20 makes this
 * argument at length; EnrollmentServiceTest is where it pays off.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
