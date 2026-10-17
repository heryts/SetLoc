package com.heryts.setloc.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.content.Intent
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.heryts.setloc.BuildConfig
import com.heryts.setloc.R
import com.heryts.setloc.adapter.FavListAdapter
import com.heryts.setloc.databinding.ActivityMapBinding
import com.heryts.setloc.route.ElevationClient
import com.heryts.setloc.route.AutoSpeed
import com.heryts.setloc.route.RouteClient
import com.heryts.setloc.route.RouteRunner
import com.heryts.setloc.route.RouteService
import com.heryts.setloc.utils.PrefManager
import com.heryts.setloc.utils.NotificationsChannel
import com.heryts.setloc.utils.ext.*
import com.heryts.setloc.viewmodel.MainViewModel
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.IOException
import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.properties.Delegates

@AndroidEntryPoint
class MapActivity : AppCompatActivity(), OnMapReadyCallback, GoogleMap.OnMapClickListener,
    GoogleMap.OnMapLongClickListener {
    private val binding by lazy { ActivityMapBinding.inflate(layoutInflater) }
    private lateinit var mMap: GoogleMap
    private val viewModel by viewModels<MainViewModel>()
    private val notificationsChannel by lazy { NotificationsChannel() }
    private var favListAdapter: FavListAdapter = FavListAdapter()
    private var mMarker: Marker? = null
    private var mLatLng: LatLng? = null
    private var lat by Delegates.notNull<Double>()
    private var lon by Delegates.notNull<Double>()
    private lateinit var alertDialog: MaterialAlertDialogBuilder
    private lateinit var dialog: AlertDialog

    private val routeWaypoints = mutableListOf<LatLng>()
    private val routeMarkers = mutableListOf<Marker>()
    private var routeLine: Polyline? = null
    private var routePoints: List<LatLng> = emptyList()
    private var routeDurationS: Double? = null
    private var routeLoading = false
    private var routeJob: Job? = null
    private var elevationJob: Job? = null
    private var clearHintShown = false
    private var wasRouteRunning = false
    private val routeReady get() = routePoints.size >= 2 && !routeLoading

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!RouteRunner.isRunning) {
            if (PrefManager.routeActive) PrefManager.routeActive = false
            if (PrefManager.routePlay) PrefManager.routePlay = false
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        initializeMap()
        setFloatActionButton()
        setRouteButton()
        observeRoute()
    }

    private fun initializeMap() {
        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment?
        mapFragment?.getMapAsync(this)
    }

    private fun setFloatActionButton() {
        if (viewModel.isStarted) {
            binding.start.visibility = View.GONE
            binding.stop.visibility = View.VISIBLE
        }

        binding.start.setOnClickListener {
            if (mLatLng == null || !::mMap.isInitialized) {
                showToast("Map belum siap")
                return@setOnClickListener
            }
            if (routeReady) {
                if (systemModeActive()) startRoute() else showSystemModeWarning { startRoute() }
                return@setOnClickListener
            }
            if (routeWaypoints.size >= 2) {
                if (!routeLoading) fetchRoute(dropLastOnFail = false)
                return@setOnClickListener
            }
            viewModel.update(true, lat, lon)
            PrefManager.altitudeM = PrefManager.altitudeManual
            if (PrefManager.altitudeFromServer) {
                val here = LatLng(lat, lon)
                lifecycleScope.launch {
                    ElevationClient.fetch(this@MapActivity, listOf(here))?.firstOrNull()
                        ?.let { PrefManager.altitudeM = it }
                }
            }
            mMarker?.position = mLatLng!!
            mMarker?.isVisible = true
            binding.start.visibility = View.GONE
            binding.stop.visibility = View.VISIBLE
            lifecycleScope.launch {
                mLatLng?.getAddress(this@MapActivity)?.let { address ->
                    address.collect{ value ->
                        showStartNotification(value)
                    }
                }
            }
            showToast(getString(R.string.location_set))
        }
        binding.stop.setOnClickListener {
            RouteRunner.stop()
            (mLatLng ?: LatLng(viewModel.getLat, viewModel.getLng))
                .let { viewModel.update(false, it.latitude, it.longitude) }
            mMarker?.isVisible = false
            binding.stop.visibility = View.GONE
            binding.start.visibility = View.VISIBLE
            cancelNotification()
            showToast(getString(R.string.location_unset))
            updateRouteFab()
        }
    }

    @SuppressLint("MissingPermission")
    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        with(mMap){
            mapType = viewModel.mapType
            val zoom = 12.0f
            lat = viewModel.getLat
            lon  = viewModel.getLng
            mLatLng = LatLng(lat, lon)
            mMarker = addMarker(
                MarkerOptions().position(mLatLng!!).draggable(false)
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)).visible(false)
            )
            mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(mLatLng!!, zoom))
            uiSettings.isZoomControlsEnabled = true
            if (checkSinglePermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                mMap.isMyLocationEnabled = true
            }else {
                val permList = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                ActivityCompat.requestPermissions(
                    this@MapActivity,
                    permList,
                    99
                )
            }
            setPadding(0,0,0,170)
            setOnMapClickListener(this@MapActivity)
            setOnMapLongClickListener(this@MapActivity)
            if (viewModel.isStarted){
                mMarker?.let {
                    it.isVisible = true
                    it.showInfoWindow()
                }
            }
        }
    }

    override fun onMapClick(latLng: LatLng) {
        if (RouteRunner.isRunning) return
        if (routeWaypoints.isNotEmpty()) clearRoute()
        mLatLng = latLng
        mMarker?.let {
            it.position = latLng
            it.isVisible = true
            mMap.animateCamera(CameraUpdateFactory.newLatLng(latLng))
            lat = latLng.latitude
            lon = latLng.longitude
        }
    }

    override fun onMapLongClick(latLng: LatLng) {
        if (RouteRunner.isRunning) {
            showToast(getString(R.string.route_stop_first))
            return
        }
        if (routeWaypoints.size >= RouteClient.MAX_POINTS) {
            showToast(getString(R.string.route_max_points, RouteClient.MAX_POINTS))
            return
        }
        val marker = mMap.addMarker(MarkerOptions().position(latLng)) ?: return
        routeWaypoints.add(latLng)
        routeMarkers.add(marker)
        refreshRouteMarkers()
        if (routeWaypoints.size == 1) {
            showToast(getString(R.string.route_pick_start))
            updateRouteFab()
        } else {
            fetchRoute(dropLastOnFail = true)
        }
    }

    private fun refreshRouteMarkers() {
        val last = routeMarkers.size - 1
        routeMarkers.forEachIndexed { i, m ->
            val hue = when (i) {
                0 -> BitmapDescriptorFactory.HUE_GREEN
                last -> BitmapDescriptorFactory.HUE_AZURE
                else -> BitmapDescriptorFactory.HUE_YELLOW
            }
            m.setIcon(BitmapDescriptorFactory.defaultMarker(hue))
            m.title = (i + 1).toString()
        }
    }

    private fun clearRoute() {
        routeJob?.cancel()
        routeJob = null
        elevationJob?.cancel()
        elevationJob = null
        routeLoading = false
        routeMarkers.forEach { it.remove() }
        routeMarkers.clear()
        routeWaypoints.clear()
        routeLine?.remove()
        routeLine = null
        routePoints = emptyList()
        RouteRunner.elevations = null
        updateRouteFab()
    }

    private fun removeLastWaypoint() {
        if (routeWaypoints.isEmpty() || RouteRunner.isRunning) return
        routeJob?.cancel()
        routeJob = null
        elevationJob?.cancel()
        elevationJob = null
        routeLoading = false
        routeWaypoints.removeAt(routeWaypoints.size - 1)
        routeMarkers.removeAt(routeMarkers.size - 1).remove()
        routeLine?.remove()
        routeLine = null
        routePoints = emptyList()
        RouteRunner.elevations = null
        refreshRouteMarkers()
        if (routeWaypoints.isNotEmpty() && !clearHintShown) {
            clearHintShown = true
            showToast(getString(R.string.route_clear_hint))
        }
        if (routeWaypoints.size >= 2) fetchRoute(dropLastOnFail = false) else updateRouteFab()
    }

    private fun fetchRoute(dropLastOnFail: Boolean) {
        if (routeWaypoints.size < 2) return
        val stops = routeWaypoints.toList()
        routeLoading = true
        elevationJob?.cancel()
        RouteRunner.elevations = null
        updateRouteFab()
        if (!isNetworkConnected()) {
            showToast(getString(R.string.no_internet))
            routeFetchFailed(dropLastOnFail)
            return
        }
        showToast(getString(R.string.route_searching))
        routeJob?.cancel()
        routeJob = lifecycleScope.launch {
            try {
                val result = RouteClient.fetch(this@MapActivity, stops, PrefManager.routeMode)
                routeLine?.remove()
                routePoints = result.points
                routeDurationS = result.durationS.takeIf { it > 0.0 }
                routeLine = mMap.addPolyline(
                    PolylineOptions()
                        .addAll(result.points)
                        .width(12f)
                        .color(ContextCompat.getColor(this@MapActivity, R.color.primary))
                        .zIndex(1f)
                )
                routeLoading = false
                try {
                    val bounds = LatLngBounds.Builder()
                    result.points.forEach { bounds.include(it) }
                    mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 140))
                } catch (_: Exception) {
                }
                updateRouteFab()
                fetchElevations(result.points)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                showToast(e.message ?: getString(R.string.address_not_found))
                routeFetchFailed(dropLastOnFail)
            }
        }
    }

    private fun routeFetchFailed(dropLast: Boolean) {
        routeLoading = false
        if (dropLast && routeWaypoints.size > 1) {
            routeWaypoints.removeAt(routeWaypoints.size - 1)
            routeMarkers.removeAt(routeMarkers.size - 1).remove()
            refreshRouteMarkers()
        }
        if (routeWaypoints.size < 2) {
            routeLine?.remove()
            routeLine = null
            routePoints = emptyList()
        } else if (routePoints.size >= 2) {
            fetchElevations(routePoints)
        }
        updateRouteFab()
    }

    private fun fetchElevations(points: List<LatLng>) {
        elevationJob?.cancel()
        if (!PrefManager.altitudeFromServer) {
            RouteRunner.elevations = null
            return
        }
        elevationJob = lifecycleScope.launch {
            val samples = ElevationClient.sample(points, 100)
            ElevationClient.fetch(this@MapActivity, samples)?.let { RouteRunner.elevations = it }
        }
    }

    private fun setRouteButton() {
        binding.route.setOnClickListener { if (routeReady) showRouteOptions() }
        binding.routeDelete.setOnClickListener { removeLastWaypoint() }
        binding.routeDelete.setOnLongClickListener { clearRoute(); true }
    }

    private fun updateRouteFab() {
        val idle = !RouteRunner.isRunning && binding.start.visibility == View.VISIBLE
        binding.route.visibility = if (routeReady && idle) View.VISIBLE else View.GONE
        binding.routeDelete.visibility = if (routeWaypoints.isNotEmpty() && idle) View.VISIBLE else View.GONE
    }

    private fun systemModeActive(): Boolean = try {
        val v = Settings.Global.getString(contentResolver, "gpsz_sys")
        val enabled = v == "1" || v == "2"
        val boot = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)
        val live = Settings.Global.getString(contentResolver, "gpsz_sys_live")?.toIntOrNull()
        enabled && boot >= 0 && live == boot
    } catch (e: Exception) {
        true
    }

    private fun showSystemModeWarning(onContinue: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.route_needs_system_title)
            .setMessage(R.string.route_needs_system_msg)
            .setPositiveButton(R.string.route_needs_system_continue) { d, _ -> d.dismiss(); onContinue() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startRoute() {
        if (!routeReady) return
        val first = routePoints.first()
        mLatLng = first
        lat = first.latitude
        lon = first.longitude
        mMarker?.apply {
            position = first
            isVisible = true
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            !checkSinglePermission(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
        val speed = PrefManager.routeSpeed.takeIf { it > 0f }
            ?: AutoSpeed.kmh(PrefManager.routeMode, RouteRunner.routeLength(routePoints), routeDurationS)
        RouteRunner.start(
            routePoints, speed,
            PrefManager.routeWaitStart, PrefManager.routeWaitEnd, PrefManager.routeSpeedVarFor(speed)
        )
        ContextCompat.startForegroundService(this, Intent(this, RouteService::class.java))
        binding.start.visibility = View.GONE
        binding.stop.visibility = View.VISIBLE
        updateRouteFab()
        mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(first, 16f))
    }

    private fun observeRoute() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RouteRunner.state.collect { s ->
                    if (s.running) wasRouteRunning = true
                    val pos = s.position
                    if (pos != null && (s.running || (s.finished && !s.spoofOff))) {
                        mLatLng = pos
                        lat = pos.latitude
                        lon = pos.longitude
                        if (::mMap.isInitialized) {
                            mMarker?.apply {
                                position = pos
                                isVisible = true
                            }
                            mMap.animateCamera(CameraUpdateFactory.newLatLng(pos))
                        }
                        binding.start.visibility = View.GONE
                        binding.stop.visibility = View.VISIBLE
                    }
                    if (s.finished && wasRouteRunning) {
                        wasRouteRunning = false
                        showToast(getString(R.string.route_arrived))
                        if (s.spoofOff) {
                            mMarker?.isVisible = false
                            binding.stop.visibility = View.GONE
                            binding.start.visibility = View.VISIBLE
                        }
                    } else if (!s.running && !s.finished && wasRouteRunning) {
                        wasRouteRunning = false
                        mMarker?.isVisible = false
                        binding.stop.visibility = View.GONE
                        binding.start.visibility = View.VISIBLE
                    }
                    updateRouteFab()
                }
            }
        }
    }

    private fun showRouteOptions() {
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
        val distance = RouteRunner.routeLength(routePoints)

        fun selectedMode() = modeIds.indexOf(chips.checkedChipId).let { if (it < 0) oldMode else it }
        fun autoSpeedKmh() = AutoSpeed.kmh(
            selectedMode(), distance, if (selectedMode() == oldMode) routeDurationS else null
        )

        fun num(f: Float) = if (f % 1f == 0f) f.toInt().toString() else f.toString()

        val kmPerMile = 1.609344f
        var mph = PrefManager.speedUnitMph
        fun toUnit(kmh: Float) = if (mph) kmh / kmPerMile else kmh
        fun toKmh(v: Float) = if (mph) v * kmPerMile else v
        fun numU(f: Float) = num(Math.round(f * 10f) / 10f)

        fun refreshSummary() {
            val sp = speedEt.text.toString().toFloatOrNull()?.let { toKmh(it) } ?: autoSpeedKmh()
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
        fun currentSpeedKmh() = speedEt.text.toString().toFloatOrNull()?.let { toKmh(it) } ?: autoSpeedKmh()

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
        waitEt.setText(num(PrefManager.routeWaitStart))
        waitEndEt.setText(if (PrefManager.routeWaitEnd < 0f) "" else num(PrefManager.routeWaitEnd))
        altEt.setText(num(PrefManager.altitudeManual))
        altVarEt.setText(num(PrefManager.altitudeVariation))
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
                speedEt.text.toString().toFloatOrNull()?.let {
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
                speedVarEt.text.toString().toFloatOrNull()?.let {
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
            clearRoute()
            sheet.dismiss()
        }
        view.findViewById<View>(R.id.route_save).setOnClickListener {
            val speedBlank = speedEt.text.isNullOrBlank()
            val typedRaw = speedEt.text.toString().toFloatOrNull()?.let { toKmh(it) }
            if (!speedBlank && !speedAuto && (typedRaw == null || typedRaw < 1f || typedRaw > 300f)) {
                showToast(getString(R.string.route_invalid_speed))
                return@setOnClickListener
            }
            val typed: Float? = if (speedBlank || speedAuto) null else typedRaw
            val sp = typed ?: autoSpeedKmh()
            val wait = (waitEt.text.toString().toFloatOrNull() ?: 0f).coerceIn(0f, 600f)
            val newMode = modeIds.indexOf(chips.checkedChipId).let { if (it < 0) oldMode else it }
            PrefManager.routeSpeed = typed ?: -1f
            PrefManager.speedVarUnit = varUnit
            PrefManager.routeSpeedVarRaw =
                if (speedVarEt.text.isNullOrBlank() || varAuto) -1f
                else speedVarEt.text.toString().toFloatOrNull()
                    ?.coerceIn(0f, if (varUnit == 0) 100f else varFromKmh(sp, varUnit, sp)) ?: -1f
            PrefManager.speedUnitMph = mph
            PrefManager.routeWaitStart = wait
            PrefManager.routeWaitEnd = waitEndEt.text.toString().toFloatOrNull()?.coerceIn(0f, 600f) ?: -1f
            val alt = (altEt.text.toString().toFloatOrNull() ?: 0f).coerceIn(-500f, 9000f)
            val altVar = (altVarEt.text.toString().toFloatOrNull() ?: 0f).coerceIn(0f, 500f)
            val fromServer = altServerCb.isChecked
            val serverChanged = fromServer != PrefManager.altitudeFromServer
            PrefManager.altitudeManual = alt
            PrefManager.altitudeVariation = altVar
            PrefManager.altitudeFromServer = fromServer
            if (!fromServer) PrefManager.altitudeM = alt

            if (!fromServer || (routePoints.size >= 2 && (serverChanged || RouteRunner.elevations == null))) {
                fetchElevations(routePoints)
            }
            val trafficChanged = trafficCb.isChecked != PrefManager.trafficAware
            PrefManager.trafficAware = trafficCb.isChecked
            sheet.dismiss()
            if (newMode != oldMode) {
                PrefManager.routeMode = newMode
                fetchRoute(dropLastOnFail = false)
            } else if (trafficChanged && newMode >= 2 && routeWaypoints.size >= 2) {
                fetchRoute(dropLastOnFail = false)
            }
        }
        sheet.setContentView(view)
        sheet.behavior.skipCollapsed = true
        sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        sheet.show()
    }

    private fun formatDistance(m: Double): String =
        if (m < 1000) "${m.toInt()} m" else String.format(Locale.US, "%.1f km", m / 1000.0)

    private fun formatDuration(sec: Double): String {
        val s = sec.toLong()
        return when {
            s < 60 -> "$s s"
            s < 3600 -> "${(s + 30) / 60} min"
            else -> "${s / 3600} h ${(s % 3600) / 60} min"
        }
    }

    private fun moveMapToNewLocation() {
        if (!::mMap.isInitialized) return
        val latLng = LatLng(lat, lon)
        mLatLng = latLng
        mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 12.0f))
        mMarker?.apply {
            position = latLng
            isVisible = true
            showInfoWindow()
        }
    }

    private fun aboutDialog(){
        alertDialog = MaterialAlertDialogBuilder(this)
        layoutInflater.inflate(R.layout.about,null).apply {
            val  tittle = findViewById<TextView>(R.id.design_about_title)
            val  version = findViewById<TextView>(R.id.design_about_version)
            val  info = findViewById<TextView>(R.id.design_about_info)
            tittle.text = getString(R.string.app_name)
            version.text = BuildConfig.VERSION_NAME
            info.text = getString(R.string.about_info)
        }.run {
            alertDialog.setView(this)
            alertDialog.show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu,menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
       when(item.itemId){
            R.id.search -> searchDialog()
            R.id.add_fav -> addFavouriteDialog()
            R.id.get_favourite -> openFavouriteListDialog()
            R.id.settings -> startActivity(Intent(this,SettingsActivity::class.java))
            R.id.about -> aboutDialog()

            else -> super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun searchDialog() {
        alertDialog = MaterialAlertDialogBuilder(this)
        val view = layoutInflater.inflate(R.layout.dialog_layout,null)
        val editText = view.findViewById<EditText>(R.id.search_edittxt)
        editText.hint = getString(R.string.search_hint)
        val progressBar = buildProgressDialog("Searching...")
        alertDialog.setTitle("Search")
        alertDialog.setView(view)
        alertDialog.setPositiveButton("Search") { _, _ ->
            if (isNetworkConnected()) {
                val getInput = editText.text.toString().trim()
                if (getInput.isNotEmpty()) {
                    lifecycleScope.launch(Dispatchers.Main) {
                        getSearchAddress(getInput)
                            .catch { emit(SearchProgress.Fail(getString(R.string.address_not_found))) }
                            .collect { result ->
                                when (result) {
                                    is SearchProgress.Progress -> {
                                        if (!isFinishing) progressBar.show()
                                    }
                                    is SearchProgress.Complete -> {
                                        progressBar.dismissSafe()
                                        lat = result.lat
                                        lon = result.lon
                                        moveMapToNewLocation()
                                    }
                                    is SearchProgress.Fail -> {
                                        progressBar.dismissSafe()
                                        showToast(result.error ?: getString(R.string.address_not_found))
                                    }
                                }
                            }
                    }
                }
            } else {
                showToast(getString(R.string.no_internet))
            }
        }
        alertDialog.show()
    }

    private fun addFavouriteDialog(){
        alertDialog =  MaterialAlertDialogBuilder(this).apply {
            val view = layoutInflater.inflate(R.layout.dialog_layout,null)
            val editText = view.findViewById<EditText>(R.id.search_edittxt)
            setTitle(getString(R.string.add_fav_dialog_title))
            setPositiveButton(getString(R.string.dialog_button_add)) { _, _ ->
                val s = editText.text.toString()
                if (mMarker?.isVisible != true) {
                    showToast("Location not select")
                } else {
                    viewModel.storeFavorite(s, lat, lon) { ok -> showToast(getString(if (ok) R.string.record_saved else R.string.cant_save)) }
                }
            }
            setView(view)
            show()
        }
    }

    private fun openFavouriteListDialog() {
        getAllUpdatedFavList()
        alertDialog = MaterialAlertDialogBuilder(this)
        alertDialog.setTitle(getString(R.string.favourites))
        val view = layoutInflater.inflate(R.layout.fav,null)
        val  rcv = view.findViewById<RecyclerView>(R.id.favorites_list)
        rcv.layoutManager = LinearLayoutManager(this)
        rcv.adapter = favListAdapter
        favListAdapter.onItemClick = {
            lat = it.lat!!
            lon = it.lng!!
            moveMapToNewLocation()
            if (dialog.isShowing) dialog.dismiss()
        }
        favListAdapter.onItemDelete = {
            viewModel.deleteFavourite(it)
        }
        alertDialog.setView(view)
        dialog = alertDialog.create()
        dialog.show()
    }

    private fun getAllUpdatedFavList(){
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED){
                viewModel.allFavList.collect {
                    favListAdapter.submitList(it)
                }
            }
        }
    }

    private fun buildProgressDialog(message: String): AlertDialog {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(android.widget.ProgressBar(context))
            addView(android.widget.TextView(context).apply {
                text = message
                setPadding(pad, 0, 0, 0)
            })
        }
        return MaterialAlertDialogBuilder(this).setView(row).setCancelable(false).create()
    }

    private fun getSearchAddress(address: String): Flow<SearchProgress> = flow {
        emit(SearchProgress.Progress)
        emit(resolveAddress(address.trim()))
    }.flowOn(Dispatchers.IO)

    private suspend fun resolveAddress(input: String): SearchProgress {
        val notFound = SearchProgress.Fail(getString(R.string.address_not_found))
        val matcher: Matcher =
            Pattern.compile("[-+]?\\d{1,3}([.]\\d+)?, *[-+]?\\d{1,3}([.]\\d+)?").matcher(input)

        if (matcher.matches()) {
            val parts = input.split(",")
            val la = parts[0].trim().toDoubleOrNull()
            val lo = parts[1].trim().toDoubleOrNull()
            return if (la != null && lo != null && la in -90.0..90.0 && lo in -180.0..180.0) {
                SearchProgress.Complete(la, lo)
            } else notFound
        }

        return try {
            if (!Geocoder.isPresent()) {
                notFound
            } else {
                val list: List<Address>? = Geocoder(this, Locale.getDefault())
                    .fromName(input, 5)
                val first = list?.firstOrNull()
                if (first != null) SearchProgress.Complete(first.latitude, first.longitude) else notFound
            }
        } catch (_: IOException) {
            SearchProgress.Fail(getString(R.string.no_internet))
        } catch (_: Exception) {
            notFound
        }
    }

    private fun showStartNotification(address: String){
        notificationsChannel.showNotification(this){
            it.setSmallIcon(R.drawable.ic_stop)
            it.setContentTitle(getString(R.string.location_set))
            it.setContentText(address)
            it.setAutoCancel(true)
            it.setCategory(Notification.CATEGORY_EVENT)
            it.priority = NotificationCompat.PRIORITY_HIGH
        }
    }

    private fun cancelNotification() {
        notificationsChannel.cancelAllNotifications(this)
    }
}

private fun AlertDialog.dismissSafe() {
    try { if (isShowing) dismiss() } catch (_: Exception) {}
}

sealed class SearchProgress {
    object Progress : SearchProgress()
    data class Complete(val lat: Double , val lon : Double) : SearchProgress()
    data class Fail(val error: String?) : SearchProgress()
}
