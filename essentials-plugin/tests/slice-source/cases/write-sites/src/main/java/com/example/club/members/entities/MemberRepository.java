package com.example.club.members.entities;

import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface MemberRepository extends Repository<Member, Long> {
    Member save(Member member);

    void delete(Member member);

    Optional<Member> findById(Long id);
}
