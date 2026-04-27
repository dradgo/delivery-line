package org.dradgo;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Disabled("enabled in story 1.3 once Flyway V1 + Testcontainers wiring ships")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class DeliveryLineApplicationTests {

	@Test
	void contextLoads() {
	}

}
