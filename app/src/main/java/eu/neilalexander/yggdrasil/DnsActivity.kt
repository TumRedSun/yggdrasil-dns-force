package eu.neilalexander.yggdrasil

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.preference.PreferenceManager
import com.google.android.material.textfield.TextInputEditText

const val KEY_DNS_SERVERS = "dns_servers"
const val KEY_DNS_VERSION = "dns_version"
const val KEY_ENABLE_CHROME_FIX = "enable_chrome_fix"

// MODIFICATION: keys for the new "Force system DNS" feature.
const val KEY_FORCE_SYSTEM_DNS = "force_system_dns"
const val KEY_FORCE_DNS_UPSTREAM = "force_dns_upstream"

class DnsActivity : AppCompatActivity() {
    private lateinit var config: ConfigurationProxy
    private lateinit var inflater: LayoutInflater

    private lateinit var serversTableLayout: TableLayout
    private lateinit var serversTableLabel: TextView
    private lateinit var serversTableHint: TextView
    private lateinit var addServerButton: ImageButton
    private lateinit var enableChromeFix: Switch

    // MODIFICATION: UI elements for "Force system DNS" feature.
    private lateinit var enableForceDns: Switch
    private lateinit var forceDnsUpstreamRow: TableRow
    private lateinit var forceDnsUpstreamValue: TextView

    private lateinit var servers: MutableList<String>
    private lateinit var preferences: SharedPreferences

    private lateinit var defaultDnsServers: HashMap<String, Pair<String, String>>

