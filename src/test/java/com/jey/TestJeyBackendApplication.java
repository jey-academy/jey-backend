package com.jey;

import org.springframework.boot.SpringApplication;

public class TestJeyBackendApplication {

	public static void main(String[] args) {
		SpringApplication.from(JeyBackendApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
