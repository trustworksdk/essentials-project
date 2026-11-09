package com.example.club.members.use_cases.rename_member;

import com.example.club.config.Clipboard;
import com.example.club.members.entities.Member;
import com.example.club.members.entities.MemberRepository;
import dk.trustworks.essentials.components.document_db.DocumentDbRepository;

public class RenameMemberHandler {
    private final MemberRepository members;
    private final DocumentDbRepository<Member, Long> archive;
    private final Clipboard clipboard = new Clipboard();

    public RenameMemberHandler(MemberRepository members, DocumentDbRepository<Member, Long> archive) {
        this.members = members;
        this.archive = archive;
    }

    public void handle(RenameMember cmd) {
        Member member = members.findById(cmd.id()).orElseThrow();
        member.rename(cmd.name());
        members.save(member);
        archive.update(member);
        clipboard.save(member);
    }
}
