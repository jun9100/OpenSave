package com.jun9100.opensave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingTest {
    @Test
    fun wizardOpensOnceAndNotOnDevicesSetUpBefore() {
        assertTrue(Onboarding.shouldShowWizard(done = false, hasExistingSetup = false))
        assertFalse(Onboarding.shouldShowWizard(done = true, hasExistingSetup = false))
        assertFalse(Onboarding.shouldShowWizard(done = false, hasExistingSetup = true))
    }

    @Test
    fun suIsNeverCalledBeforeTheUserChoseRoot() {
        // A new install before or while the wizard runs.
        assertFalse(Onboarding.rootAllowed(choice = null, wizardDone = false))
        assertFalse(Onboarding.rootAllowed(choice = AccessChoice.SAF, wizardDone = true))
        assertTrue(Onboarding.rootAllowed(choice = AccessChoice.ROOT, wizardDone = false))
        // An install that predates the wizard keeps checking Root.
        assertTrue(Onboarding.rootAllowed(choice = null, wizardDone = true))
    }

    @Test
    fun finishingKeepsAnExplicitPickAndDefaultsAFirstRunToFolders() {
        assertEquals(AccessChoice.ROOT, Onboarding.choiceOnFinish(AccessChoice.ROOT, AccessChoice.SAF, firstRun = false))
        assertEquals(AccessChoice.SAF, Onboarding.choiceOnFinish(null, null, firstRun = true))
        assertEquals(AccessChoice.ROOT, Onboarding.choiceOnFinish(null, AccessChoice.ROOT, firstRun = true))
        // Reopened from 设置 on an older install and closed without a pick: nothing changes.
        assertNull(Onboarding.choiceOnFinish(null, null, firstRun = false))
    }

    @Test
    fun aBackgroundRootFailureIsReportedOnlyWhenRootWorkedBefore() {
        assertTrue(Onboarding.reportBackgroundRootFailure(rootAllowed = true, rootConfirmed = true))
        assertFalse(Onboarding.reportBackgroundRootFailure(rootAllowed = true, rootConfirmed = false))
        assertFalse(Onboarding.reportBackgroundRootFailure(rootAllowed = false, rootConfirmed = true))
    }

    @Test
    fun suIsDetectedFromFilesWithoutRunningIt() {
        assertTrue(RootDetection.suLikelyPresent { it == "/system/xbin/su" })
        assertFalse(RootDetection.suLikelyPresent { false })
        assertFalse(RootDetection.suLikelyPresent { throw SecurityException("denied") })
    }

    @Test
    fun wizardStepsGoForwardAndBack() {
        assertEquals(3, WizardStep.count)
        assertEquals(WizardStep.ACCESS, WizardStep.WELCOME.next())
        assertNull(WizardStep.CLOUD.next())
        assertNull(WizardStep.WELCOME.previous())
        assertEquals(WizardStep.ACCESS, WizardStep.CLOUD.previous())
        assertEquals(listOf(1, 2, 3), WizardStep.entries.map(WizardStep::number))
    }
}
