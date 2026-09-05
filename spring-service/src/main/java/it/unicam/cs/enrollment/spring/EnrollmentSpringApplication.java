package it.unicam.cs.enrollment.spring;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * ============================================================================
 * WHERE THE SERVER WENT
 * ============================================================================
 * This class is the entire answer to the question fieldbook chapter 16 opens
 * with. The Jakarta EE application has no {@code main} method anywhere: WildFly
 * is the process, and the WAR is something you hand to it. Here the application
 * IS the process, and Tomcat is a library it starts.
 *
 * <p>{@code @SpringBootApplication} is three annotations in a trench coat, and
 * being able to name all three is a standard interview question:
 *
 * <p>{@code @SpringBootConfiguration} - this class is itself a source of bean
 * definitions, so {@code @Bean} methods could live here.
 *
 * <p>{@code @ComponentScan} - find every {@code @Component},
 * {@code @Service}, {@code @Repository}, {@code @Controller} and
 * {@code @Configuration} at or below THIS PACKAGE. That last part is the one
 * that bites: the scan starts from the package of this class, so a bean in a
 * sibling package is invisible and the failure is a NoSuchBeanDefinitionException
 * that names the missing dependency rather than the reason. This is why the
 * convention is to put the application class in the ROOT package of the project,
 * as it is here.
 *
 * <p>{@code @EnableAutoConfiguration} - the part that looks like magic and is
 * not. Spring Boot reads a list of candidate configuration classes shipped
 * inside its own jars, and each one is guarded by conditions:
 * {@code @ConditionalOnClass}, {@code @ConditionalOnMissingBean},
 * {@code @ConditionalOnProperty}. "Is Hibernate on the classpath? Then configure
 * JPA - unless the application already defined an EntityManagerFactory itself."
 * That is all it is: a large, well-tested pile of if-statements about the
 * classpath.
 *
 * <p>THE COMMAND THAT MAKES IT CONCRETE, and the single most useful debugging
 * tool in Spring Boot:
 *
 * <pre>
 *   mvn spring-boot:run -Dspring-boot.run.arguments=--debug
 * </pre>
 *
 * It prints the CONDITIONS EVALUATION REPORT: every auto-configuration that
 * matched and why, and every one that did not and why not. The next time
 * something is not configured the way you expected, that report answers it in
 * seconds - and it turns auto-configuration from magic into a list you can read.
 *
 * <p>WHAT DOES NOT HAPPEN HERE. There is no {@code @EnableJpaRepositories}, no
 * {@code @EnableTransactionManagement}, no {@code @EnableWebMvc}. All three are
 * implied by the starters on the classpath. Adding {@code @EnableWebMvc} by hand
 * is in fact actively harmful - it switches OFF the MVC auto-configuration and
 * you lose the Jackson setup, the static resource handling and the error
 * handling in one move. A surprising amount of Stack Overflow advice tells you
 * to add it.
 */
@SpringBootApplication
// The one @Enable this application still needs.
//
// Everything else - JPA repositories, transactions, web MVC - is implied by the
// starters on the classpath. Scheduling is NOT, because a scheduler thread pool
// is a real cost and Boot will not start one on the off chance you wanted it.
// Leave this off and every @Scheduled method compiles, deploys, and never runs
// once - with no warning anywhere. It is the quietest failure in the framework.
//
// @EnableCaching is the same shape and lives on CacheConfig.
@EnableScheduling
public class EnrollmentSpringApplication {

    public static void main(String[] args) {
        SpringApplication.run(EnrollmentSpringApplication.class, args);
    }
}
