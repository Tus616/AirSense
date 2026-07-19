package com.airsense.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AirsenseApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(AirsenseApiApplication.class, args);
	}

}
