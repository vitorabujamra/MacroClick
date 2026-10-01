package com.vitor.macroclick

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class StepType(val label: String) {
    TAP("Toque"), LONG("Toque longo"), SWIPE("Deslizar")
}

class Step(
    var type: StepType = StepType.TAP,
    var points: MutableList<FloatArray> = mutableListOf(),
    var duration: Long = 50,
    var delay: Long = 500,
    var repeat: Int = 1,
    var repeatInterval: Long = 100
) {
    fun toJson(): JSONObject {
        val pts = JSONArray()
        for (p in points) pts.put(JSONArray().put(p[0].toDouble()).put(p[1].toDouble()))
        return JSONObject()
            .put("type", type.name)
            .put("points", pts)
            .put("duration", duration)
            .put("delay", delay)
            .put("repeat", repeat)
            .put("repeatInterval", repeatInterval)
    }

    companion object {
        fun fromJson(o: JSONObject): Step {
            val pts = mutableListOf<FloatArray>()
            val arr = o.optJSONArray("points") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val p = arr.getJSONArray(i)
                pts.add(floatArrayOf(p.getDouble(0).toFloat(), p.getDouble(1).toFloat()))
            }
            val type = try { StepType.valueOf(o.optString("type", "TAP")) } catch (e: Exception) { StepType.TAP }
            return Step(
                type, pts,
                o.optLong("duration", 50),
                o.optLong("delay", 500),
                o.optInt("repeat", 1),
                o.optLong("repeatInterval", 100)
            )
        }
    }
}

class Macro(
    var id: Long = 0,
    var name: String = "",
    var steps: MutableList<Step> = mutableListOf(),
    var loops: Int = 1,          // 0 = infinito
    var loopDelay: Long = 1000
) {
    fun toJson(): JSONObject {
        val arr = JSONArray()
        for (s in steps) arr.put(s.toJson())
        return JSONObject()
            .put("id", id)
            .put("name", name)
            .put("loops", loops)
            .put("loopDelay", loopDelay)
            .put("steps", arr)
    }

    fun copy(): Macro = fromJson(toJson())

    fun loopsLabel(): String = if (loops == 0) "∞" else "${loops}x"

    companion object {
        fun fromJson(o: JSONObject): Macro {
            val steps = mutableListOf<Step>()
            val arr = o.optJSONArray("steps") ?: JSONArray()
            for (i in 0 until arr.length()) steps.add(Step.fromJson(arr.getJSONObject(i)))
            return Macro(
                o.optLong("id", 0),
                o.optString("name", "Macro"),
                steps,
                o.optInt("loops", 1),
                o.optLong("loopDelay", 1000)
            )
        }
    }
}

object Storage {
    private const val PREFS = "macroclick"
    private const val KEY = "macros"

    fun loadAll(ctx: Context): MutableList<Macro> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        return parseList(raw)
    }

    fun parseList(raw: String): MutableList<Macro> {
        val list = mutableListOf<Macro>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) list.add(Macro.fromJson(arr.getJSONObject(i)))
        } catch (e: Exception) { }
        return list
    }

    fun toJsonString(list: List<Macro>): String {
        val arr = JSONArray()
        for (m in list) arr.put(m.toJson())
        return arr.toString()
    }

    fun saveAll(ctx: Context, list: List<Macro>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, toJsonString(list)).apply()
    }

    fun get(ctx: Context, id: Long): Macro? = loadAll(ctx).firstOrNull { it.id == id }

    fun upsert(ctx: Context, m: Macro) {
        val list = loadAll(ctx)
        val idx = list.indexOfFirst { it.id == m.id }
        if (idx >= 0) list[idx] = m.copy() else list.add(m.copy())
        saveAll(ctx, list)
    }

    fun delete(ctx: Context, id: Long) {
        saveAll(ctx, loadAll(ctx).filter { it.id != id })
    }
}
