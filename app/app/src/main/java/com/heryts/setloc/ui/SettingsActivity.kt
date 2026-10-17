package com.heryts.setloc.ui

import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.DigitsKeyListener
import android.view.MenuItem
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import com.heryts.setloc.R
import com.heryts.setloc.databinding.SettingsActivityBinding
import com.heryts.setloc.utils.PrefManager
import rikka.preference.SimpleMenuPreference

class SettingsActivity : AppCompatActivity() {

    private val binding by lazy {
        SettingsActivityBinding.inflate(layoutInflater)
    }

    class SettingPreferenceDataStore : PreferenceDataStore() {
        private fun fmt(f: Float) = if (f % 1f == 0f) f.toInt().toString() else f.toString()

        override fun getBoolean(key: String?, defValue: Boolean): Boolean {
            return when (key) {
                "random_position" -> PrefManager.isRandomPosition
                "altitude_from_server" -> PrefManager.altitudeFromServer
                "route_traffic_aware" -> PrefManager.trafficAware
                else -> throw IllegalArgumentException("Invalid key $key")
            }
        }

        override fun putBoolean(key: String?, value: Boolean) {
            return when (key) {
                "random_position" -> PrefManager.isRandomPosition = value
                "altitude_from_server" -> PrefManager.altitudeFromServer = value
                "route_traffic_aware" -> PrefManager.trafficAware = value
                else -> throw IllegalArgumentException("Invalid key $key")
            }
        }

        override fun getString(key: String?, defValue: String?): String? {
            return when (key) {
                "accuracy_settings" -> PrefManager.accuracy
                "map_type" -> PrefManager.mapType.toString()
                "darkTheme" -> PrefManager.darkTheme.toString()
                "altitude_manual" -> fmt(PrefManager.altitudeManual)
                "altitude_var" -> fmt(PrefManager.altitudeVariation)
                "random_radius" -> fmt(PrefManager.randomRadius)
                else -> throw IllegalArgumentException("Invalid key $key")
            }
        }

        override fun putString(key: String?, value: String?) {
            return when (key) {
                "accuracy_settings" -> PrefManager.accuracy = value
                "map_type" -> PrefManager.mapType = value!!.toInt()
                "darkTheme" -> PrefManager.darkTheme = value!!.toInt()
                "altitude_manual" -> PrefManager.altitudeManual =
                    (value?.toFloatOrNull() ?: 0f).coerceIn(-500f, 9000f)
                "altitude_var" -> PrefManager.altitudeVariation =
                    (value?.toFloatOrNull() ?: 0f).coerceIn(0f, 500f)
                "random_radius" -> PrefManager.randomRadius =
                    (value?.toFloatOrNull() ?: 8f).coerceIn(0f, 100f)
                else -> throw IllegalArgumentException("Invalid key $key")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsPreferenceFragment())
                .commit()
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    class SettingsPreferenceFragment : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager?.preferenceDataStore = SettingPreferenceDataStore()
            setPreferencesFromResource(R.xml.setting, rootKey)

            findPreference<androidx.preference.SwitchPreference>("random_position")?.let {
                if (PrefManager.routeActive) {
                    it.isEnabled = false
                    it.summary = getString(R.string.settings_random_paused)
                }
            }

            findPreference<EditTextPreference>("accuracy_settings")?.let {
                it.summary = "${PrefManager.accuracy} m."
                it.setOnBindEditTextListener { editText ->
                    editText.inputType = InputType.TYPE_CLASS_NUMBER
                    editText.keyListener = DigitsKeyListener.getInstance("0123456789.,")
                    editText.addTextChangedListener(getCommaReplacerTextWatcher(editText))
                }

                it.setOnPreferenceChangeListener { preference, newValue ->
                    preference.summary = "$newValue m."
                    true
                }
            }

            for ((key, signed) in listOf("altitude_manual" to true, "altitude_var" to false, "random_radius" to false)) {
                findPreference<EditTextPreference>(key)?.let { pref ->
                    val show = { v: String? -> pref.summary = "${v ?: "0"} m" }
                    show(preferenceManager.preferenceDataStore?.getString(key, "0"))
                    pref.setOnBindEditTextListener { editText ->
                        editText.inputType = InputType.TYPE_CLASS_NUMBER or
                            InputType.TYPE_NUMBER_FLAG_DECIMAL or
                            (if (signed) InputType.TYPE_NUMBER_FLAG_SIGNED else 0)
                        editText.addTextChangedListener(getCommaReplacerTextWatcher(editText))
                    }
                    pref.setOnPreferenceChangeListener { _, newValue ->
                        val ok = (newValue as? String)?.toFloatOrNull() != null
                        if (ok) show(newValue as String)
                        else Toast.makeText(requireContext(), getString(R.string.enter_valid_input), Toast.LENGTH_SHORT).show()
                        ok
                    }
                }
            }

            findPreference<SimpleMenuPreference>("darkTheme")?.setOnPreferenceChangeListener { _, newValue ->
                val newMode = (newValue as String).toInt()
                if (PrefManager.darkTheme != newMode) {
                    AppCompatDelegate.setDefaultNightMode(newMode)
                    activity?.recreate()
                }
                true
            }
        }

        private fun getCommaReplacerTextWatcher(editText: EditText) = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(editable: Editable) {
                val text = editable.toString()
                if (text.contains(",")) {
                    editText.setText(text.replace(",", "."))
                    editText.setSelection(editText.text.length)
                }
            }
        }
    }
}
