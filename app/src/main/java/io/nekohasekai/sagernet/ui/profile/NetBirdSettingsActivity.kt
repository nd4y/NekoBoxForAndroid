package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import android.content.Intent
import android.text.InputType
import android.view.Menu
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ui.NetBirdActivity
import androidx.preference.Preference
import io.nekohasekai.sagernet.fmt.netbird.NetBirdBean
import io.nekohasekai.sagernet.fmt.netbird.validateNetBirdManagementUrl
import io.nekohasekai.sagernet.fmt.netbird.parseNetBirdDnsDomains
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import moe.matsuri.nb4a.proxy.PreferenceBinding
import moe.matsuri.nb4a.proxy.PreferenceBindingManager
import moe.matsuri.nb4a.proxy.Type

class NetBirdSettingsActivity : ProfileSettingsActivity<NetBirdBean>() {
    override fun createEntity() = NetBirdBean()

    private val pbm = PreferenceBindingManager()
    private val name = pbm.add(PreferenceBinding(Type.Text, "name"))
    private val managementUrl = pbm.add(PreferenceBinding(Type.Text, "managementUrl"))
    private val setupKey = pbm.add(PreferenceBinding(Type.Text, "setupKey"))
    private val hostname = pbm.add(PreferenceBinding(Type.Text, "hostname"))
    private val dnsDomains = pbm.add(PreferenceBinding(Type.Text, "dnsDomains"))

    override fun NetBirdBean.init() { pbm.writeToCacheAll(this) }
    override fun NetBirdBean.serialize() {
        pbm.fromCacheAll(this)
        managementUrl = managementUrl.trim()
        setupKey = setupKey.trim()
        hostname = hostname.trim()
        initializeDefaultValues()
    }

    override suspend fun saveAndExit() {
        val candidate = createEntity().apply { serialize() }
        val error = runCatching { validateNetBirdManagementUrl(candidate.managementUrl) }.exceptionOrNull()
        if (error != null) {
            onMainDispatcher {
                Toast.makeText(this@NetBirdSettingsActivity, R.string.netbird_invalid_management_url, Toast.LENGTH_LONG).show()
            }
            return
        }
        if (runCatching { parseNetBirdDnsDomains(candidate.dnsDomains) }.isFailure) {
            onMainDispatcher {
                Toast.makeText(this@NetBirdSettingsActivity, R.string.netbird_invalid_dns_domains, Toast.LENGTH_LONG).show()
            }
            return
        }
        super.saveAndExit()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        // Custom JSON must not override the mandatory underlay or identity storage.
        menu.findItem(R.id.action_custom_outbound_json)?.isVisible = false
        menu.findItem(R.id.action_custom_config_json)?.isVisible = false
        return true
    }

    override fun PreferenceFragmentCompat.createPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.netbird_preferences)
        pbm.setPreferenceFragment(this)
        findPreference<Preference>("netbirdConnections")!!.apply {
            isEnabled = DataStore.editingId != 0L
            summary = getString(if (isEnabled) R.string.netbird_connections_summary else R.string.netbird_save_first)
            setOnPreferenceClickListener {
                // Profile editors share one edit cache. Do not keep this editor under another one.
                if (DataStore.dirty) Toast.makeText(requireContext(), R.string.netbird_save_first, Toast.LENGTH_LONG).show()
                else {
                    this@NetBirdSettingsActivity.finish()
                    startActivity(Intent(requireContext(), NetBirdActivity::class.java))
                }
                true
            }
        }
        (setupKey.preference as EditTextPreference).apply {
            summaryProvider = PasswordSummaryProvider
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        }
        (managementUrl.preference as EditTextPreference).setOnBindEditTextListener {
            it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
    }
}
