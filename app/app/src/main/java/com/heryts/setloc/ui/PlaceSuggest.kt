package com.heryts.setloc.ui

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Filter
import androidx.appcompat.widget.AppCompatAutoCompleteTextView
import com.heryts.setloc.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val MIN_CHARS = 3
private const val DEBOUNCE_MS = 500L
private val NUMERIC_ONLY = Regex("[-+0-9.,\\s]+")

private class SuggestAdapter(context: Context) :
    ArrayAdapter<String>(context, R.layout.item_suggest, mutableListOf()) {
    override fun getFilter() = object : Filter() {
        override fun performFiltering(constraint: CharSequence?) =
            FilterResults().also { it.count = this@SuggestAdapter.count }
        override fun publishResults(constraint: CharSequence?, results: FilterResults?) {}
    }
}

internal fun AppCompatAutoCompleteTextView.attachPlaceSuggestions(
    scope: CoroutineScope,
    onPick: (PlaceCandidate) -> Unit
) {
    val adapter = SuggestAdapter(context)
    var items: List<PlaceCandidate> = emptyList()
    var job: Job? = null
    setAdapter(adapter)
    setDropDownBackgroundResource(R.drawable.bg_suggest)
    threshold = 1

    fun publish(list: List<PlaceCandidate>) {
        items = list
        adapter.clear()
        adapter.addAll(list.map { it.label })
        adapter.notifyDataSetChanged()
        if (list.isNotEmpty() && isAttachedToWindow && hasFocus()) showDropDown() else dismissDropDown()
    }

    setOnItemClickListener { _, _, position, _ ->
        items.getOrNull(position)?.let(onPick)
    }

    addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            job?.cancel()
            val text = s?.toString()?.trim().orEmpty()
            if (text.length < MIN_CHARS || NUMERIC_ONLY.matches(text) || items.any { it.label == text }) {
                if (text.length < MIN_CHARS) publish(emptyList())
                return
            }
            job = scope.launch {
                delay(DEBOUNCE_MS)
                val result = try {
                    searchPlaces(context, text)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                }
                publish(result)
            }
        }
    })

    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {}
        override fun onViewDetachedFromWindow(v: View) { job?.cancel() }
    })
}
