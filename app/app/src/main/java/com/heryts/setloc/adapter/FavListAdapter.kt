package com.heryts.setloc.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.heryts.setloc.R
import com.heryts.setloc.favourite.Favourite

class FavListAdapter : ListAdapter<Favourite, FavListAdapter.VH>(object : DiffUtil.ItemCallback<Favourite>() {
    override fun areItemsTheSame(a: Favourite, b: Favourite) = a.id == b.id
    override fun areContentsTheSame(a: Favourite, b: Favourite) = a == b
}) {
    var onItemClick: ((Favourite) -> Unit)? = null
    var onItemDelete: ((Favourite) -> Unit)? = null

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val address: TextView = v.findViewById(R.id.address)
        val delete: View = v.findViewById(R.id.del)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.fav_items, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val f = getItem(position)
        holder.address.text = f.address
        holder.address.setOnClickListener { onItemClick?.invoke(f) }
        holder.delete.setOnClickListener { onItemDelete?.invoke(f) }
    }
}
