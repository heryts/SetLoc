package com.heryts.setloc.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.heryts.setloc.R
import com.heryts.setloc.favourite.Favourite
import com.heryts.setloc.history.HistoryItem
import java.util.Locale

private val COORD_LABEL = Regex("^[-+]?\\d+(\\.\\d+)?, *[-+]?\\d+(\\.\\d+)?$")

internal data class PlaceRow(
    val label: String,
    val lat: Double,
    val lon: Double,
    val history: Boolean,
    val source: HistoryItem? = null
) {
    val title: String
        get() = if (COORD_LABEL.matches(label)) label else label.substringBefore(", ")
    val subtitle: String
        get() = if (COORD_LABEL.matches(label) || !label.contains(", ")) "" else label.substringAfter(", ")
}

internal class PlaceAdapter(
    private val onClick: (PlaceRow) -> Unit,
    private val onRemove: (PlaceRow) -> Unit
) : ListAdapter<PlaceRow, PlaceAdapter.VH>(object : DiffUtil.ItemCallback<PlaceRow>() {
    override fun areItemsTheSame(a: PlaceRow, b: PlaceRow) =
        a.label == b.label && a.lat == b.lat && a.lon == b.lon && a.history == b.history
    override fun areContentsTheSame(a: PlaceRow, b: PlaceRow) = a == b
}) {
    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.row_icon)
        val title: TextView = v.findViewById(R.id.row_title)
        val subtitle: TextView = v.findViewById(R.id.row_subtitle)
        val remove: ImageButton = v.findViewById(R.id.row_remove)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_place, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = getItem(position)
        holder.icon.setImageResource(if (row.history) R.drawable.ic_history else R.drawable.ic_place)
        holder.title.text = row.title
        holder.subtitle.text = row.subtitle
        holder.subtitle.visibility = if (row.subtitle.isEmpty()) View.GONE else View.VISIBLE
        holder.remove.visibility = if (row.history) View.VISIBLE else View.GONE
        holder.itemView.setOnClickListener { onClick(row) }
        holder.remove.setOnClickListener { onRemove(row) }
    }
}

internal class FavChipAdapter(
    private val onClick: (Favourite) -> Unit,
    private val onLongClick: (Favourite) -> Unit
) : ListAdapter<Favourite, FavChipAdapter.VH>(object : DiffUtil.ItemCallback<Favourite>() {
    override fun areItemsTheSame(a: Favourite, b: Favourite) = a.id == b.id
    override fun areContentsTheSame(a: Favourite, b: Favourite) = a == b
}) {
    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.chip_title)
        val coords: TextView = v.findViewById(R.id.chip_coords)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_fav_chip, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val f = getItem(position)
        holder.title.text = f.address
        holder.coords.text = if (f.lat != null && f.lng != null)
            String.format(Locale.US, "%.6f,%.6f", f.lat, f.lng) else ""
        holder.itemView.setOnClickListener { onClick(f) }
        holder.itemView.setOnLongClickListener { onLongClick(f); true }
    }
}
