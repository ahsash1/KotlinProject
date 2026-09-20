package org.example.project.guidance

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfirmationMatcherTest {

    @Test
    fun `recognises plain affirmative words`() {
        assertTrue(ConfirmationMatcher.isAffirmative("yes"))
        assertTrue(ConfirmationMatcher.isAffirmative("Yeah."))
        assertTrue(ConfirmationMatcher.isAffirmative("Sure, okay"))
    }

    @Test
    fun `recognises affirmative phrases`() {
        assertTrue(ConfirmationMatcher.isAffirmative("go ahead"))
        assertTrue(ConfirmationMatcher.isAffirmative("yeah do it"))
    }

    @Test
    fun `treats anything else as not affirmative`() {
        assertFalse(ConfirmationMatcher.isAffirmative("no"))
        assertFalse(ConfirmationMatcher.isAffirmative("cancel that"))
        assertFalse(ConfirmationMatcher.isAffirmative("what did you say"))
        assertFalse(ConfirmationMatcher.isAffirmative(""))
        assertFalse(ConfirmationMatcher.isAffirmative("   "))
    }
}
