package dev.xross.launcher.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The user's own accounts for metadata services. */
data class ScraperAccounts(
    val screenScraperUser: String = "",
    val screenScraperPassword: String = "",
    val steamGridDbKey: String = "",
) {
    override fun toString() = "ScraperAccounts(screenScraperUser=$screenScraperUser, secrets=<hidden>)"
}

/**
 * Service credentials, kept apart from [SettingsStore] in their own app-private preferences file
 * that is excluded from cloud backup and device transfer (see `res/xml/backup_rules.xml` and
 * `data_extraction_rules.xml`), so a password never leaves the device with a settings backup.
 */
class CredentialStore(context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _accounts = MutableStateFlow(load())
    val accounts: StateFlow<ScraperAccounts> = _accounts.asStateFlow()
    val current: ScraperAccounts get() = _accounts.value

    fun update(transform: (ScraperAccounts) -> ScraperAccounts) {
        val next = transform(_accounts.value)
        if (next == _accounts.value) return
        prefs.edit {
            putString(KEY_SS_USER, next.screenScraperUser)
            putString(KEY_SS_PASSWORD, next.screenScraperPassword)
            putString(KEY_SGDB, next.steamGridDbKey)
        }
        _accounts.value = next
    }

    private fun load() = ScraperAccounts(
        screenScraperUser = prefs.getString(KEY_SS_USER, null).orEmpty(),
        screenScraperPassword = prefs.getString(KEY_SS_PASSWORD, null).orEmpty(),
        steamGridDbKey = prefs.getString(KEY_SGDB, null).orEmpty(),
    )

    companion object {
        /** Also named in the backup exclusion rules. */
        const val FILE = "xross_credentials"
        private const val KEY_SS_USER = "screenscraper_user"
        private const val KEY_SS_PASSWORD = "screenscraper_password"
        private const val KEY_SGDB = "steamgriddb_key"
    }
}
