package com.tjg.twidget.brief

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class BriefNumericEvidenceTest {
    @Test fun equivalentGroupingAndExplicitPositiveSignsPreserveTheObservedValue() {
        val strings = TestBriefStrings()
        assertTrue(BriefNumericEvidence.accepts("You have 1,200 followers, up 2 today.", "1200 followers, +2 today", strings))
        assertTrue(BriefNumericEvidence.accepts("Progress: 80.0%", "80%", strings))
        assertFalse(BriefNumericEvidence.accepts("You have 1,201 followers.", "1200 followers", strings))
        assertFalse(BriefNumericEvidence.accepts("Your progress is 80.", "80%", strings))
        assertFalse(BriefNumericEvidence.accepts("Your change is 2.", "-2", strings))
        assertFalse(BriefNumericEvidence.accepts("You have 1.2 followers.", "1200", strings))
        assertFalse(BriefNumericEvidence.accepts("You have 12,00 followers.", "1200", strings))
    }

    @Test fun decimalAndGroupingSeparatorsFollowTheAppLanguage() {
        val german = TestBriefStrings(Locale.GERMAN)
        assertTrue(BriefNumericEvidence.accepts("1.200 Follower, 3,5%", "1200 Follower, 3,50%", german))
        assertFalse(BriefNumericEvidence.accepts("3,5%", "35%", german))
        val french = TestBriefStrings(Locale.FRENCH)
        assertTrue(BriefNumericEvidence.accepts("1 200 abonnés", "1\u202f200 abonnés", french))
        assertFalse(BriefNumericEvidence.accepts("1 201 abonnés", "1200 abonnés", french))
    }
}
