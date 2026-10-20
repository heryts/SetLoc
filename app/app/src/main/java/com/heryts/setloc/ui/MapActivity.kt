package com.heryts.setloc.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatAutoCompleteTextView
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
    private var favListAdapter: FavListAdapter = FavListAdapter()
    private var mMarker: Marker? = null
    private var mLatLng: LatLng? = null
    private var pinListener: ((LatLng) -> Unit)? = null

    private var floatingDialog: AlertDialog? = null

    private val routeWaypoints = mutableListOf<LatLng>()
    private val routeMarkers = mutableListOf<Marker>()
    private var routeLine: Polyline? = null
    private var routePoints: List<LatLng> = emptyList()
    private var routeDurationS: Double? = null
    private var routeLoading = false
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
        setSupportActionBar(binding.toolbar)
        initializeMap()
        setFloatActionButton()
        setRouteButton()
        observeRoute()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.allFavList.collect { favListAdapter.submitList(it) }
            }
        }
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
            setPadding(0, 0, 0, (64 * resources.displayMetrics.density).toInt())
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
            pinListener?.invoke(latLng)
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

    private fun cancelRouteJobs() {
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
        clearRouteLine()
        updateRouteFab()
    }

    private fun removeLastWaypoint() {
        if (routeWaypoints.isEmpty() || RouteRunner.isRunning) return
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
        try {
            val bounds = LatLngBounds.Builder()
            result.points.forEach { bounds.include(it) }
            mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 140))
        } catch (_: Exception) {
        }
        updateRouteFab()
        if (elev != null && PrefManager.altitudeFromServer) RouteRunner.elevations = elev
        else fetchElevations(result.points)
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
        pinListener?.invoke(latLng)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu,menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.search -> { searchDialog(); true }
        R.id.add_fav -> { addFavouriteDialog(); true }
        R.id.get_favourite -> { openFavouriteListDialog(); true }
        R.id.settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
        R.id.about -> { showAboutDialog(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun AlertDialog.makeFloating(): AlertDialog {
        floatingDialog?.let { if (it !== this) it.dismissSafe() }
        floatingDialog = this
        setCanceledOnTouchOutside(false)
        setOnDismissListener {
            if (floatingDialog === this) { floatingDialog = null; pinListener = null }
        }
        window?.apply {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            WindowCompat.setDecorFitsSystemWindows(this, false)
            var imeUp = false
            val place = {
                setGravity(if (imeUp) Gravity.TOP or Gravity.CENTER_HORIZONTAL else Gravity.CENTER)
                val lp = attributes
                lp.y = if (imeUp) binding.toolbar.height + (8 * resources.displayMetrics.density).toInt() else 0
                attributes = lp
            }
            place()
            var forward = false
            decorView.setOnTouchListener { v, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                    val panel = v.findViewById<View>(androidx.appcompat.R.id.parentPanel)
                    forward = if (panel == null) true else {
                        val loc = IntArray(2)
                        panel.getLocationOnScreen(loc)
                        ev.rawX < loc[0] || ev.rawX > loc[0] + panel.width ||
                            ev.rawY < loc[1] || ev.rawY > loc[1] + panel.height
                    }
                }
                if (forward) {
                    val target = this@MapActivity.window.decorView
                    val a = IntArray(2)
                    val d = IntArray(2)
                    target.getLocationOnScreen(a)
                    v.getLocationOnScreen(d)
                    val copy = MotionEvent.obtain(ev)
                    copy.offsetLocation((d[0] - a[0]).toFloat(), (d[1] - a[1]).toFloat())
                    target.dispatchTouchEvent(copy)
                    copy.recycle()
                }
                forward
            }
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

    private fun AlertDialog.backTo(action: (() -> Unit)?): AlertDialog {
        if (action != null) setOnCancelListener { binding.root.post { if (!isFinishing) action() } }
        return this
    }

    private fun pinText(p: LatLng) = getString(
        R.string.pin_label, String.format(Locale.US, "%.6f, %.6f", p.latitude, p.longitude)
    )

    private fun bindPinInfo(tv: TextView?) {
        if (tv == null) return
        val update = { p: LatLng? ->
            tv.text = if (p != null && mMarker?.isVisible == true) pinText(p) else getString(R.string.pin_none)
        }
        update(mLatLng)
        pinListener = { p -> update(p) }
    }

    private fun hideKeyboard(v: View) {
        v.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(v.windowToken, 0)
    }

    override fun onDestroy() {
        floatingDialog?.dismissSafe()
        floatingDialog = null
        super.onDestroy()
    }

    private fun searchDialog(prefill: String = "") {
        val alertDialog = MaterialAlertDialogBuilder(this)
        val view = layoutInflater.inflate(R.layout.dialog_layout,null)
        val editText = view.findViewById<AppCompatAutoCompleteTextView>(R.id.search_edittxt)
        editText.hint = getString(R.string.search_hint)
        if (prefill.isNotEmpty()) { editText.setText(prefill); editText.setSelection(prefill.length) }
        var shown: AlertDialog? = null
        editText.attachPlaceSuggestions(lifecycleScope) { place ->
            moveMapToNewLocation(LatLng(place.lat, place.lon))
            hideKeyboard(editText)
        }
        editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                shown?.getButton(AlertDialog.BUTTON_POSITIVE)?.performClick()
                true
            } else false
        }
        val progressBar = buildProgressDialog(getString(R.string.searching))
        alertDialog.setTitle(getString(R.string.search_dialog_title))
        alertDialog.setView(view)
        alertDialog.setPositiveButton(getString(R.string.search_button), null)
        alertDialog.setNegativeButton(getString(R.string.add_favourite), null)
        fun doSearch() {
            hideKeyboard(editText)
            if (isNetworkConnected()) {
                val getInput = editText.text.toString().trim()
                if (getInput.isNotEmpty()) {
                    lifecycleScope.launch {
                        searchAddress(this@MapActivity, getInput)
                            .catch { emit(SearchProgress.Fail(getString(R.string.address_not_found))) }
                            .collect { result ->
                                when (result) {
                                    is SearchProgress.Progress -> {
                                        if (!isFinishing) progressBar.show()
                                    }
                                    is SearchProgress.Complete -> {
                                        progressBar.dismissSafe()
                                        moveMapToNewLocation(LatLng(result.lat, result.lon))
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
        val created = alertDialog.create()
        shown = created
        created.setOnShowListener {
            created.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { doSearch() }
            created.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                val pos = mLatLng
                if (mMarker?.isVisible != true || pos == null) {
                    showToast(getString(R.string.location_not_selected))
                } else {
                    hideKeyboard(editText)
                    val label = editText.text.toString().trim()
                    showFavouriteNameDialog(
                        PlaceCandidate(label, pos.latitude, pos.longitude),
                        moveMap = false,
                        onBack = { searchDialog(label) }
                    )
                }
            }
        }
        created.makeFloating()
        bindPinInfo(view.findViewById(R.id.pin_info))
        created.show()
    }

    private fun addFavouriteDialog(prefill: String = "") {
        val view = layoutInflater.inflate(R.layout.dialog_add_fav, null)
        val editText = view.findViewById<AppCompatAutoCompleteTextView>(R.id.search_edittxt)
        editText.hint = getString(R.string.fav_name_search_hint)
        if (prefill.isNotEmpty()) { editText.setText(prefill); editText.setSelection(prefill.length) }
        var addDialog: AlertDialog? = null
        editText.attachPlaceSuggestions(lifecycleScope) { place ->
            addDialog?.dismiss()
            val typed = editText.text.toString()
            showFavouriteNameDialog(place, onBack = { addFavouriteDialog(typed) })
        }

        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.add_fav_dialog_title))
            .setView(view)
            .setNegativeButton(getString(R.string.search_button), null)
            .setPositiveButton(getString(R.string.dialog_button_add), null)
            .create()

        addDialog = dlg
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pos = mLatLng
                if (mMarker?.isVisible != true || pos == null) {
                    showToast(getString(R.string.location_not_selected))
                    return@setOnClickListener
                }
                val label = editText.text.toString().trim()
                    .ifEmpty { "%.5f, %.5f".format(Locale.US, pos.latitude, pos.longitude) }
                saveFavourite(label, pos.latitude, pos.longitude)
                dlg.dismiss()
            }
            dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                val query = editText.text.toString().trim()
                if (query.isEmpty()) {
                    showToast(getString(R.string.fav_search_empty))
                } else {
                    searchFavouritePlaces(query, { dlg.dismiss() }, { addFavouriteDialog(query) })
                }
            }
            editText.requestFocus()
        }
        editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                true
            } else false
        }
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dlg.makeFloating()
        bindPinInfo(view.findViewById(R.id.pin_info))
        dlg.show()
    }

    private fun saveFavourite(label: String, lat: Double, lon: Double) {
        viewModel.storeFavorite(label, lat, lon) { ok ->
            showToast(getString(if (ok) R.string.record_saved else R.string.cant_save))
        }
    }

    private fun searchFavouritePlaces(query: String, onFound: () -> Unit, onBack: () -> Unit) {
        val progress = buildProgressDialog(getString(R.string.searching))
        lifecycleScope.launch {
            if (!isFinishing) progress.show()
            val result = try {
                searchPlaces(this@MapActivity, query)
            } catch (e: CancellationException) {
                progress.dismissSafe()
                throw e
            } catch (_: java.io.IOException) {
                progress.dismissSafe()
                showToast(getString(R.string.no_internet))
                return@launch
            } catch (_: Exception) {
                emptyList()
            }
            progress.dismissSafe()
            if (result.isEmpty()) {
                showToast(getString(R.string.address_not_found))
            } else if (!isFinishing) {
                onFound()
                showFavouritePlacePicker(result, onBack)
            }
        }
    }

    private fun showFavouritePlacePicker(places: List<PlaceCandidate>, onBack: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.fav_pick_location))
            .setItems(places.map { it.label }.toTypedArray()) { _, i ->
                showFavouriteNameDialog(places[i], onBack = { showFavouritePlacePicker(places, onBack) })
            }
            .setNegativeButton(getString(R.string.dialog_button_cancel)) { d, _ -> d.cancel() }
            .create().backTo(onBack).makeFloating().show()
    }

    private fun showFavouriteNameDialog(place: PlaceCandidate, moveMap: Boolean = true, onBack: (() -> Unit)? = null) {
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

    private fun openFavouriteListDialog() {
        val alertDialog = MaterialAlertDialogBuilder(this)
        alertDialog.setTitle(getString(R.string.favourites))
        val view = layoutInflater.inflate(R.layout.fav,null)
        val  rcv = view.findViewById<RecyclerView>(R.id.favorites_list)
        rcv.layoutManager = LinearLayoutManager(this)
        rcv.adapter = favListAdapter
        favListAdapter.onItemClick = {
            val la = it.lat
            val lo = it.lng
            if (la != null && lo != null) moveMapToNewLocation(LatLng(la, lo))
        }
        favListAdapter.onItemDelete = {
            viewModel.deleteFavourite(it)
        }
        alertDialog.setView(view)
        alertDialog.setNegativeButton(getString(R.string.dialog_button_close), null)
        alertDialog.create().makeFloating().show()
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
