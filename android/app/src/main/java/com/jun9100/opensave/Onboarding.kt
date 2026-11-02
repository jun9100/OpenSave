package com.jun9100.opensave

import android.content.Context
import java.io.File

/** How the user chose to reach the saves in the first-run wizard. */
enum class AccessChoice {
    /** Only folders granted through the system picker; su is never called. */
    SAF,

    /** Root scanning as well; the user asked for the su prompt. */
    ROOT,
}

/** The steps of the first-run wizard, in order. */
enum class WizardStep(val title: String) {
    WELCOME("SaveVault 会备份什么"),
    ACCESS("选择访问方式"),
    CLOUD("连接 SaveVault 云端（可选）"),
    ;

    val number: Int get() = ordinal + 1

    fun next(): WizardStep? = entries.getOrNull(ordinal + 1)

    fun previous(): WizardStep? = entries.getOrNull(ordinal - 1)

    companion object {
        val count: Int get() = entries.size
    }
}

/** The pure rules of the first run, unit tested. */
object Onboarding {
    /**
     * The wizard opens once. A device that was set up before the wizard existed
     * (a granted folder, a Root path, a game-save config or snapshots) skips it.
     */
    fun shouldShowWizard(done: Boolean, hasExistingSetup: Boolean): Boolean = !done && !hasExistingSetup

    /**
     * Whether OpenSave may call su. Before the wizard is done it never does, so
     * the Magisk prompt appears only after the user picked Root; an install that
     * predates the wizard ([choice] null, wizard skipped as already set up)
     * keeps the old behaviour of checking Root.
     */
    fun rootAllowed(choice: AccessChoice?, wizardDone: Boolean): Boolean = when (choice) {
        AccessChoice.ROOT -> true
        AccessChoice.SAF -> false
        null -> wizardDone
    }

    /**
     * The choice stored when the wizard closes: an explicit one wins; a first
     * run that never picked stays with SAF (no su); a reopened wizard keeps
     * what was there.
     */
    fun choiceOnFinish(picked: AccessChoice?, stored: AccessChoice?, firstRun: Boolean): AccessChoice? =
        picked ?: stored ?: if (firstRun) AccessChoice.SAF else null

    /**
     * A background Root failure is worth reporting only when Root is expected:
     * allowed and seen working in the app before. Otherwise there is simply no Root.
     */
    fun reportBackgroundRootFailure(rootAllowed: Boolean, rootConfirmed: Boolean): Boolean = rootAllowed && rootConfirmed
}

/** Files whose presence means an su binary is installed; checked without running it. */
object RootDetection {
    val SU_PATHS = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/system_ext/bin/su",
        "/vendor/bin/su",
        "/debug_ramdisk/su",
        "/data/adb/magisk",
    )

    fun suLikelyPresent(exists: (String) -> Boolean = { File(it).exists() }): Boolean =
        SU_PATHS.any { runCatching { exists(it) }.getOrDefault(false) }
}

/** The wizard's state on this device. */
class OnboardingStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun wizardDone(): Boolean = preferences.getBoolean(WIZARD_DONE_KEY, false)

    fun setWizardDone() = preferences.edit().putBoolean(WIZARD_DONE_KEY, true).apply()

    fun accessChoice(): AccessChoice? =
        preferences.getString(ACCESS_KEY, null)?.let { name -> AccessChoice.entries.firstOrNull { it.name == name } }

    fun setAccessChoice(choice: AccessChoice?) {
        val editor = preferences.edit()
        if (choice == null) editor.remove(ACCESS_KEY) else editor.putString(ACCESS_KEY, choice.name)
        editor.apply()
    }

    fun rootAllowed(): Boolean = Onboarding.rootAllowed(accessChoice(), wizardDone())

    /** su granted uid 0 in the app at least once since Root was chosen. */
    fun rootConfirmed(): Boolean = preferences.getBoolean(ROOT_CONFIRMED_KEY, false)

    fun setRootConfirmed(confirmed: Boolean) = preferences.edit().putBoolean(ROOT_CONFIRMED_KEY, confirmed).apply()

    /** The notification permission was requested once; it is not asked again. */
    fun notificationsAsked(): Boolean = preferences.getBoolean(NOTIFICATIONS_ASKED_KEY, false)

    fun setNotificationsAsked() = preferences.edit().putBoolean(NOTIFICATIONS_ASKED_KEY, true).apply()

    private companion object {
        const val PREFERENCES = "opensave_onboarding"
        const val WIZARD_DONE_KEY = "wizard_done"
        const val ACCESS_KEY = "access_choice"
        const val ROOT_CONFIRMED_KEY = "root_confirmed"
        const val NOTIFICATIONS_ASKED_KEY = "notifications_asked"
    }
}
