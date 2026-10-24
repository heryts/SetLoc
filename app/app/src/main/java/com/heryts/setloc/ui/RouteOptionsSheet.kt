package com.heryts.setloc.ui

import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.heryts.setloc.R
import com.heryts.setloc.route.RouteNative
import com.heryts.setloc.route.RouteRunner
import com.heryts.setloc.utils.PrefManager
import com.heryts.setloc.utils.ext.fmtNum
import com.heryts.setloc.utils.ext.formatDistance
import com.heryts.setloc.utils.ext.formatDuration
import com.heryts.setloc.utils.ext.showToast
import com.google.android.gms.maps.model.LatLng
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.chip.ChipGroup

private fun EditText.num(): Float? =
    text?.toString()?.trim()?.replace(',', '.')?.toFloatOrNull()

internal class RouteOptionsHost(
    val points: () -> List<LatLng>,
    val durationS: () -> Double?,
    val waypointCount: () -> Int,
    val refetchRoute: () -> Unit,
    val refetchElevations: () -> Unit,
    val clearRoute: () -> Unit
)

internal fun AppCompatActivity.openRouteOptions(h: RouteOptionsHost) {
    val sheet = BottomSheetDialog(this)
    val view = layoutInflater.inflate(R.layout.route_options, null)
    val summary = view.findViewById<TextView>(R.id.route_summary)
    val chips = view.findViewById<ChipGroup>(R.id.route_mode)
    val speedEt = view.findViewById<EditText>(R.id.route_speed)
    val speedVarEt = view.findViewById<EditText>(R.id.route_speed_var)
    val unitSp = view.findViewById<android.widget.Spinner>(R.id.route_speed_unit)
    val varUnitSp = view.findViewById<android.widget.Spinner>(R.id.route_speed_var_unit)
    val speedLayout = view.findViewById<TextInputLayout>(R.id.route_speed_layout)
    val speedVarLayout = view.findViewById<TextInputLayout>(R.id.route_speed_var_layout)
    val waitEt = view.findViewById<EditText>(R.id.route_wait)
    val waitEndEt = view.findViewById<EditText>(R.id.route_wait_end)
    val waitLayout = view.findViewById<TextInputLayout>(R.id.route_wait_layout)
    val waitEndLayout = view.findViewById<TextInputLayout>(R.id.route_wait_end_layout)
    val altEt = view.findViewById<EditText>(R.id.route_altitude)
    val altVarEt = view.findViewById<EditText>(R.id.route_altitude_var)
    val altLayout = view.findViewById<TextInputLayout>(R.id.route_altitude_layout)
    val altVarLayout = view.findViewById<TextInputLayout>(R.id.route_altitude_var_layout)
    val altServerCb = view.findViewById<android.widget.CompoundButton>(R.id.route_altitude_server)
    val trafficCb = view.findViewById<android.widget.CompoundButton>(R.id.route_traffic_aware)

    val modeIds = intArrayOf(R.id.mode_walk, R.id.mode_bike, R.id.mode_motor, R.id.mode_car)
    val oldMode = PrefManager.routeMode.coerceIn(0, modeIds.size - 1)
    val distance = RouteNative.routeLength(h.points())

    fun selectedMode() = modeIds.indexOf(chips.checkedChipId).let { if (it < 0) oldMode else it }
    fun autoSpeedKmh() = RouteNative.autoSpeed(
        selectedMode(), distance, if (selectedMode() == oldMode) h.durationS() else null
    )

    val kmPerMile = 1.609344f
    var mph = PrefManager.speedUnitMph
    fun toUnit(kmh: Float) = if (mph) kmh / kmPerMile else kmh
    fun toKmh(v: Float) = if (mph) v * kmPerMile else v
    fun numU(f: Float) = fmtNum(Math.round(f * 10f) / 10f)

    fun refreshSummary() {
        val sp = speedEt.num()?.let { toKmh(it) } ?: autoSpeedKmh()
        summary.text = if (sp > 0f) {
            getString(
                R.string.route_summary,
                formatDistance(distance),
                formatDuration(distance / (sp / 3.6))
            )
        } else formatDistance(distance)
    }

    var varUnit = PrefManager.speedVarUnit.coerceIn(0, 2)
    val varUnitNames = arrayOf("%", "km/h", "MPH")
    fun varToKmh(v: Float, u: Int, speedKmh: Float) = when (u) {
        0 -> speedKmh * v / 100f
        2 -> v * kmPerMile
        else -> v
    }
    fun varFromKmh(kmh: Float, u: Int, speedKmh: Float) = when (u) {
        0 -> if (speedKmh > 0f) kmh / speedKmh * 100f else 0f
        2 -> kmh / kmPerMile
        else -> kmh
    }
    fun currentSpeedKmh() = speedEt.num()?.let { toKmh(it) } ?: autoSpeedKmh()

    var speedAuto = PrefManager.routeSpeed <= 0f
    var varAuto = PrefManager.routeSpeedVarRaw < 0f
    var programmatic = false
    fun setText(et: EditText, t: String) {
        programmatic = true
        et.setText(t)
        programmatic = false
    }

    chips.check(modeIds[oldMode])
    setText(
        speedEt,
        numU(toUnit(if (speedAuto) autoSpeedKmh() else PrefManager.routeSpeed))
    )
    run {
        val sp = currentSpeedKmh()
        val shown = if (varAuto) varFromKmh(sp * 0.08f, varUnit, sp) else PrefManager.routeSpeedVarRaw
        setText(speedVarEt, numU(shown))
    }
    waitEt.setText(fmtNum(PrefManager.routeWaitStart))
    waitEndEt.setText(if (PrefManager.routeWaitEnd < 0f) "" else fmtNum(PrefManager.routeWaitEnd))
    altEt.setText(fmtNum(PrefManager.altitudeManual))
    altVarEt.setText(fmtNum(PrefManager.altitudeVariation))
    altServerCb.isChecked = PrefManager.altitudeFromServer
    trafficCb.isChecked = PrefManager.trafficAware

    fun autoLabel(
        layout: TextInputLayout, et: EditText,
        autoText: () -> String = { getString(R.string.route_auto) }, normal: () -> String
    ): () -> Unit {
        layout.isExpandedHintEnabled = false
        val apply = {
            layout.hint = normal()
            layout.placeholderText = autoText()
        }
        et.doAfterTextChanged { apply() }
        apply()
        return apply
    }
    autoLabel(speedLayout, speedEt) { getString(R.string.route_speed) }
    val refreshVarLabel = autoLabel(speedVarLayout, speedVarEt) { varUnitNames[varUnit] }
    autoLabel(waitLayout, waitEt) { getString(R.string.route_wait_start) }
    autoLabel(waitEndLayout, waitEndEt, { getString(R.string.route_auto_stop) }) { getString(R.string.route_wait_end) }
    autoLabel(altLayout, altEt) { getString(R.string.route_altitude) }
    autoLabel(altVarLayout, altVarEt) { getString(R.string.route_altitude_var) }

    unitSp.adapter = android.widget.ArrayAdapter(
        this, android.R.layout.simple_spinner_item, arrayOf("km/h", "MPH")
    ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
    unitSp.setSelection(if (mph) 1 else 0)
    unitSp.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
            val newMph = pos == 1
            if (newMph == mph) return
            val oldMph = mph
            mph = newMph
            speedEt.num()?.let {
                val kmh = if (oldMph) it * kmPerMile else it
                setText(speedEt, numU(toUnit(kmh)))
            }
            refreshSummary()
        }
        override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
    }

    varUnitSp.adapter = android.widget.ArrayAdapter(
        this, android.R.layout.simple_spinner_item, varUnitNames
    ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
    varUnitSp.setSelection(varUnit)
    varUnitSp.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
            if (pos == varUnit) return
            val old = varUnit
            varUnit = pos
            speedVarEt.num()?.let {
                val sp = currentSpeedKmh()
                setText(speedVarEt, numU(varFromKmh(varToKmh(it, old, sp), pos, sp)))
            }
            refreshVarLabel()
        }
        override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
    }
    refreshSummary()

    speedEt.doAfterTextChanged {
        if (!programmatic) speedAuto = false
        refreshSummary()
    }
    speedVarEt.doAfterTextChanged { if (!programmatic) varAuto = false }
    chips.setOnCheckedStateChangeListener { _, checkedIds ->
        val idx = checkedIds.firstOrNull()?.let { modeIds.indexOf(it) } ?: -1
        if (idx >= 0) {
            setText(speedEt, numU(toUnit(autoSpeedKmh())))
            speedAuto = true
        }
    }

    view.findViewById<View>(R.id.route_clear).setOnClickListener {
        h.clearRoute()
        sheet.dismiss()
    }
    view.findViewById<View>(R.id.route_save).setOnClickListener {
        val speedBlank = speedEt.text.isNullOrBlank()
        val typedRaw = speedEt.num()?.let { toKmh(it) }
        if (!speedBlank && !speedAuto && (typedRaw == null || typedRaw < 1f || typedRaw > 300f)) {
            showToast(getString(R.string.route_invalid_speed))
            return@setOnClickListener
        }
        val typed: Float? = if (speedBlank || speedAuto) null else typedRaw
        val sp = typed ?: autoSpeedKmh()
        val wait = (waitEt.num() ?: 0f).coerceIn(0f, 600f)
        val newMode = selectedMode()
        PrefManager.routeSpeed = typed ?: -1f
        PrefManager.speedVarUnit = varUnit
        PrefManager.routeSpeedVarRaw =
            if (speedVarEt.text.isNullOrBlank() || varAuto) -1f
            else speedVarEt.num()
                ?.coerceIn(0f, if (varUnit == 0) 100f else varFromKmh(sp, varUnit, sp)) ?: -1f
        PrefManager.speedUnitMph = mph
        PrefManager.routeWaitStart = wait
        PrefManager.routeWaitEnd = waitEndEt.num()?.coerceIn(0f, 600f) ?: -1f
        val alt = (altEt.num() ?: 0f).coerceIn(-500f, 9000f)
        val altVar = (altVarEt.num() ?: 0f).coerceIn(0f, 500f)
        val fromServer = altServerCb.isChecked
        val serverChanged = fromServer != PrefManager.altitudeFromServer
        PrefManager.altitudeManual = alt
        PrefManager.altitudeVariation = altVar
        PrefManager.altitudeFromServer = fromServer
        if (!fromServer) PrefManager.altitudeM = alt

        if (!fromServer || (h.points().size >= 2 && (serverChanged || RouteRunner.elevations == null))) {
            h.refetchElevations()
        }
        val trafficChanged = trafficCb.isChecked != PrefManager.trafficAware
        PrefManager.trafficAware = trafficCb.isChecked
        sheet.dismiss()
        if (newMode != oldMode) {
            PrefManager.routeMode = newMode
            h.refetchRoute()
        } else if (trafficChanged && newMode >= 2 && h.waypointCount() >= 2) {
            h.refetchRoute()
        }
    }
    sheet.setContentView(view)
    sheet.behavior.skipCollapsed = true
    sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
    sheet.show()
}
