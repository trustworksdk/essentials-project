package com.example.club.members._seed;

import com.example.club.members.entities.Member;
import com.example.club.members.entities.MemberRepository;

public class DemoMembers {
    void load(MemberRepository repository) {
        repository.save(new Member("demo"));
    }
}
