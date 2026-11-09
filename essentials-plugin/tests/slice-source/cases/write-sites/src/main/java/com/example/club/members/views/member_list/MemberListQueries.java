package com.example.club.members.views.member_list;

import com.example.club.members.entities.Member;
import com.example.club.members.entities.MemberRepository;

public class MemberListQueries {
    private final MemberRepository members;

    public MemberListQueries(MemberRepository members) {
        this.members = members;
    }

    public void prune(Long id) {
        Member member = members.findById(id).orElseThrow();
        members.delete(member);
    }
}
