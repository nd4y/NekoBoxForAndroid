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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.SagerNet
import org.json.JSONObject

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

    class Settings : PreferenceFragmentCompat(), SagerConnection.Callback {
        private val connection = SagerConnection(5, true)
        private var refresh: Job? = null
        private var runtimeCategory: PreferenceCategory? = null
        private var peersCategory: PreferenceCategory? = null
        private var statusRow: Preference? = null
        private var jobRow: Preference? = null
        private var diagnoseRow: Preference? = null
        private var cancelRow: Preference? = null
        private val peerRows = linkedMapOf<String, Preference>()

        override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {}
        override fun onServiceConnected(service: ISagerNetService) {}
        override fun onPause() {
            refresh?.cancel(); refresh = null
            connection.disconnect(requireContext())
            super.onPause()
        }
        private fun runtimePreferences() {
            peerRows.clear()
            fun row(titleId: Int, action: (() -> Unit)? = null) = Preference(requireContext()).apply {
                title = getString(titleId); isPersistent = false; isSelectable = action != null
                if (action != null) setOnPreferenceClickListener { action(); true }
            }
            runtimeCategory = PreferenceCategory(requireContext()).apply { title = getString(R.string.netbird_live) }
            preferenceScreen.addPreference(runtimeCategory!!)
            statusRow = row(R.string.netbird_status).also { runtimeCategory!!.addPreference(it) }
            jobRow = row(R.string.netbird_check_summary) {
                MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.netbird_check_summary)
                    .setMessage(R.string.netbird_probe_explanation).setPositiveButton(android.R.string.ok, null).show()
            }.also { runtimeCategory!!.addPreference(it) }
            diagnoseRow = row(R.string.netbird_check_all) { diagnosticAction(false) }.also { runtimeCategory!!.addPreference(it) }
            cancelRow = row(R.string.netbird_cancel_check) { diagnosticAction(true) }.also { runtimeCategory!!.addPreference(it) }
            runtimeCategory!!.addPreference(row(R.string.netbird_reload) { SagerNet.reloadService() })
            peersCategory = PreferenceCategory(requireContext()).apply {
                key = "netbird-runtime-peers"; title = getString(R.string.netbird_peers)
                initialExpandedChildrenCount = 3
            }
            preferenceScreen.addPreference(peersCategory!!)
            connection.connect(requireContext(), this)
            refresh = lifecycleScope.launch {
                while (isAdded) {
                    val result = onDefaultDispatcher {
                        runCatching { connection.service?.let { JSONObject(it.netBirdStatus()) to JSONObject(it.netBirdDiagnostics()) } }.getOrNull()
                    }
                    if (result == null) {
                        statusRow?.summary = getString(R.string.netbird_status_unavailable)
                        jobRow?.summary = getString(R.string.netbird_status_unavailable)
                        diagnoseRow?.isEnabled = false; cancelRow?.isEnabled = false
                        peerRows.values.forEach { peersCategory?.removePreference(it) }; peerRows.clear()
                    } else renderRuntime(result.first, result.second)
                    delay(2000)
                }
            }
        }
        private fun diagnosticAction(cancel: Boolean) {
            lifecycleScope.launch {
                onDefaultDispatcher { runCatching { connection.service?.let { if (cancel) it.cancelNetBirdDiagnostics() else it.startNetBirdDiagnostics() } } }
            }
        }
        private fun stateLabel(state: String) = getString(when (state) {
            "connected" -> R.string.netbird_connected
            "connecting" -> R.string.netbird_connecting
            "disconnected" -> R.string.netbird_disconnected
            "reply" -> R.string.netbird_reply
            "checking" -> R.string.netbird_checking
            "queued" -> R.string.netbird_queued
            "no_reply" -> R.string.netbird_no_reply
            "cancelled" -> R.string.netbird_cancelled
            "running" -> R.string.netbird_running
            "completed" -> R.string.netbird_completed
            "limit" -> R.string.netbird_limit
            else -> R.string.netbird_not_checked
        })
        private fun renderRuntime(status: JSONObject, job: JSONObject) {
            val active = status.optBoolean("active")
            val direct = status.optString("mode") == "direct"
            val connected = getString(R.string.netbird_connected); val disconnected = getString(R.string.netbird_disconnected)
            val roster = status.optJSONArray("peers")
            val rows = if (roster == null) emptyList() else (0 until roster.length()).map { roster.getJSONObject(it) }
            val p2p = rows.count { it.optString("state") == "connected" && !it.optBoolean("relayed") }
            val relays = rows.count { it.optString("state") == "connected" && it.optBoolean("relayed") }
            statusRow?.summary = if (!active) getString(R.string.netbird_inactive) else listOf(
                getString(if (direct) R.string.netbird_direct else R.string.netbird_vless),
                if (direct) getString(R.string.netbird_no_proxy) else getString(R.string.netbird_proxy_path,
                    status.optString("proxy_group"), status.optString("selected_proxy")),
                "Cloud: " + if (status.optBoolean("management_connected")) connected else disconnected,
                "Signal: " + if (status.optBoolean("signal_connected")) connected else disconnected,
                status.optString("fqdn") + " / " + status.optString("ip"),
                getString(R.string.netbird_peer_paths, p2p, relays, rows.size)
            ).joinToString("\n")
            val results = job.optJSONArray("results")
            val byIP = mutableMapOf<String, JSONObject>()
            if (results != null) for (i in 0 until results.length()) { val p=results.getJSONObject(i); byIP[p.optString("ip")]=p }
            val replies=byIP.values.count { it.optString("state")=="reply" }
            jobRow?.summary = (if (job.optBoolean("stale")) getString(R.string.netbird_check_stale) + "\n" else "") + getString(R.string.netbird_check_progress, stateLabel(job.optString("state")), replies, byIP.size) +
                job.optString("finished").takeIf { it.isNotBlank() && !it.startsWith("0001") }?.let { value ->
                    runCatching { "\n" + java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.SHORT)
                        .withLocale(java.util.Locale.getDefault()).withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.parse(value)) }.getOrDefault("")
                }.orEmpty()
            val running=job.optString("state")=="running"
            diagnoseRow?.isEnabled = active && !running
            cancelRow?.isEnabled = running
            val peers = status.optJSONArray("peers")
            val keys = mutableSetOf<String>()
            if (peers != null) for (i in 0 until peers.length()) {
                val peer=peers.getJSONObject(i); val ip=peer.optString("ip");val key=ip+peer.optString("fqdn");keys.add(key)
                val row=peerRows.getOrPut(key) { Preference(requireContext()).apply { isPersistent=false;isSelectable=false;peersCategory!!.addPreference(this) } }
                row.title=peer.optString("fqdn").ifBlank { ip }
                val probe=if (job.optBoolean("stale")) null else byIP[ip]; val state=probe?.optString("state").orEmpty()
                row.summary=listOf(ip, stateLabel(peer.optString("state")) + if (peer.optString("state") == "connected") " / " + getString(if (peer.optBoolean("relayed")) R.string.netbird_relay else R.string.netbird_p2p) else "",
                    stateLabel(state) + if (state=="reply") " (${probe!!.optLong("rtt_ms")} ms)" else "",
                    getString(R.string.netbird_traffic,peer.optLong("bytes_tx"),peer.optLong("bytes_rx")),
                    peer.optString("last_handshake").takeIf { !it.startsWith("0001") && it.isNotBlank() }?.let { getString(R.string.netbird_handshake,it) }.orEmpty()
                ).filter { it.isNotBlank() }.joinToString("\n")
            }
            for (key in peerRows.keys.toList()) if (key !in keys) peersCategory?.removePreference(peerRows.remove(key)!!)
            peersCategory?.title=getString(R.string.netbird_peers_count,keys.size)
        }

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
                if (!isAdded || !isResumed) return@launch
                preferenceScreen.removeAll()
                runtimePreferences()
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
                        listOf(getString(if (bean.connectionMode == "direct") R.string.netbird_direct else R.string.netbird_vless), bean.managementUrl, bean.hostname, bean.dnsDomains).filter { it.isNotBlank() }.joinToString("\n")) {
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
                        category.addPreference(row(getString(R.string.netbird_chain_item, chain.displayName()), (if (bean.connectionMode == "direct") getString(R.string.netbird_standby_saved) + "\n" else "") + hops.joinToString(" → ")) {
                            startActivity(Intent(requireContext(), ChainSettingsActivity::class.java)
                                .putExtra(ProfileSettingsActivity.EXTRA_PROFILE_ID, chain.id))
                        })
                        for (outer in chain.chainBean!!.proxies.mapNotNull { id -> profiles.find { it.id == id } }
                            .filter { it.id !in relatedIds }) {
                            category.addPreference(row(getString(if (bean.connectionMode == "direct") R.string.netbird_standby_proxy else R.string.netbird_outer_item, outer.displayName())) {
                                startActivity(outer.settingIntent(requireContext(), outer.groupId in subscriptionGroups))
                            })
                        }
                    }
                    category.addPreference(row(getString(R.string.netbird_add_chain), getString(R.string.netbird_add_chain_summary)) {
                        startActivity(Intent(requireContext(), ChainSettingsActivity::class.java))
                    })
                    val targets = if (bean.connectionMode == "direct") listOf(profile) + chains else chains
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
                        getString(if (targets.isEmpty()) R.string.netbird_create_chain_first else R.string.netbird_add_rule_summary),
                        if (targets.isEmpty()) null else ({
                            fun edit(chain: ProxyEntity) {
                                startActivity(Intent(requireContext(), RouteSettingsActivity::class.java)
                                    .putExtra(RouteSettingsActivity.EXTRA_OUTBOUND_ID, chain.id))
                            }
                            if (targets.size == 1) edit(targets.single()) else MaterialAlertDialogBuilder(requireContext())
                                .setTitle(R.string.netbird_choose_chain)
                                .setItems(targets.map { it.displayName() }.toTypedArray()) { _, index -> edit(targets[index]) }.show()
                        })))
                }
            }
        }
    }
}
