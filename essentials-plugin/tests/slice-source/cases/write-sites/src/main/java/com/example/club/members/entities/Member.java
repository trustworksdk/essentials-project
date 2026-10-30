package com.example.club.members.entities;

import jakarta.persistence.Entity;

@Entity
public class Member {
    private String name;

    public Member(String name) {
        this.name = name;
    }

    public void rename(String name) {
        this.name = name;
    }
}
