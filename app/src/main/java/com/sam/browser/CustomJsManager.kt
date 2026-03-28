package com.sam.browser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class CustomScript(
    val id: String,
    val name: String,
    val code: String,
    val enabled: Boolean,
    val injectOn: String = "finish"   // "start" | "finish"
)

object CustomJsManager {

    private const val PREFS  = "SamBrowserPrefs"
    private const val KEY    = "custom_js_scripts"

    fun getAll(ctx: Context): List<CustomScript> {
        val json = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                CustomScript(
                    id       = o.optString("id", "$i"),
                    name     = o.optString("name", "Script ${i + 1}"),
                    code     = o.optString("code", ""),
                    enabled  = o.optBoolean("enabled", true),
                    injectOn = o.optString("injectOn", "finish")
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    fun saveAll(ctx: Context, scripts: List<CustomScript>) {
        val arr = JSONArray()
        scripts.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                put("code", s.code)
                put("enabled", s.enabled)
                put("injectOn", s.injectOn)
            })
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun add(ctx: Context, name: String, code: String, injectOn: String = "finish") {
        val list = getAll(ctx).toMutableList()
        list.add(CustomScript(
            id       = System.nanoTime().toString(),
            name     = name.ifBlank { "Script ${list.size + 1}" },
            code     = code,
            enabled  = true,
            injectOn = injectOn
        ))
        saveAll(ctx, list)
    }

    fun update(ctx: Context, updated: CustomScript) =
        saveAll(ctx, getAll(ctx).map { if (it.id == updated.id) updated else it })

    fun delete(ctx: Context, id: String) =
        saveAll(ctx, getAll(ctx).filter { it.id != id })

    /** Scripts to inject at a specific stage */
    fun forStage(ctx: Context, injectOn: String): List<CustomScript> =
        getAll(ctx).filter { it.enabled && it.injectOn == injectOn }
}
