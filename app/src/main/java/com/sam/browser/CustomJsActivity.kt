package com.sam.browser

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class CustomJsActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var adapter: ScriptAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_custom_js)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        recycler = findViewById(R.id.scriptRecycler)
        tvEmpty  = findViewById(R.id.tvEmptyScripts)

        adapter = ScriptAdapter(
            items    = CustomJsManager.getAll(this).toMutableList(),
            onEdit   = { showEditor(it) },
            onDelete = { confirmDelete(it) },
            onToggle = { script, enabled ->
                CustomJsManager.update(this, script.copy(enabled = enabled))
            }
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(
            R.id.fabAddScript
        ).setOnClickListener {
            showEditor(null)
        }

        refresh()
    }

    private fun refresh() {
        val scripts = CustomJsManager.getAll(this)
        adapter.refresh(scripts)
        tvEmpty.visibility = if (scripts.isEmpty())
            android.view.View.VISIBLE else android.view.View.GONE
    }

    @SuppressLint("InflateParams")
    private fun showEditor(script: CustomScript?) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_edit_script, null)
        val etName  = view.findViewById<EditText>(R.id.etScriptName)
        val etCode  = view.findViewById<EditText>(R.id.etScriptCode)
        val rgStage = view.findViewById<RadioGroup>(R.id.rgInjectStage)

        if (script != null) {
            etName.setText(script.name)
            etCode.setText(script.code)
            if (script.injectOn == "start") rgStage.check(R.id.rbStart)
            else rgStage.check(R.id.rbFinish)
        } else {
            rgStage.check(R.id.rbFinish)
        }

        AlertDialog.Builder(this, R.style.HomepageDialogTheme)
            .setTitle(if (script == null) "New Script" else "Edit Script")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val name     = etName.text.toString().trim().ifBlank { "Script" }
                val code     = etCode.text.toString()
                val injectOn = if (rgStage.checkedRadioButtonId == R.id.rbStart) "start" else "finish"
                if (script == null) {
                    CustomJsManager.add(this, name, code, injectOn)
                } else {
                    CustomJsManager.update(this, script.copy(
                        name = name, code = code, injectOn = injectOn
                    ))
                }
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(script: CustomScript) {
        AlertDialog.Builder(this, R.style.HomepageDialogTheme)
            .setTitle("Delete script?")
            .setMessage("\"${script.name}\" will be removed.")
            .setPositiveButton("Delete") { _, _ ->
                CustomJsManager.delete(this, script.id)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