    @SuppressLint("ApplySharedPref")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dns)

        config = ConfigurationProxy(applicationContext)
        inflater = LayoutInflater.from(this)

        val descriptionRevertron = getString(R.string.dns_server_info_revertron)
        // Here we can add some other DNS servers in a future
        defaultDnsServers = hashMapOf(
            "308:62:45:62::" to Pair(getString(R.string.location_amsterdam), descriptionRevertron),
            "308:84:68:55::" to Pair(getString(R.string.location_frankfurt), descriptionRevertron),
            "308:25:40:bd::" to Pair(getString(R.string.location_bratislava), descriptionRevertron),
            "308:c8:48:45::" to Pair(getString(R.string.location_buffalo), descriptionRevertron),
        )

        serversTableLayout = findViewById(R.id.configuredDnsTableLayout)
        serversTableLabel = findViewById(R.id.configuredDnsLabel)
        serversTableHint = findViewById(R.id.configuredDnsHint)
        enableChromeFix = findViewById(R.id.enableChromeFix)

        // MODIFICATION: bind the new "Force system DNS" UI.
        enableForceDns = findViewById(R.id.enableForceDns)
        forceDnsUpstreamRow = findViewById(R.id.forceDnsUpstreamRow)
        forceDnsUpstreamValue = findViewById(R.id.forceDnsUpstreamValue)

        addServerButton = findViewById(R.id.addServerButton)
        addServerButton.setOnClickListener {
            val view = inflater.inflate(R.layout.dialog_add_dns_server, null)
            val input = view.findViewById<TextInputEditText>(R.id.addDnsInput)
            val builder: AlertDialog.Builder = AlertDialog.Builder(ContextThemeWrapper(this, R.style.YggdrasilDialogs))
            builder.setTitle(getString(R.string.dns_add_server_dialog_title))
            builder.setView(view)
            builder.setPositiveButton(getString(R.string.add)) { _, _ ->
                val server = input.text.toString()
                if (!servers.contains(server)) {
                    servers.add(server)
                    preferences.edit().apply {
                        putString(KEY_DNS_SERVERS, servers.joinToString(","))
                        commit()
                    }
                    updateConfiguredServers()
                } else {
                    Toast.makeText(this, R.string.dns_already_added_server, Toast.LENGTH_SHORT).show()
                }
            }
            builder.setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.cancel()
            }
            builder.show()
        }

        enableChromeFix.setOnCheckedChangeListener { _, isChecked ->
            preferences.edit().apply {
                putBoolean(KEY_ENABLE_CHROME_FIX, isChecked)
                commit()
            }
        }

        val enableChromeFixPanel = findViewById<TableRow>(R.id.enableChromeFixPanel)
        enableChromeFixPanel.setOnClickListener {
            enableChromeFix.toggle()
        }

        // MODIFICATION: wire up the "Force system DNS" toggle.
        enableForceDns.setOnCheckedChangeListener { _, isChecked ->
            preferences.edit().apply {
                putBoolean(KEY_FORCE_SYSTEM_DNS, isChecked)
                commit()
            }
            updateForceDnsUi()
        }
        val enableForceDnsPanel = findViewById<TableRow>(R.id.enableForceDnsPanel)
        enableForceDnsPanel.setOnClickListener {
            enableForceDns.toggle()
        }

        // MODIFICATION: tap on the upstream row opens an edit dialog.
        forceDnsUpstreamRow.setOnClickListener {
            showEditUpstreamDialog()
        }

        preferences = PreferenceManager.getDefaultSharedPreferences(this.baseContext)
        val serverString = preferences.getString(KEY_DNS_SERVERS, "")
        servers = if (serverString!!.isNotEmpty()) {
            serverString.split(",").toMutableList()
        } else {
            mutableListOf()
        }
        updateUsableServers()
    }

    override fun onResume() {
        super.onResume()
        updateConfiguredServers()
        enableChromeFix.isChecked = preferences.getBoolean(KEY_ENABLE_CHROME_FIX, false)

        // MODIFICATION: load force-DNS state.
        enableForceDns.isChecked = preferences.getBoolean(KEY_FORCE_SYSTEM_DNS, true)
        val upstream = preferences.getString(KEY_FORCE_DNS_UPSTREAM, DEFAULT_FORCE_DNS_UPSTREAM)
            ?: DEFAULT_FORCE_DNS_UPSTREAM
        forceDnsUpstreamValue.text = upstream
        updateForceDnsUi()
    }

    // MODIFICATION: show/hide the upstream row depending on the toggle.
    @SuppressLint("ApplySharedPref")
    private fun updateForceDnsUi() {
        forceDnsUpstreamRow.visibility = if (enableForceDns.isChecked) View.VISIBLE else View.GONE
    }

    // MODIFICATION: dialog to edit the upstream DNS server IP.
    @SuppressLint("ApplySharedPref")
    private fun showEditUpstreamDialog() {
        val current = preferences.getString(KEY_FORCE_DNS_UPSTREAM, DEFAULT_FORCE_DNS_UPSTREAM)
            ?: DEFAULT_FORCE_DNS_UPSTREAM
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(current)
            hint = "200:xxxx::xxxx"
        }
        val padding = (16 * resources.displayMetrics.density).toInt()
        input.setPadding(padding, padding, padding, padding)

        val builder: AlertDialog.Builder = AlertDialog.Builder(ContextThemeWrapper(this, R.style.YggdrasilDialogs))
        builder.setTitle(getString(R.string.force_dns_upstream_dialog_title))
        builder.setMessage(getString(R.string.force_dns_upstream_dialog_message))
        builder.setView(input)
        builder.setPositiveButton(getString(R.string.save)) { _, _ ->
            val value = input.text.toString().trim()
            if (value.isNotEmpty()) {
                preferences.edit().apply {
                    putString(KEY_FORCE_DNS_UPSTREAM, value)
                    commit()
                }
                forceDnsUpstreamValue.text = value
            }
        }
        builder.setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
            dialog.cancel()
        }
        builder.show()
    }

    @SuppressLint("ApplySharedPref")
    private fun updateConfiguredServers() {
        when (servers.size) {
            0 -> {
                serversTableLayout.visibility = View.GONE
                serversTableLabel.text = getString(R.string.dns_no_configured_servers)
                serversTableHint.text = getText(R.string.dns_configured_servers_hint_empty)
            }
            else -> {
                serversTableLayout.visibility = View.VISIBLE
                serversTableLabel.text = getString(R.string.dns_configured_servers)
                serversTableHint.text = getText(R.string.dns_configured_servers_hint)

                serversTableLayout.removeAllViewsInLayout()
                for (i in servers.indices) {
                    val server = servers[i]
                    val view = inflater.inflate(R.layout.peers_configured, null)
                    view.findViewById<TextView>(R.id.addressValue).text = server
                    view.findViewById<ImageButton>(R.id.deletePeerButton).tag = i

                    view.findViewById<ImageButton>(R.id.deletePeerButton).setOnClickListener { button ->
                        val builder: AlertDialog.Builder = AlertDialog.Builder(ContextThemeWrapper(this, R.style.YggdrasilDialogs))
                        builder.setTitle(getString(R.string.dns_remove_title, server))
                        builder.setPositiveButton(getString(R.string.remove)) { dialog, _ ->
                            servers.removeAt(button.tag as Int)
                            preferences.edit().apply {
                                this.putString(KEY_DNS_SERVERS, servers.joinToString(","))
                                this.commit()
                            }
                            dialog.dismiss()
                            updateConfiguredServers()
                        }
                        builder.setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                            dialog.cancel()
                        }
                        builder.show()
                    }
                    serversTableLayout.addView(view)
                }
            }
        }
    }

    @SuppressLint("ApplySharedPref")
    private fun updateUsableServers() {
        val usableTableLayout: TableLayout = findViewById(R.id.usableDnsTableLayout)

        defaultDnsServers.forEach {
            val server = it.key
            val infoPair = it.value
            val view = inflater.inflate(R.layout.dns_server_usable, null)
            view.findViewById<TextView>(R.id.serverValue).text = server
            val addButton = view.findViewById<ImageButton>(R.id.addButton)
            addButton.tag = server

            addButton.setOnClickListener { button ->
                val serverString = button.tag as String
                if (!servers.contains(serverString)) {
                    servers.add(serverString)
                    preferences.edit().apply {
                        this.putString(KEY_DNS_SERVERS, servers.joinToString(","))
                        this.commit()
                    }
                    updateConfiguredServers()
                } else {
                    Toast.makeText(this, R.string.dns_already_added_server, Toast.LENGTH_SHORT).show()
                }
            }
            view.setOnLongClickListener {
                val builder: AlertDialog.Builder = AlertDialog.Builder(ContextThemeWrapper(this, R.style.YggdrasilDialogs))
                builder.setTitle(getString(R.string.dns_server_info_dialog_title))
                builder.setMessage("${infoPair.first}\n\n${infoPair.second}")
                builder.setPositiveButton(getString(R.string.ok)) { dialog, _ ->
                    dialog.dismiss()
                }
                builder.show()
                true
            }

            usableTableLayout.addView(view)
        }
    }
}
