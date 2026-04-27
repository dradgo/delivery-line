package org.dradgo;

import org.springframework.boot.SpringApplication;

public class TestDeliveryLineApplication {

	public static void main(String[] args) {
		SpringApplication.from(DeliveryLineApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
