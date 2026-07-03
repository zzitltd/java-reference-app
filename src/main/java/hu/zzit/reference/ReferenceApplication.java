package hu.zzit.reference;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.resilience.annotation.EnableResilientMethods;

@SpringBootApplication
// Activates Spring Framework's built-in @Retryable / @ConcurrencyLimit processing
// (see the ObjectStorage port for the retry policy) — no extra dependency needed.
@EnableResilientMethods
public class ReferenceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReferenceApplication.class, args);
    }
}
