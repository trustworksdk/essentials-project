package com.example.club.config;

import com.example.club.members.entities.Member;
import com.example.club.members.entities.MemberRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SeedConfiguration {
    @Bean
    CommandLineRunner seed(MemberRepository members) {
        return args -> members.save(new Member("founder"));
    }
}
