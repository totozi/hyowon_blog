package com.blog.hyowon;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class HyowonBlogApplication {

	public static void main(String[] args) {
		SpringApplication.run(HyowonBlogApplication.class, args);
	}

}
