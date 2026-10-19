package com.heryts.setloc.ui

import android.os.Bundle
import android.text.InputType
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.heryts.setloc.R
import com.heryts.setloc.databinding.SettingHeaderBinding
import com.heryts.setloc.databinding.SettingRowBinding
import com.heryts.setloc.databinding.SettingsActivityBinding
import com.heryts.setloc.utils.PrefManager
import com.heryts.setloc.utils.ext.fmtNum

class SettingsActivity : AppCompatActivity() {
    private val binding by lazy { SettingsActivityBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        build()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun header(title: Int) {
        if (binding.settingsList.childCount > 0) {
            binding.settingsList.addView(layoutInflater.inflate(R.layout.setting_divider, binding.settingsList, false))
        }
        val h = SettingHeaderBinding.inflate(layoutInflater, binding.settingsList, false)
        h.root.setText(title)
        binding.settingsList.addView(h.root)
    }

    private fun row(title: String, icon: Int?): SettingRowBinding {
        val r = SettingRowBinding.inflate(layoutInflater, binding.settingsList, false)
        r.settingTitle.text = title
        if (icon != null) r.settingIcon.setImageResource(icon) else r.settingIcon.visibility = View.INVISIBLE
        binding.settingsList.addView(r.root)
        return r
    }

    private fun setSummary(r: SettingRowBinding, text: String?) {
        r.settingSummary.text = text
        r.settingSummary.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun setEnabled(r: SettingRowBinding, enabled: Boolean) {
        r.root.isEnabled = enabled
        r.settingSwitch.isEnabled = enabled
        r.root.alpha = if (enabled) 1f else 0.5f
    }

    private fun choice(
        title: String, icon: Int, entriesRes: Int, valuesRes: Int,
        get: () -> Int, set: (Int) -> Unit, onChanged: () -> Unit = {}
    ) {
        val entries = resources.getStringArray(entriesRes)
        val values = resources.getStringArray(valuesRes).map { it.toInt() }
        val r = row(title, icon)
        val refresh = { setSummary(r, entries.getOrNull(values.indexOf(get()))) }
        refresh()
        r.root.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setSingleChoiceItems(entries, values.indexOf(get())) { d, which ->
                    d.dismiss()
                    if (values[which] != get()) {
                        set(values[which])
                        refresh()
                        onChanged()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun toggle(
        title: String, icon: Int?, summary: String?, get: () -> Boolean, set: (Boolean) -> Unit,
        onChanged: (Boolean) -> Unit = {}
    ): SettingRowBinding {
        val r = row(title, icon)
        setSummary(r, summary)
        r.settingSwitch.visibility = View.VISIBLE
        r.settingSwitch.isChecked = get()
        r.root.setOnClickListener { r.settingSwitch.toggle() }
        r.settingSwitch.setOnCheckedChangeListener { _, checked ->
            set(checked)
            onChanged(checked)
        }
        return r
    }

    private fun number(
        title: String, icon: Int?, signed: Boolean, get: () -> String, validate: (String) -> Boolean,
        set: (String) -> Unit
    ): SettingRowBinding {
        val r = row(title, icon)
        val refresh = { setSummary(r, "${get()} m") }
        refresh()
        r.root.setOnClickListener {
            val input = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
                    (if (signed) InputType.TYPE_NUMBER_FLAG_SIGNED else 0)
                setText(get())
                setSelection(text.length)
            }
            val pad = (24 * resources.displayMetrics.density).toInt()
            val box = FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
            MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val text = input.text.toString().replace(',', '.')
                    if (validate(text)) {
                        set(text)
                        refresh()
                    } else {
                        Toast.makeText(this, getString(R.string.enter_valid_input), Toast.LENGTH_SHORT).show()
                    }
                }
                .show()
        }
        return r
    }

    private fun build() {
        header(R.string.preference_category_general)
        choice(
            getString(R.string.dark_theme), R.drawable.ic_dark_mode, R.array.theme_texts, R.array.theme_values,
            { PrefManager.darkTheme }, { PrefManager.darkTheme = it },
            { AppCompatDelegate.setDefaultNightMode(PrefManager.darkTheme) }
        )
        choice(
            getString(R.string.settings_map), R.drawable.ic_map_type, R.array.map_type, R.array.map_value,
            { PrefManager.mapType }, { PrefManager.mapType = it }
        )

        header(R.string.settings_location)
        number(
            getString(R.string.settings_of_accuracy), R.drawable.ic_accuracy, false,
            { PrefManager.accuracy ?: "10" }, { it.toFloatOrNull() != null }, { PrefManager.accuracy = it }
        )
        lateinit var radius: SettingRowBinding
        val paused = PrefManager.routeActive
        toggle(
            getString(R.string.settings_random_position), R.drawable.ic_random_position,
            if (paused) getString(R.string.settings_random_paused)
            else getString(R.string.settings_random_position_summary),
            { PrefManager.isRandomPosition }, { PrefManager.isRandomPosition = it },
            { on -> setEnabled(radius, on) }
        ).also { if (paused) setEnabled(it, false) }
        radius = number(
            getString(R.string.settings_random_radius), null, false,
            { fmtNum(PrefManager.randomRadius) }, { it.toFloatOrNull() != null },
            { PrefManager.randomRadius = it.toFloat().coerceIn(0f, 100f) }
        )
        setEnabled(radius, PrefManager.isRandomPosition)
        toggle(
            getString(R.string.settings_altitude_server), null, getString(R.string.settings_altitude_server_summary),
            { PrefManager.altitudeFromServer }, { PrefManager.altitudeFromServer = it }
        )
        number(
            getString(R.string.settings_altitude), null, true,
            { fmtNum(PrefManager.altitudeManual) }, { it.toFloatOrNull() != null },
            { PrefManager.altitudeManual = it.toFloat().coerceIn(-500f, 9000f) }
        )
        number(
            getString(R.string.settings_altitude_var), null, false,
            { fmtNum(PrefManager.altitudeVariation) }, { it.toFloatOrNull() != null },
            { PrefManager.altitudeVariation = it.toFloat().coerceIn(0f, 500f) }
        )

        header(R.string.settings_route)
        toggle(
            getString(R.string.settings_traffic_aware), null, getString(R.string.settings_traffic_aware_summary),
            { PrefManager.trafficAware }, { PrefManager.trafficAware = it }
        )

        header(R.string.settings_network)
        toggle(
            getString(R.string.settings_hide_net), null, getString(R.string.settings_hide_net_summary),
            { PrefManager.hideNetwork }, { PrefManager.hideNetwork = it }
        )
        toggle(
            getString(R.string.settings_hide_bt), null, getString(R.string.settings_hide_bt_summary),
            { PrefManager.hideBluetooth }, { PrefManager.hideBluetooth = it }
        )
    }
}
