package com.mhmh2.bujirun.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountRulesTest {
    @Test fun acceptsNormalEmails() {
        listOf("a@b.co", "runner.kim+bujirun@gmail.com", "  Someone@Naver.com ", "x_y@mail.example.co.kr")
            .forEach { assertTrue(it, AccountRules.isValidEmail(it)) }
    }

    @Test fun rejectsNonEmails() {
        listOf("", "admin", "abc", "a@b", "a@b.", "@b.com", "a b@c.com", "a@b..com", "홍길동@메일.한국")
            .forEach { assertFalse(it, AccountRules.isValidEmail(it)) }
    }

    @Test fun normalizesEmail() {
        assertEquals("someone@naver.com", AccountRules.normalizeEmail("  Someone@Naver.com "))
    }

    @Test fun nicknameRules() {
        assertTrue(AccountRules.isValidNickname("부지런"))
        assertTrue(AccountRules.isValidNickname("  달리는  사람 "))
        assertEquals("달리는 사람", AccountRules.normalizeNickname("  달리는  사람 "))
        assertFalse(AccountRules.isValidNickname("a"))
        assertFalse(AccountRules.isValidNickname("   "))
        assertFalse(AccountRules.isValidNickname("열세글자가넘는아주긴닉네임"))
    }

    @Test fun passwordRules() {
        assertTrue(AccountRules.isValidPassword("12345678"))
        assertFalse(AccountRules.isValidPassword("1234567"))
    }
}
