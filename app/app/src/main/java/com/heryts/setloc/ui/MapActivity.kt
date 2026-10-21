package com.heryts.setloc.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.graphics.Typeface
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.heryts.setloc.R
import com.heryts.setloc.databinding.ActivityMapBinding
import com.heryts.setloc.favourite.SavedRoute
import com.heryts.setloc.favourite.SavedRouteStore
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import java.util.Locale

class MapActivity : AppCompatActivity(), OnMapReadyCallback, GoogleMap.OnMapClickListener,
    GoogleMap.OnMapLongClickListener {
    private val binding by lazy { ActivityMapBinding.inflate(layoutInflater) }
    private lateinit var mMap: GoogleMap
    private val viewModel by viewModels<MainViewModel>()
    private val notificationsChannel by lazy { NotificationsChannel() }
    private var mMarker: Marker? = null
    private var mLatLng: LatLng? = null

    private var floatingDialog: AlertDialog? = null
    private var dialogMoved = false
    private var insetTop = 0
    private var insetBottom = 0
    private var insetIme = 0
    private var pinLabel: String? = null
    private var pinPicked = false
    private var routeFromGps = false
    private var favDialog: AlertDialog? = null
    private lateinit var searchPanel: SearchPanel
    private var adjustPending = false
    private var pinAdjustWanted = false

    private val routeWaypoints = mutableListOf<LatLng>()
    private val routeMarkers = mutableListOf<Marker>()
    private var routeLine: Polyline? = null
    private var routePoints: List<LatLng> = emptyList()
    private var routeDurationS: Double? = null
    private var routeLoading = false
    private var openOptionsWhenReady = false
    private var dockedTop = 0
    private var routesDialogUp = false
    private var confirmDialog: AlertDialog? = null
    private var currentRouteKey: String? = null
    private var routeJob: Job? = null
    private class CachedRoute(val result: RouteClient.Result, val at: Long, var elev: FloatArray? = null)
    private val routeCache = object : LinkedHashMap<String, CachedRoute>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedRoute>?): Boolean = size > 8
    }
    private var elevationJob: Job? = null
    private var clearHintShown = false
    private var wasRouteRunning = false
    private val routeReady get() = routePoints.size >= 2 && !routeLoading
    private val singleStop get() = routeWaypoints.size == 1 && routePoints.size < 2 && !routeLoading

    private companion object {
        const val ROUTE_DEBOUNCE_MS = 700L
        const val ROUTE_CACHE_TRAFFIC_MS = 5 * 60_000L
        const val ROUTE_CACHE_MS = 30 * 60_000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!RouteRunner.isRunning && !PrefManager.routeLive()) {
            if (PrefManager.routeActive) PrefManager.routeActive = false
            if (PrefManager.routePlay) PrefManager.routePlay = false
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(binding.root)
        setupSystemBars()
        setupSearchBar()
        initializeMap()
        setFloatActionButton()
        setRouteButton()
        observeRoute()
    }

    @SuppressLint("MissingPermission")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 99 && ::mMap.isInitialized && checkSinglePermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            mMap.isMyLocationEnabled = true
        }
    }

    private fun setRunningUi(running: Boolean) {
        binding.start.visibility = if (running) View.GONE else View.VISIBLE
        binding.stop.visibility = if (running) View.VISIBLE else View.GONE
        updateRouteFab()
    }

    private fun initializeMap() {
        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment?
        mapFragment?.getMapAsync(this)
    }

    private fun setFloatActionButton() {
        if (viewModel.isStarted) {
            setRunningUi(true)
        }

        binding.start.setOnClickListener {
            if (mLatLng == null || !::mMap.isInitialized) {
                showToast(getString(R.string.map_not_ready))
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
            val here = mLatLng!!
            viewModel.update(true, here.latitude, here.longitude)
            PrefManager.altitudeM = PrefManager.altitudeManual
            if (PrefManager.altitudeFromServer) {
                lifecycleScope.launch {
                    ElevationClient.fetch(this@MapActivity, listOf(here))?.firstOrNull()
                        ?.let { PrefManager.altitudeM = it }
                }
            }
            mMarker?.position = here
            mMarker?.isVisible = true
            setRunningUi(true)
            lifecycleScope.launch { showStartNotification(here.address(this@MapActivity)) }
            showToast(getString(R.string.location_set))
        }
        binding.myLocation.setOnClickListener {
            val loc = realLocation()
            if (loc == null || !::mMap.isInitialized) {
                showToast(getString(R.string.gps_unavailable))
                return@setOnClickListener
            }
            mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(loc, maxOf(mMap.cameraPosition.zoom, 15f)))
        }
        binding.stop.setOnClickListener {
            RouteRunner.stop()
            (mLatLng ?: LatLng(viewModel.getLat, viewModel.getLng))
                .let { viewModel.update(false, it.latitude, it.longitude) }
            mMarker?.isVisible = false
            setRunningUi(false)
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
            mLatLng = LatLng(viewModel.getLat, viewModel.getLng)
            mMarker = addMarker(
                MarkerOptions().position(mLatLng!!).draggable(false)
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)).visible(false)
            )
            mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(mLatLng!!, zoom))
            uiSettings.isZoomControlsEnabled = true
            uiSettings.isMyLocationButtonEnabled = false
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
            applyMapPadding()
            setOnCameraIdleListener {
                if (pinAdjustWanted) {
                    pinAdjustWanted = false
                    requestDialogAdjust()
                }
            }
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
        if (searchPanel.isShowing) {
            searchPanel.close()
            return
        }
        if (!RouteRunner.isRunning && binding.start.visibility == View.VISIBLE && (routeReady || routeLoading)) {
            onMapLongClick(latLng)
            return
        }
        pinLabel = null
        updateRouteFab()
        if (RouteRunner.isRunning) return
        if (routeWaypoints.isNotEmpty()) clearRoute()
        mLatLng = latLng
        mMarker?.let {
            it.position = latLng
            it.isVisible = true
            mMap.animateCamera(CameraUpdateFactory.newLatLng(latLng))
            onPinChanged()
        }
        pinPicked = true
        updateRouteFab()
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
        val pin = mLatLng
        if (routeWaypoints.isEmpty() && mMarker?.isVisible == true && pin != null &&
            RouteClient.MAX_POINTS >= 2 && metersBetween(pin, latLng) >= 5f
        ) {
            addRouteStop(pin, quiet = true)
        }
        addRouteStop(latLng, quiet = false)
        if (routeWaypoints.size == 1) {
            mLatLng = latLng
            pinLabel = null
            pinPicked = false
        }
    }

    private fun addRouteStop(latLng: LatLng, quiet: Boolean) {
        val marker = mMap.addMarker(MarkerOptions().position(latLng)) ?: return
        routeWaypoints.add(latLng)
        routeMarkers.add(marker)
        refreshRouteMarkers()
        if (routeWaypoints.size == 1) {
            if (!quiet) showToast(getString(R.string.route_pick_start))
            updateRouteFab()
        } else {
            fetchRoute(dropLastOnFail = true)
        }
    }

    private fun metersBetween(a: LatLng, b: LatLng): Float {
        val out = FloatArray(1)
        android.location.Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, out)
        return out[0]
    }

    private fun onRouteButton() {
        if (RouteRunner.isRunning) {
            showToast(getString(R.string.route_stop_first))
            return
        }
        if (routeReady) {
            showRouteOptions()
            return
        }
        if (routeLoading) {
            openOptionsWhenReady = true
            return
        }
        addRouteFromPin()
    }

    private fun selectedPoint(): LatLng? = when {
        singleStop -> routeWaypoints.first()
        mMarker?.isVisible == true -> mLatLng
        else -> null
    }

    private fun addRouteFromPin() {
        val pos = selectedPoint()
        if (!::mMap.isInitialized || pos == null) {
            showToast(getString(R.string.location_not_selected))
            return
        }
        val fresh = routeWaypoints.size < 2
        val needed = if (fresh) 2 else 1
        if ((if (fresh) 0 else routeWaypoints.size) + needed > RouteClient.MAX_POINTS) {
            showToast(getString(R.string.route_max_points, RouteClient.MAX_POINTS))
            return
        }
        val from = if (fresh) realLocation() else routeWaypoints.last()
        if (from == null) {
            showToast(getString(R.string.gps_unavailable))
            return
        }
        if (metersBetween(from, pos) < 5f) {
            showToast(getString(R.string.route_point_exists))
            return
        }
        if (fresh) {
            if (routeWaypoints.isNotEmpty()) clearRoute()
            addRouteStop(from, quiet = true)
            routeFromGps = true
        }
        openOptionsWhenReady = true
        addRouteStop(pos, quiet = false)
    }

    @SuppressLint("MissingPermission")
    private fun realLocation(): LatLng? {
        if (!checkSinglePermission(Manifest.permission.ACCESS_FINE_LOCATION)) return null
        val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
        val best = listOf(
            android.location.LocationManager.GPS_PROVIDER,
            android.location.LocationManager.NETWORK_PROVIDER,
            android.location.LocationManager.PASSIVE_PROVIDER,
            "fused"
        ).mapNotNull { try { lm.getLastKnownLocation(it) } catch (_: Exception) { null } }
            .maxByOrNull { it.time }
        return best?.let { LatLng(it.latitude, it.longitude) }
    }

    private fun addFavouriteFromPin() {
        val pos = selectedPoint()
        if (pos == null) {
            showToast(getString(R.string.location_not_selected))
            return
        }
        val label = pinLabel ?: "%.5f, %.5f".format(Locale.US, pos.latitude, pos.longitude)
        showFavouriteNameDialog(PlaceCandidate(label, pos.latitude, pos.longitude), moveMap = false)
    }

    private fun confirmDeleteFavourite(f: com.heryts.setloc.favourite.Favourite) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_favourite_item)
            .setMessage(f.address)
            .setPositiveButton(android.R.string.ok) { _, _ -> viewModel.deleteFavourite(f) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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

    private fun cancelRouteJobs() {
        openOptionsWhenReady = false
        routeJob?.cancel()
        routeJob = null
        elevationJob?.cancel()
        elevationJob = null
        routeLoading = false
    }

    private fun clearRouteLine() {
        routeLine?.remove()
        routeLine = null
        routePoints = emptyList()
        currentRouteKey = null
        RouteRunner.elevations = null
    }

    private fun dropLastWaypoint() {
        routeWaypoints.removeAt(routeWaypoints.size - 1)
        routeMarkers.removeAt(routeMarkers.size - 1).remove()
    }

    private fun clearRoute() {
        cancelRouteJobs()
        routeMarkers.forEach { it.remove() }
        routeMarkers.clear()
        routeWaypoints.clear()
        routeFromGps = false
        clearRouteLine()
        updateRouteFab()
    }

    private fun hidePin() {
        mMarker?.isVisible = false
        pinLabel = null
        pinPicked = false
        updateRouteFab()
    }

    private fun onDeleteClick() {
        if (RouteRunner.isRunning) return
        if (routeWaypoints.isNotEmpty()) removeLastWaypoint() else hidePin()
    }

    private fun onDeleteLongClick() {
        if (RouteRunner.isRunning) return
        clearRoute()
        hidePin()
    }

    private fun removeLastWaypoint() {
        if (routeWaypoints.isEmpty() || RouteRunner.isRunning) return
        if (routeFromGps && routeWaypoints.size <= 2) {
            clearRoute()
            return
        }
        cancelRouteJobs()
        dropLastWaypoint()
        clearRouteLine()
        refreshRouteMarkers()
        if (routeWaypoints.isNotEmpty() && !clearHintShown) {
            clearHintShown = true
            showToast(getString(R.string.route_clear_hint))
        }
        if (routeWaypoints.size >= 2) fetchRoute(dropLastOnFail = false) else updateRouteFab()
    }

    private fun routeKey(stops: List<LatLng>): String {
        val sb = StringBuilder()
        stops.forEach { sb.append("%.6f,%.6f;".format(Locale.US, it.latitude, it.longitude)) }
        return sb.append(PrefManager.routeMode).append(PrefManager.trafficAware).toString()
    }

    private fun applyRoute(result: RouteClient.Result, key: String, elev: FloatArray?) {
        currentRouteKey = key
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
        fitRoute(result.points)
        updateRouteFab()
        if (elev != null && PrefManager.altitudeFromServer) RouteRunner.elevations = elev
        else fetchElevations(result.points)
        if (openOptionsWhenReady) {
            openOptionsWhenReady = false
            showRouteOptions()
        }
    }

    private fun fetchRoute(dropLastOnFail: Boolean) {
        if (routeWaypoints.size < 2) return
        val stops = routeWaypoints.toList()
        routeLoading = true
        elevationJob?.cancel()
        RouteRunner.elevations = null
        updateRouteFab()
        val key = routeKey(stops)
        val ttl = if (PrefManager.trafficAware && PrefManager.routeMode >= 2) ROUTE_CACHE_TRAFFIC_MS else ROUTE_CACHE_MS
        val cached = routeCache[key]?.takeIf { android.os.SystemClock.elapsedRealtime() - it.at < ttl }
        if (cached != null) {
            routeJob?.cancel()
            applyRoute(cached.result, key, cached.elev)
            return
        }
        if (!isNetworkConnected()) {
            showToast(getString(R.string.no_internet))
            routeFetchFailed(dropLastOnFail)
            return
        }
        routeJob?.cancel()
        routeJob = lifecycleScope.launch {
            try {
                delay(ROUTE_DEBOUNCE_MS)
                showToast(getString(R.string.route_searching))
                val result = RouteClient.fetch(this@MapActivity, stops, PrefManager.routeMode)
                routeCache[key] = CachedRoute(result, android.os.SystemClock.elapsedRealtime())
                applyRoute(result, key, null)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                showToast((e as? RouteClient.RouteException)?.text(this@MapActivity) ?: e.message ?: getString(R.string.address_not_found))
                routeFetchFailed(dropLastOnFail)
            }
        }
    }

    private fun routeFetchFailed(dropLast: Boolean) {
        routeLoading = false
        openOptionsWhenReady = false
        if (dropLast && routeWaypoints.size > 1) {
            dropLastWaypoint()
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
            ElevationClient.fetch(this@MapActivity, samples)?.let { e ->
                RouteRunner.elevations = e
                currentRouteKey?.let { k -> routeCache[k]?.elev = e }
            }
        }
    }

    private fun setRouteButton() {
        binding.route.setOnClickListener { onRouteButton() }
        binding.routeDelete.setOnClickListener { onDeleteClick() }
        binding.routeDelete.setOnLongClickListener { onDeleteLongClick(); true }
        binding.addFav.setOnLongClickListener { showSavedRoutes(); true }
        binding.addFav.setOnClickListener { if (routeReady) showSaveRouteDialog() else addFavouriteFromPin() }
    }

    private fun updateRouteFab() {
        val idle = !RouteRunner.isRunning && binding.start.visibility == View.VISIBLE
        val picked = ((pinLabel != null || pinPicked) && mMarker?.isVisible == true || singleStop) && idle
        val hasRoute = (routeReady || routeLoading) && idle
        binding.route.visibility = if (picked || hasRoute) View.VISIBLE else View.GONE
        binding.routeDelete.visibility = if ((routeWaypoints.isNotEmpty() && idle) || picked) View.VISIBLE else View.GONE
        val saveMode = routeReady && idle
        binding.addFav.visibility = if (picked || saveMode) View.VISIBLE else View.GONE
        binding.addFav.contentDescription = getString(if (saveMode) R.string.save_route else R.string.add_favourite)
    }

    private fun systemModeActive(): Boolean = try {
        val v = PrefManager.dec(Settings.Global.getString(contentResolver, PrefManager.tag("sys")))
        val enabled = v == "1" || v == "2"
        val boot = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)
        val live = PrefManager.dec(Settings.Global.getString(contentResolver, PrefManager.tag("live")))?.toIntOrNull()
        enabled && boot >= 0 && live == boot
    } catch (_: Exception) {
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
        mMarker?.apply {
            position = first
            isVisible = true
        }
        if (!checkSinglePermission(Manifest.permission.POST_NOTIFICATIONS)) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
        val speed = PrefManager.routeSpeed.takeIf { it > 0f }
            ?: AutoSpeed.kmh(PrefManager.routeMode, RouteRunner.routeLength(routePoints), routeDurationS)
        RouteRunner.start(
            routePoints, speed,
            PrefManager.routeWaitStart, PrefManager.routeWaitEnd, PrefManager.routeSpeedVarFor(speed)
        )
        ContextCompat.startForegroundService(this, Intent(this, RouteService::class.java))
        setRunningUi(true)
        updateRouteFab()
        mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(first, 16f))
    }

    private fun insideCenter(pos: LatLng): Boolean = try {
        val b = mMap.projection.visibleRegion.latLngBounds
        val c = b.center
        val hLat = (b.northeast.latitude - b.southwest.latitude) / 2 * 0.5
        var span = b.northeast.longitude - b.southwest.longitude
        if (span < 0) span += 360.0
        val hLng = span / 2 * 0.5
        var dLng = Math.abs(pos.longitude - c.longitude)
        if (dLng > 180) dLng = 360 - dLng
        Math.abs(pos.latitude - c.latitude) <= hLat && dLng <= hLng
    } catch (_: Exception) {
        false
    }

    private fun observeRoute() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RouteRunner.state.collect { s ->
                    if (s.running) wasRouteRunning = true
                    val pos = s.position
                    if (pos != null && (s.running || (s.finished && !s.spoofOff))) {
                        mLatLng = pos
                        if (::mMap.isInitialized) {
                            mMarker?.apply {
                                position = pos
                                isVisible = true
                            }
                            if (!insideCenter(pos)) mMap.animateCamera(CameraUpdateFactory.newLatLng(pos))
                        }
                        setRunningUi(true)
                    }
                    if (s.finished && wasRouteRunning) {
                        wasRouteRunning = false
                        showToast(getString(R.string.route_arrived))
                        if (s.spoofOff) {
                            mMarker?.isVisible = false
                            setRunningUi(false)
                        }
                    } else if (!s.running && !s.finished && wasRouteRunning) {
                        wasRouteRunning = false
                        mMarker?.isVisible = false
                        setRunningUi(false)
                    }
                    updateRouteFab()
                }
            }
        }
    }

    private fun showRouteOptions() = openRouteOptions(
        RouteOptionsHost(
            points = { routePoints },
            durationS = { routeDurationS },
            waypointCount = { routeWaypoints.size },
            refetchRoute = { fetchRoute(dropLastOnFail = false) },
            refetchElevations = { fetchElevations(routePoints) },
            clearRoute = { clearRoute() }
        )
    )

    private fun moveMapToNewLocation(latLng: LatLng) {
        if (!::mMap.isInitialized) return
        mLatLng = latLng
        mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 12.0f))
        mMarker?.apply {
            position = latLng
            isVisible = true
            showInfoWindow()
        }
        onPinChanged()
    }

    private fun setupSystemBars() {
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val d = resources.displayMetrics.density
            insetTop = sb.top
            insetBottom = sb.bottom
            insetIme = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            if (::searchPanel.isInitialized) searchPanel.setIme(insetIme)
            (binding.searchBar.layoutParams as ViewGroup.MarginLayoutParams).topMargin = sb.top + (12 * d).toInt()
            binding.searchBar.requestLayout()
            listOf(
                binding.route to 104, binding.addFav to 156,
                binding.routeDelete to 208, binding.start to 32, binding.stop to 32
            )
                .forEach { (v, base) ->
                    (v.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = (base * d).toInt() + sb.bottom
                    v.requestLayout()
                }
            applyMapPadding()
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun applyMapPadding() {
        if (!::mMap.isInitialized) return
        val d = resources.displayMetrics.density
        val top = maxOf(insetTop + (76 * d).toInt(), dockedTop)
        mMap.setPadding(0, top, 0, insetBottom + (64 * d).toInt())
    }

    private fun setupSearchBar() {
        searchPanel = SearchPanel(
            this, binding.searchBar,
            onPick = {
                pinLabel = if (it.label.firstOrNull()?.isLetter() == true) it.label.substringBefore(", ") else it.label
                moveMapToNewLocation(LatLng(it.lat, it.lon))
                pinPicked = true
                updateRouteFab()
            },
            onFavouriteLongClick = { confirmDeleteFavourite(it) }
        )
        searchPanel.setIme(insetIme)
        val backCb = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() { searchPanel.close() }
        }
        onBackPressedDispatcher.addCallback(this, backCb)
        searchPanel.onVisibilityChanged = { shown ->
            backCb.isEnabled = shown
            if (shown) {
                confirmDialog?.dismissSafe()
                floatingDialog?.dismissSafe()
            }
        }
        binding.menuBtn.setOnClickListener {
            confirmDialog?.dismissSafe()
            floatingDialog?.dismissSafe()
            searchPanel.close()
            PopupMenu(this, binding.menuBtn, Gravity.END).apply {
                menuInflater.inflate(R.menu.main_menu, menu)
                setOnMenuItemClickListener { handleMenuItem(it.itemId) }
                show()
            }
        }
    }

    private fun handleMenuItem(id: Int): Boolean = when (id) {
        R.id.saved_routes -> { showSavedRoutes(); true }
        R.id.settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
        R.id.about -> { showAboutDialog(); true }
        else -> false
    }

    private fun AlertDialog.makeFloating(): AlertDialog {
        floatingDialog?.let { if (it !== this) it.dismissSafe() }
        floatingDialog = this
        dialogMoved = false
        setCanceledOnTouchOutside(false)
        setOnDismissListener {
            if (floatingDialog === this) floatingDialog = null
        }
        window?.apply {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            WindowCompat.setDecorFitsSystemWindows(this, false)
            var imeUp = false
            val place = {
                setGravity(if (imeUp) Gravity.TOP or Gravity.CENTER_HORIZONTAL else Gravity.CENTER)
                val lp = attributes
                lp.x = 0
                lp.y = if (imeUp) binding.searchBar.bottom + (8 * resources.displayMetrics.density).toInt() else 0
                attributes = lp
                dialogMoved = false
            }
            place()
            attachTouchRouting(this@MapActivity.window, true) { dialogMoved = true }
            decorView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> requestDialogAdjust() }
            ViewCompat.setOnApplyWindowInsetsListener(decorView) { _, insets ->
                val up = insets.isVisible(WindowInsetsCompat.Type.ime())
                if (up != imeUp) {
                    imeUp = up
                    place()
                }
                insets
            }
        }
        return this
    }

    private fun requestDialogAdjust() {
        if (adjustPending) return
        adjustPending = true
        binding.root.postDelayed({
            adjustPending = false
            adjustDialogForPin()
        }, 120)
    }

    private fun onPinChanged() {
        dialogMoved = false
        pinAdjustWanted = true
        requestDialogAdjust()
        binding.root.postDelayed({ pinAdjustWanted = false }, 1500)
    }

    private fun adjustDialogForPin() {
        val dlg = floatingDialog ?: return
        if (dialogMoved || !dlg.isShowing || !::mMap.isInitialized) return
        val marker = mMarker ?: return
        if (!marker.isVisible) return
        val w = dlg.window ?: return
        val panel = w.decorView.findViewById<View>(androidx.appcompat.R.id.parentPanel) ?: return
        if (panel.height == 0) return
        val mapView = supportFragmentManager.findFragmentById(R.id.map)?.view ?: return
        val d = resources.displayMetrics.density
        val pt = mMap.projection.toScreenLocation(marker.position)
        val ml = IntArray(2)
        mapView.getLocationOnScreen(ml)
        val px = ml[0] + pt.x
        val py = ml[1] + pt.y
        val pinTop = py - 64 * d
        val pinBottom = py + 8 * d
        val pl = IntArray(2)
        panel.getLocationOnScreen(pl)
        val overlap = pl[0] < px + 24 * d && pl[0] + panel.width > px - 24 * d &&
            pl[1] < pinBottom && pl[1] + panel.height > pinTop
        if (!overlap) return
        val tl = IntArray(2)
        binding.searchBar.getLocationOnScreen(tl)
        val minTop = tl[1] + binding.searchBar.height + 8 * d
        val above = pinTop - panel.height - 8 * d
        val below = pinBottom + 8 * d
        val screenH = resources.displayMetrics.heightPixels
        val newTop = when {
            above >= minTop -> above
            below + panel.height <= screenH -> below
            else -> minTop
        }
        val lp = w.attributes
        lp.y += (newTop - pl[1]).toInt()
        w.attributes = lp
    }

    private fun AlertDialog.backTo(action: (() -> Unit)?): AlertDialog {
        if (action != null) setOnCancelListener { binding.root.post { if (!isFinishing) action() } }
        return this
    }

    private fun pinText(p: LatLng) = getString(
        R.string.pin_label, String.format(Locale.US, "%.6f, %.6f", p.latitude, p.longitude)
    )

    override fun onDestroy() {
        confirmDialog?.dismissSafe()
        confirmDialog = null
        floatingDialog?.dismissSafe()
        floatingDialog = null
        super.onDestroy()
    }

    private fun saveFavourite(label: String, lat: Double, lon: Double) {
        viewModel.storeFavorite(label, lat, lon) { ok ->
            showToast(getString(if (ok) R.string.record_saved else R.string.cant_save))
        }
    }

    private fun showFavouriteNameDialog(place: PlaceCandidate, moveMap: Boolean = true, onBack: (() -> Unit)? = null) {
        if (favDialog?.isShowing == true) return
        if (moveMap) moveMapToNewLocation(LatLng(place.lat, place.lon))
        val view = layoutInflater.inflate(R.layout.dialog_add_fav, null)
        val editText = view.findViewById<EditText>(R.id.search_edittxt)
        editText.setText(place.label)
        editText.selectAll()

        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.add_fav_dialog_title))
            .setView(view)
            .setNegativeButton(getString(R.string.dialog_button_cancel), null)
            .setPositiveButton(getString(R.string.dialog_button_add), null)
            .create().backTo(onBack)
        favDialog = dlg

        fun submit() {
            val label = editText.text.toString().trim()
                .ifEmpty { "%.5f, %.5f".format(Locale.US, place.lat, place.lon) }
            saveFavourite(label, place.lat, place.lon)
            dlg.dismiss()
        }
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { submit() }
            dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { dlg.cancel() }
            editText.requestFocus()
        }
        editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dlg.makeFloating()
        view.findViewById<TextView>(R.id.pin_info)?.text = pinText(LatLng(place.lat, place.lon))
        dlg.show()
    }

    private fun showSaveRouteDialog() {
        if (favDialog?.isShowing == true || !routeReady) return
        val view = layoutInflater.inflate(R.layout.dialog_add_fav, null)
        val editText = view.findViewById<EditText>(R.id.search_edittxt)
        editText.hint = getString(R.string.route_name_hint)
        editText.setText(
            getString(R.string.route_name_default, formatDistance(RouteRunner.routeLength(routePoints)))
        )
        editText.selectAll()
        view.findViewById<TextView>(R.id.pin_info)?.text =
            getString(R.string.route_points_info, routeWaypoints.size)

        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.save_route))
            .setView(view)
            .setNegativeButton(getString(R.string.dialog_button_cancel), null)
            .setPositiveButton(getString(R.string.dialog_button_add), null)
            .create()
        favDialog = dlg

        fun submit() {
            val name = editText.text.toString().trim().ifEmpty {
                getString(R.string.route_name_default, formatDistance(RouteRunner.routeLength(routePoints)))
            }
            val pts = routeWaypoints.map { it.latitude to it.longitude }
            viewModel.storeRoute(name, PrefManager.routeMode, pts) { ok ->
                showToast(getString(if (ok) R.string.record_saved else R.string.cant_save))
            }
            dlg.dismiss()
        }
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { submit() }
            dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { dlg.cancel() }
            editText.requestFocus()
        }
        editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dlg.makeFloating()
        dlg.show()
    }

    private fun showSavedRoutes() {
        val items = SavedRouteStore.all.value.toMutableList()
        if (items.isEmpty()) {
            showToast(getString(R.string.saved_routes_empty))
            return
        }
        val d = resources.displayMetrics.density
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this)
        scroll.addView(rows)
        val box = FrameLayout(this)
        box.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0))
        var selectedId = -1L
        val dlgRef = arrayOfNulls<AlertDialog>(1)

        fun render() {
            scroll.layoutParams.height = (minOf(items.size, 4) * 52 * d).toInt()
            scroll.requestLayout()
            rows.removeAllViews()
            items.forEach { r ->
                val row = layoutInflater.inflate(R.layout.item_saved_route, rows, false)
                val name = row.findViewById<TextView>(R.id.saved_route_name)
                name.text = r.name
                name.setTypeface(null, if (r.id == selectedId) Typeface.BOLD else Typeface.NORMAL)
                val remove = {
                    confirmDeleteSavedRoute(r) {
                        items.remove(r)
                        if (items.isEmpty()) dlgRef[0]?.dismissSafe() else render()
                    }
                }
                row.setOnClickListener {
                    selectedId = r.id
                    render()
                    dlgRef[0]?.let { moveRoutesDialogUp(it) }
                    loadSavedRoute(r)
                }
                row.setOnLongClickListener { remove(); true }
                row.findViewById<View>(R.id.saved_route_delete).setOnClickListener { remove() }
                rows.addView(row)
            }
        }
        render()

        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.saved_routes)
            .setView(box)
            .setNegativeButton(getString(R.string.saved_routes_close), null)
            .create()
        dlgRef[0] = dlg
        dlg.makeFloating()
        dlg.setOnDismissListener {
            if (floatingDialog === dlg) floatingDialog = null
            routesDialogUp = false
            dockedTop = 0
            applyMapPadding()
        }
        dlg.window?.decorView?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (routesDialogUp) updateDockedTop(dlg)
        }
        dlg.show()
    }

    private fun moveRoutesDialogUp(dlg: AlertDialog) {
        val w = dlg.window ?: return
        val d = resources.displayMetrics.density
        w.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        val lp = w.attributes
        lp.x = 0
        lp.y = binding.searchBar.bottom + (8 * d).toInt()
        w.attributes = lp
        dialogMoved = false
        routesDialogUp = true
        updateDockedTop(dlg)
    }

    private fun updateDockedTop(dlg: AlertDialog) {
        val w = dlg.window ?: return
        val d = resources.displayMetrics.density
        val h = w.decorView.height
        val top = if (dialogMoved || h == 0) 0 else w.attributes.y + h + (8 * d).toInt()
        if (top != dockedTop) {
            dockedTop = top
            applyMapPadding()
            if (top > 0 && routePoints.size >= 2) fitRoute(routePoints)
        }
    }

    private fun fitRoute(points: List<LatLng>) {
        try {
            val bounds = LatLngBounds.Builder()
            points.forEach { bounds.include(it) }
            mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 140))
        } catch (_: Exception) {
        }
    }

    private fun confirmDeleteSavedRoute(r: SavedRoute, onDone: () -> Unit) {
        val anchor = floatingDialog
        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_saved_route)
            .setMessage(r.name)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewModel.deleteSavedRoute(r)
                onDone()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        confirmDialog?.dismissSafe()
        confirmDialog = dlg
        dlg.setOnDismissListener { if (confirmDialog === dlg) confirmDialog = null }
        dlg.setCanceledOnTouchOutside(false)
        val aw = anchor?.takeIf { it.isShowing }?.window
        if (aw == null) {
            dlg.show()
            return
        }
        val d = resources.displayMetrics.density
        val loc = IntArray(2)
        aw.decorView.getLocationOnScreen(loc)
        val root = window.decorView
        val rootLoc = IntArray(2)
        root.getLocationOnScreen(rootLoc)
        val anchorCx = loc[0] + aw.decorView.width / 2
        val anchorCy = loc[1] + aw.decorView.height / 2
        val rootCx = rootLoc[0] + root.width / 2
        val rootCy = rootLoc[1] + root.height / 2
        dlg.window?.apply {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            WindowCompat.setDecorFitsSystemWindows(this, false)
            setElevation(0f)
            setGravity(Gravity.CENTER)
            attachTouchRouting(this@MapActivity.window, false)
            val lp = attributes
            lp.x = anchorCx - rootCx
            lp.y = anchorCy - rootCy
            attributes = lp
        }
        val fill = sampleColor(aw.decorView, aw.decorView.width - (56 * d).toInt(), (32 * d).toInt())
        dlg.show()
        dlg.window?.let { w ->
            w.setLayout(
                aw.decorView.width - (32 * d).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            if (fill != null) {
                w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                w.decorView.findViewById<View>(androidx.appcompat.R.id.parentPanel)?.background =
                    android.graphics.drawable.GradientDrawable().apply {
                        setColor(fill)
                        cornerRadius = 4 * d
                    }
            }
        }
    }

    private fun sampleColor(v: View, x: Int, y: Int): Int? = try {
        val bmp = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.translate(-x.toFloat(), -y.toFloat())
        v.draw(c)
        bmp.getPixel(0, 0).takeIf { android.graphics.Color.alpha(it) == 255 }
    } catch (_: Exception) {
        null
    }

    private fun loadSavedRoute(r: SavedRoute) {
        if (RouteRunner.isRunning) {
            showToast(getString(R.string.route_stop_first))
            return
        }
        if (!::mMap.isInitialized) return
        clearRoute()
        hidePin()
        PrefManager.routeMode = r.mode
        r.points.forEach { (la, ln) ->
            val ll = LatLng(la, ln)
            val marker = mMap.addMarker(MarkerOptions().position(ll)) ?: return@forEach
            routeWaypoints.add(ll)
            routeMarkers.add(marker)
        }
        refreshRouteMarkers()
        fetchRoute(dropLastOnFail = false)
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
