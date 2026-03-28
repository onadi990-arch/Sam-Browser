package com.sam.browser

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.Switch
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ScriptAdapter(
    private var items: MutableList<CustomScript>,
    private val onEdit: (CustomScript) -> Unit,
    private val onDelete: (CustomScript) -> Unit,
    private val onToggle: (CustomScript, Boolean) -> Unit
) : RecyclerView.Adapter<ScriptAdapter.VH>() {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvName:    TextView    = v.findViewById(R.id.tvScriptName)
        val tvStage:   TextView    = v.findViewById(R.id.tvScriptStage)
        val swEnabled: Switch      = v.findViewById(R.id.swScriptEnabled)
        val btnEdit:   ImageButton = v.findViewById(R.id.btnScriptEdit)
        val btnDelete: ImageButton = v.findViewById(R.id.btnScriptDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_script, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val s = items[pos]
        h.tvName.text    = s.name
        h.tvStage.text   = if (s.injectOn == "start") "on page start" else "on page finish"
        h.swEnabled.setOnCheckedChangeListener(null)
        h.swEnabled.isChecked = s.enabled
        h.swEnabled.setOnCheckedChangeListener { _, checked -> onToggle(s, checked) }
        h.btnEdit.setOnClickListener   { onEdit(s) }
        h.btnDelete.setOnClickListener { onDelete(s) }
    }

    fun refresh(newItems: List<CustomScript>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }
}
