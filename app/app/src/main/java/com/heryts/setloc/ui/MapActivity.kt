package com.heryts.setloc.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.ProgressDialog
import android.content.Intent
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
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
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
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
    private val update by lazy { viewModel.getAvailableUpdate() }
    private val notificationsChannel by lazy { NotificationsChannel() }
    private var favListAdapter: FavListAdapter = FavListAdapter()
    private var mMarker: Marker? = null
    private var mLatLng: LatLng? = null
    private var lat by Delegates.notNull<Double>()
    private var lon by Delegates.notNull<Double>()
    private lateinit var alertDialog: MaterialAlertDialogBuilder
    private lateinit var dialog: AlertDialog

    // --- rute (long press 2x di peta: titik awal & tujuan) ---
    private var routeStart: LatLng? = null
    private var routeEnd: LatLng? = null
    private var routeStartMarker: Marker? = null
    private var routeEndMarker: Marker? = null
    private var routeLine: Polyline? = null
    private var routePoints: List<LatLng> = emptyList()
    private var routeJob: Job? = null
    private var wasRouteRunning = false
    private val routeReady get() = routePoints.size >= 2


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        initializeMap()
        setFloatActionButton()
        setRouteButton()
        observeRoute()
        updateChecker()

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
                startRoute()
                return@setOnClickListener
            }
            viewModel.update(true, lat, lon)
            mLatLng.let {
                mMarker?.position = it!!
            }
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
            mLatLng.let {
                viewModel.update(false, it!!.latitude, it.longitude)
            }
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
            mLatLng.let {
                mMarker = addMarker(
                    MarkerOptions().position(it!!).draggable(false)
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)).visible(false)
                )
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(it, zoom))
            }
            uiSettings.isZoomControlsEnabled = true
            if (checkSinglePermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                mMap.isMyLocationEnabled = true;
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
        if (routeStart != null) clearRoute()   // tap biasa = kembali ke mode satu titik
        mLatLng = latLng
        mMarker?.let { marker ->
            mLatLng.let {
                marker.position = it!!
                marker.isVisible = true
                mMap.animateCamera(CameraUpdateFactory.newLatLng(it))
                lat = it.latitude
                lon = it.longitude
            }
        }
    }


    // ------------------------------------------------------------------ rute

    override fun onMapLongClick(latLng: LatLng) {
        if (RouteRunner.isRunning) {
            showToast(getString(R.string.route_stop_first))
            return
        }
        if (routeStart == null || routeEnd != null) {
            clearRoute()
            routeStart = latLng
            routeStartMarker = mMap.addMarker(
                MarkerOptions().position(latLng)
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
            )
            showToast(getString(R.string.route_pick_start))
            updateRouteFab()
        } else {
            routeEnd = latLng
            routeEndMarker = mMap.addMarker(
                MarkerOptions().position(latLng)
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
            )
            fetchRoute()
        }
    }

    private fun clearRoute() {
        routeJob?.cancel()
        routeJob = null
        routeStartMarker?.remove()
        routeEndMarker?.remove()
        routeLine?.remove()
        routeStartMarker = null
        routeEndMarker = null
        routeLine = null
        routeStart = null
        routeEnd = null
        routePoints = emptyList()
        updateRouteFab()
    }

    /** Gagal cari rute: titik awal dipertahankan, titik tujuan dibuang supaya bisa dipilih ulang. */
    private fun dropRouteEnd() {
        routeEndMarker?.remove()
        routeEndMarker = null
        routeEnd = null
        routeLine?.remove()
        routeLine = null
        routePoints = emptyList()
        updateRouteFab()
    }

    private fun fetchRoute() {
        val from = routeStart ?: return
        val to = routeEnd ?: return
        routeLine?.remove()
        routeLine = null
        routePoints = emptyList()
        updateRouteFab()
        if (!isNetworkConnected()) {
            showToast(getString(R.string.no_internet))
            dropRouteEnd()
            return
        }
        showToast(getString(R.string.route_searching))
        routeJob?.cancel()
        routeJob = lifecycleScope.launch {
            try {
                val result = RouteClient.fetch(this@MapActivity, from, to, PrefManager.routeMode)
                routePoints = result.points
                routeLine = mMap.addPolyline(
                    PolylineOptions()
                        .addAll(result.points)
                        .width(12f)
                        .color(ContextCompat.getColor(this@MapActivity, R.color.primary))
                        .zIndex(1f)
                )
                try {
                    val bounds = LatLngBounds.Builder()
                    result.points.forEach { bounds.include(it) }
                    mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 140))
                } catch (_: Exception) {
                    // layout peta belum siap: abaikan, rute tetap tergambar
                }
                updateRouteFab()
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                showToast(e.message ?: getString(R.string.address_not_found))
                dropRouteEnd()
            }
        }
    }

    private fun setRouteButton() {
        binding.route.setOnClickListener { if (routeReady) showRouteOptions() }
        binding.routeDelete.setOnClickListener { clearRoute() }
    }

    /** Tombol centang hanya tampil bila rute siap, belum jalan, dan Play sedang tampil. */
    private fun updateRouteFab() {
        val idle = !RouteRunner.isRunning && binding.start.visibility == View.VISIBLE
        binding.route.visibility = if (routeReady && idle) View.VISIBLE else View.GONE
        // hapus: tampil begitu titik awal dipilih (juga saat rute gagal / belum selesai dicari)
        binding.routeDelete.visibility = if (routeStart != null && idle) View.VISIBLE else View.GONE
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
        RouteRunner.start(routePoints, PrefManager.routeSpeed, PrefManager.routeWaitStart)
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
                    if (pos != null && (s.running || s.finished)) {
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
                    } else if (!s.running && !s.finished && wasRouteRunning) {
                        // dihentikan dari tombol Stop di notifikasi
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
        val waitEt = view.findViewById<EditText>(R.id.route_wait)

        val modeIds = intArrayOf(R.id.mode_walk, R.id.mode_bike, R.id.mode_motor, R.id.mode_car)
        val defaultSpeeds = floatArrayOf(5f, 15f, 40f, 50f)
        val oldMode = PrefManager.routeMode.coerceIn(0, modeIds.size - 1)
        val distance = RouteRunner.routeLength(routePoints)

        fun num(f: Float) = if (f % 1f == 0f) f.toInt().toString() else f.toString()

        fun refreshSummary() {
            val sp = speedEt.text.toString().toFloatOrNull()
            summary.text = if (sp != null && sp > 0f) {
                getString(
                    R.string.route_summary,
                    formatDistance(distance),
                    formatDuration(distance / (sp / 3.6))
                )
            } else formatDistance(distance)
        }

        chips.check(modeIds[oldMode])
        speedEt.setText(num(PrefManager.routeSpeed))
        waitEt.setText(num(PrefManager.routeWaitStart))
        refreshSummary()

        speedEt.doAfterTextChanged { refreshSummary() }
        chips.setOnCheckedStateChangeListener { _, checkedIds ->
            val idx = checkedIds.firstOrNull()?.let { modeIds.indexOf(it) } ?: -1
            if (idx >= 0) speedEt.setText(num(defaultSpeeds[idx]))
        }

        view.findViewById<View>(R.id.route_clear).setOnClickListener {
            clearRoute()
            sheet.dismiss()
        }
        view.findViewById<View>(R.id.route_save).setOnClickListener {
            val sp = speedEt.text.toString().toFloatOrNull()
            if (sp == null || sp < 1f || sp > 300f) {
                showToast(getString(R.string.route_invalid_speed))
                return@setOnClickListener
            }
            val wait = (waitEt.text.toString().toFloatOrNull() ?: 0f).coerceIn(0f, 600f)
            val newMode = modeIds.indexOf(chips.checkedChipId).let { if (it < 0) oldMode else it }
            PrefManager.routeSpeed = sp
            PrefManager.routeWaitStart = wait
            sheet.dismiss()
            if (newMode != oldMode) {
                PrefManager.routeMode = newMode
                fetchRoute()   // jenis kendaraan beda -> rute bisa beda
            }
        }
        sheet.setContentView(view)
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

    // ------------------------------------------------------------------ /rute

    private fun moveMapToNewLocation(moveNewLocation: Boolean) {
        if (moveNewLocation) {
            mLatLng = LatLng(lat, lon)
            mLatLng.let { latLng ->
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng!!, 12.0f))
                mMarker?.apply {
                    position = latLng
                    isVisible = true
                    showInfoWindow()
                }
            }
        }

    }


    override fun onResume() {
        super.onResume()
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
        val progressBar = ProgressDialog(this)
        progressBar.setMessage("Searching...")
        alertDialog.setTitle("Search")
        alertDialog.setView(view)
        alertDialog.setPositiveButton("Search") { _, _ ->
            if (isNetworkConnected()) {
                val getInput = editText.text.toString().trim()
                if (getInput.isNotEmpty()) {
                    lifecycleScope.launch(Dispatchers.Main) {
                        getSearchAddress(getInput)
                            .catch { e ->
                                Log.e("MapActivity", "search flow error", e)
                                emit(SearchProgress.Fail(getString(R.string.address_not_found)))
                            }
                            .collect { result ->
                                when (result) {
                                    is SearchProgress.Progress -> {
                                        if (!isFinishing) progressBar.show()
                                    }
                                    is SearchProgress.Complete -> {
                                        progressBar.dismissSafe()
                                        lat = result.lat
                                        lon = result.lon
                                        if (::mMap.isInitialized) moveMapToNewLocation(true)
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
                if (!mMarker?.isVisible!!){
                  showToast("Location not select")
                }else{
                    viewModel.storeFavorite(s, lat, lon)
                    viewModel.response.observe(this@MapActivity){
                        if (it == (-1).toLong()) showToast("Can't save") else showToast("Save")
                    }
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
            it.let {
                lat = it.lat!!
                lon = it.lng!!
            }
            moveMapToNewLocation(true)
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
                viewModel.doGetUserDetails()
                viewModel.allFavList.collect {
                    favListAdapter.submitList(it)
                }
            }
        }

    }


    private fun updateDialog(){
        alertDialog = MaterialAlertDialogBuilder(this)
        alertDialog.setTitle(R.string.update_available)
        alertDialog.setMessage(update?.changelog)
        alertDialog.setPositiveButton(getString(R.string.update_button)) { _, _ ->
            MaterialAlertDialogBuilder(this).apply {
                val view = layoutInflater.inflate(R.layout.update_dialog, null)
                val progress = view.findViewById<LinearProgressIndicator>(R.id.update_download_progress)
                val cancel = view.findViewById<AppCompatButton>(R.id.update_download_cancel)
                setView(view)
                cancel.setOnClickListener {
                    viewModel.cancelDownload(this@MapActivity)
                    dialog.dismiss()
                }
                lifecycleScope.launch {
                    viewModel.downloadState.collect {
                        when (it) {
                            is MainViewModel.State.Downloading -> {
                                if (it.progress > 0) {
                                    progress.isIndeterminate = false
                                    progress.progress = it.progress
                                }
                            }
                            is MainViewModel.State.Done -> {
                                viewModel.openPackageInstaller(this@MapActivity, it.fileUri)
                                viewModel.clearUpdate()
                                dialog.dismiss()
                            }

                            is MainViewModel.State.Failed -> {
                                Toast.makeText(
                                    this@MapActivity,
                                    R.string.bs_update_download_failed,
                                    Toast.LENGTH_LONG
                                ).show()
                                dialog.dismiss()

                            }
                            else -> {}
                        }
                    }
                }
                update?.let { it ->
                    viewModel.startDownload(this@MapActivity, it)
                } ?: run {
                    dialog.dismiss()
                }
            }.run {
                dialog = create()
                dialog.show()
            }
        }
        dialog = alertDialog.create()
        dialog.show()

    }

    private fun updateChecker(){
        lifecycleScope.launchWhenResumed {
            viewModel.update.collect{
                if (it!= null){
                    updateDialog()
                }
            }
        }
    }


    private fun getSearchAddress(address: String): Flow<SearchProgress> = flow {
        emit(SearchProgress.Progress)
        emit(resolveAddress(address.trim()))
    }.flowOn(Dispatchers.IO)

    // Dipisah dari flow supaya try/catch tidak menelan exception downstream (emit)
    private fun resolveAddress(input: String): SearchProgress {
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
                    .getFromLocationName(input, 5)
                // ambil hasil pertama (paling relevan), tidak wajib cuma 1 hasil
                val first = list?.firstOrNull()
                if (first != null) SearchProgress.Complete(first.latitude, first.longitude) else notFound
            }
        } catch (io: IOException) {
            SearchProgress.Fail(getString(R.string.no_internet))
        } catch (e: Exception) {
            Log.e("MapActivity", "search failed", e)
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


    private fun cancelNotification(){
        notificationsChannel.cancelAllNotifications(this)
    }


}


private fun ProgressDialog.dismissSafe() {
    try { if (isShowing) dismiss() } catch (_: Exception) {}
}

sealed class SearchProgress {
    object Progress : SearchProgress()
    data class Complete(val lat: Double , val lon : Double) : SearchProgress()
    data class Fail(val error: String?) : SearchProgress()
}