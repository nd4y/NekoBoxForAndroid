package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.onDefaultDispatcher
import io.nekohasekai.sagernet.ui.profile.ChainSettingsActivity
import io.nekohasekai.sagernet.ui.profile.NetBirdSettingsActivity
import io.nekohasekai.sagernet.ui.profile.ProfileSettingsActivity
import kotlinx.coroutines.launch

/** One discoverable entry point for enrollment, transport and split routing. */
class NetBirdActivity : ThemedActivity(R.layout.layout_settings_activity) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.action_netbird)
            setDisplayHomeAsUpEnabled(true)
        }
        if (savedInstanceState == null) supportFragmentManager.beginTransaction()
            .replace(R.id.settings, Settings()).commit()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    class Settings : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
        }

        override fun onResume() {
            super.onResume()
            // Refresh after an editor saves, cancels or deletes an entry. Never share its cache.
            lifecycleScope.launch {
                val (profiles, rules, subscriptionGroups) = onDefaultDispatcher {
                    Triple(SagerDatabase.proxyDao.getAll(), SagerDatabase.rulesDao.allRules(),
                        SagerDatabase.groupDao.allGroups().filter { it.type == GroupType.SUBSCRIPTION }.map { it.id }.toSet())
                }
                if (!isAdded) return@launch
                preferenceScreen.removeAll()
                fun row(titleText: CharSequence, summaryText: CharSequence? = null, action: (() -> Unit)? = null) =
                    Preference(requireContext()).apply {
                        title = titleText; summary = summaryText; isPersistent = false
                        isSelectable = action != null
                        if (action != null) setOnPreferenceClickListener { action(); true }
                    }
                preferenceScreen.addPreference(row(getString(R.string.netbird_ui_intro), getString(R.string.netbird_chain_summary)))
                preferenceScreen.addPreference(row(getString(R.string.netbird_add_profile)) {
                    startActivity(Intent(requireContext(), NetBirdSettingsActivity::class.java))
                })
                val netbirds = profiles.filter { it.netbirdBean != null }
                if (netbirds.isEmpty()) preferenceScreen.addPreference(row(getString(R.string.netbird_no_profiles)))
                for (profile in netbirds) {
                    val bean = profile.netbirdBean!!
                    val category = PreferenceCategory(requireContext()).apply { title = profile.displayName(); isPersistent = false }
                    preferenceScreen.addPreference(category)
                    category.addPreference(row(getString(R.string.netbird_profile_settings),
                        listOf(bean.managementUrl, bean.hostname, bean.dnsDomains).filter { it.isNotBlank() }.joinToString("\n")) {
                        startActivity(profile.settingIntent(requireContext(), profile.groupId in subscriptionGroups))
                    })
                    val relatedIds = mutableSetOf(profile.id)
                    do {
                        val previousSize = relatedIds.size
                        profiles.filter { it.chainBean?.proxies?.any { id -> id in relatedIds } == true }
                            .forEach { relatedIds.add(it.id) }
                    } while (relatedIds.size != previousSize)
                    val chains = profiles.filter { it.id != profile.id && it.id in relatedIds }
                    for (chain in chains) {
                        val hops = chain.chainBean!!.proxies.mapNotNull { id -> profiles.find { it.id == id }?.displayName() }
                        category.addPreference(row(getString(R.string.netbird_chain_item, chain.displayName()), hops.joinToString(" → ")) {
                            startActivity(Intent(requireContext(), ChainSettingsActivity::class.java)
                                .putExtra(ProfileSettingsActivity.EXTRA_PROFILE_ID, chain.id))
                        })
                        for (outer in chain.chainBean!!.proxies.mapNotNull { id -> profiles.find { it.id == id } }
                            .filter { it.id !in relatedIds }) {
                            category.addPreference(row(getString(R.string.netbird_outer_item, outer.displayName())) {
                                startActivity(outer.settingIntent(requireContext(), outer.groupId in subscriptionGroups))
                            })
                        }
                    }
                    category.addPreference(row(getString(R.string.netbird_add_chain), getString(R.string.netbird_add_chain_summary)) {
                        startActivity(Intent(requireContext(), ChainSettingsActivity::class.java))
                    })
                    val destinations = chains.map { it.id }.toSet() + profile.id
                    for (rule in rules.filter { it.outbound in destinations }) {
                        val details = listOf(if (rule.enabled) getString(R.string.netbird_rule_enabled) else getString(R.string.netbird_rule_disabled),
                            rule.domains, rule.ip).filter { it.isNotBlank() }.joinToString("\n")
                        category.addPreference(row(getString(R.string.netbird_rule_item, rule.name), details) {
                            startActivity(Intent(requireContext(), RouteSettingsActivity::class.java)
                                .putExtra(RouteSettingsActivity.EXTRA_ROUTE_ID, rule.id))
                        })
                    }
                    category.addPreference(row(getString(R.string.netbird_add_rule),
                        getString(if (chains.isEmpty()) R.string.netbird_create_chain_first else R.string.netbird_add_rule_summary),
                        if (chains.isEmpty()) null else ({
                            fun edit(chain: ProxyEntity) {
                                startActivity(Intent(requireContext(), RouteSettingsActivity::class.java)
                                    .putExtra(RouteSettingsActivity.EXTRA_OUTBOUND_ID, chain.id))
                            }
                            if (chains.size == 1) edit(chains.single()) else MaterialAlertDialogBuilder(requireContext())
                                .setTitle(R.string.netbird_choose_chain)
                                .setItems(chains.map { it.displayName() }.toTypedArray()) { _, index -> edit(chains[index]) }.show()
                        })))
                }
            }
        }
    }
}
