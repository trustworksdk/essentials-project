package com.example.club.members.automations.welcome

import com.example.club.members.entities.Member
import com.example.club.members.entities.MemberRepository

class WelcomeProcessor(private val members: MemberRepository) {
    fun greet(member: Member) {
        members.save(member)
    }
}
