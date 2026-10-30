package com.example.club.members.use_cases.join_club;

import com.example.club.members.entities.Member;
import com.example.club.members.entities.MemberRepository;

public class JoinClubHandler {
    private final MemberRepository members;

    public JoinClubHandler(MemberRepository members) {
        this.members = members;
    }

    public void handle(JoinClub cmd) {
        this.members.save(new Member(cmd.name()));
    }
}
