package com.heryts.setloc.ui

import android.content.Context
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.heryts.setloc.R
import com.heryts.setloc.favourite.FavouriteStore
import com.heryts.setloc.history.SearchHistoryStore
import com.heryts.setloc.utils.NetNative
import com.heryts.setloc.utils.ext.isNetworkConnected
import com.heryts.setloc.utils.ext.showToast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

internal class SearchPanel(
    private val activity: AppCompatActivity,
    private val root: View,
    private val onPick: (PlaceCandidate) -> Unit,
    private val onFavouriteLongClick: (com.heryts.setloc.favourite.Favourite) -> Unit
) {
    private val input = root.findViewById<EditText>(R.id.search_input)
    private val clearBtn = root.findViewById<View>(R.id.search_clear)
    private val icon = root.findViewById<android.widget.ImageButton>(R.id.search_icon)
    private val dropdown = root.findViewById<View>(R.id.dropdown)
    private val sectionHeader = root.findViewById<View>(R.id.section_header)
    private val favSection = root.findViewById<View>(R.id.fav_section)
    private val favRow = root.findViewById<RecyclerView>(R.id.fav_row)
    private val list = root.findViewById<RecyclerView>(R.id.result_list)
    private val sectionTitle = root.findViewById<TextView>(R.id.section_title)
    private val sectionAction = root.findViewById<TextView>(R.id.section_action)
    private val emptyText = root.findViewById<TextView>(R.id.empty_text)
    private val progress = root.findViewById<View>(R.id.search_progress)

    private val favAdapter = FavChipAdapter(
        onClick = { f ->
            val la = f.lat
            val lo = f.lng
            if (la != null && lo != null) {
                close()
                onPick(PlaceCandidate(f.address.orEmpty(), la, lo))
            }
        },
        onLongClick = { onFavouriteLongClick(it) }
    )
    private val listAdapter = PlaceAdapter(
        onClick = { row -> pick(PlaceCandidate(row.label, row.lat, row.lon)) },
        onRemove = { row -> row.source?.let { SearchHistoryStore.remove(it) } }
    )

    private var remote: List<PlaceCandidate> = emptyList()
    private var remoteDone = false
    private var remoteFor = ""
    private var job: Job? = null

    private var open = false
    private var imeHeight = 0
    private var favCount = 0

    var onVisibilityChanged: ((Boolean) -> Unit)? = null
    val isShowing get() = open

    init {
        SearchHistoryStore.load()
        favRow.layoutManager = LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false)
        favRow.adapter = favAdapter
        list.layoutManager = LinearLayoutManager(activity)
        list.adapter = listAdapter
        icon.setOnClickListener { if (open) close() else open() }
        input.setOnFocusChangeListener { _, focused -> if (focused && !open) open() }
        input.setOnClickListener { if (!open) open() }
        clearBtn.setOnClickListener { input.setText("") }
        sectionAction.setOnClickListener { SearchHistoryStore.clear() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { submit(); true } else false
        }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { onTextChanged(s?.toString().orEmpty()) }
        })
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.CREATED) {
                launch {
                    FavouriteStore.all.collect {
                        favCount = it.size
                        favAdapter.submitList(it)
                        refresh()
                    }
                }
                launch { SearchHistoryStore.all.collect { refresh() } }
            }
        }
    }

    fun setIme(bottom: Int) {
        imeHeight = bottom
        if (open) refresh()
    }

    fun open() {
        if (open) return
        open = true
        favRow.scrollToPosition(0)
        icon.setImageResource(R.drawable.ic_arrow_back)
        remote = emptyList()
        remoteDone = false
        refresh()
        onVisibilityChanged?.invoke(true)
        input.requestFocus()
        input.post {
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun close() {
        job?.cancel()
        showProgress(false)
        input.setText("")
        open = false
        (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
        (root.parent as? View)?.requestFocus()
        icon.setImageResource(R.drawable.ic_baseline_search_24)
        dropdown.visibility = View.GONE
        onVisibilityChanged?.invoke(false)
    }

    private fun pick(place: PlaceCandidate) {
        SearchHistoryStore.add(place.label, place.lat, place.lon)
        close()
        onPick(place)
    }

    private fun showProgress(on: Boolean) {
        progress.visibility = if (on) View.VISIBLE else View.GONE
        refresh()
    }

    private fun isCoords(t: String) = NetNative.isCoords(t)

    private fun onTextChanged(text: String) {
        clearBtn.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        job?.cancel()
        val t = text.trim()
        if (t.isEmpty()) {
            remote = emptyList()
            remoteDone = false
            remoteFor = ""
        }
        showProgress(false)
        if (!open) open() else refresh()
        if (t.length < 3 && !isCoords(t)) {
            remote = emptyList()
            remoteDone = false
            refresh()
            return
        }
        job = activity.lifecycleScope.launch {
            if (!isCoords(t)) delay(400)
            showProgress(true)
            try {
                remote = searchPlaces(t)
                remoteFor = t
                remoteDone = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                activity.showToast(activity.getString(R.string.no_internet))
            } catch (_: Exception) {
                remoteDone = true
            }
            showProgress(false)
            refresh()
        }
    }

    private fun inResults(): Boolean =
        input.text.toString().trim().isNotEmpty() && (remote.isNotEmpty() || remoteDone)

    private fun rows(): List<PlaceRow> {
        val t = input.text.toString().trim()
        val history = SearchHistoryStore.all.value
        if (!inResults()) return history.map { PlaceRow(it.label, it.lat, it.lon, true, it) }
        val matches = history.filter { it.label.contains(t, ignoreCase = true) }.take(3)
            .map { PlaceRow(it.label, it.lat, it.lon, true, it) }
        val extra = remote.filter { r -> matches.none { it.label == r.label } }
            .map { PlaceRow(it.label, it.lat, it.lon, false) }
        return matches + extra
    }

    private fun submitTarget(t: String): PlaceRow? {
        val h = SearchHistoryStore.all.value.firstOrNull { it.label.contains(t, ignoreCase = true) }
        if (h != null) return PlaceRow(h.label, h.lat, h.lon, true, h)
        if (remoteFor != t) return null
        return remote.firstOrNull()?.let { PlaceRow(it.label, it.lat, it.lon, false) }
    }

    private fun refresh() {
        if (!open) return
        val results = inResults()
        val data = rows()
        val d = activity.resources.displayMetrics.density
        val screenH = activity.resources.displayMetrics.heightPixels
        val showFav = favCount > 0
        favSection.visibility = if (showFav) View.VISIBLE else View.GONE
        val used = (52 + 12 + 24) * d + (if (showFav) 76 * d else 0f) + 44 * d
        val cap = minOf(screenH * 0.45f, screenH - imeHeight - used).coerceAtLeast(64 * d)
        val height = minOf(data.size * 64 * d, cap).toInt()
        list.layoutParams = list.layoutParams.also { it.height = height }
        list.visibility = if (data.isEmpty()) View.GONE else View.VISIBLE
        listAdapter.submitList(data)
        val searching = progress.visibility == View.VISIBLE
        sectionTitle.text = activity.getString(if (results) R.string.search_results else R.string.search_recent)
        sectionTitle.visibility = if (data.isEmpty()) View.GONE else View.VISIBLE
        sectionHeader.visibility = if (data.isNotEmpty() || searching) View.VISIBLE else View.GONE
        sectionAction.visibility = if (!results && data.isNotEmpty()) View.VISIBLE else View.GONE
        emptyText.visibility = if (results && data.isEmpty()) View.VISIBLE else View.GONE
        val anything = showFav || data.isNotEmpty() || searching || emptyText.visibility == View.VISIBLE
        dropdown.visibility = if (anything) View.VISIBLE else View.GONE
    }

    private fun submit() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        val first = submitTarget(t)
        if (first != null) {
            pick(PlaceCandidate(first.label, first.lat, first.lon))
            return
        }
        if (!activity.isNetworkConnected()) {
            activity.showToast(activity.getString(R.string.no_internet))
            return
        }
        job?.cancel()
        job = activity.lifecycleScope.launch {
            showProgress(true)
            val found = try {
                searchPlaces(t).firstOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            showProgress(false)
            if (found != null) pick(found)
            else activity.showToast(activity.getString(R.string.address_not_found))
        }
    }
}
