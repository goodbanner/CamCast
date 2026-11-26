package io.github.goodbanner.camcast

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

data class StreamPrefs(
    val url: String = "rtmp://192.168.1.50/live/cam",
    val width: Int = 1280,
    val height: Int = 720,
    val fps: Int = 30,
    val bitrateKbps: Int = 2000,
    val gopSec: Int = 1,
    val rotation: Int = 0,
    val profile: String = "baseline",
    val audioOn: Boolean = true,
    val sampleRate: Int = 44100,
    val audioBitrateKbps: Int = 128,
    val reconnectMs: Int = 2000,
) {
    fun toJson(): String = JSONObject().apply {
        put("url", url)
        put("width", width)
        put("height", height)
        put("fps", fps)
        put("bitrateKbps", bitrateKbps)
        put("gopSec", gopSec)
        put("rotation", rotation)
        put("profile", profile)
        put("audioOn", audioOn)
        put("sampleRate", sampleRate)
        put("audioBitrateKbps", audioBitrateKbps)
        put("reconnectMs", reconnectMs)
    }.toString()

    companion object {
        private const val STORE = "camcast"

        fun fromJson(s: String): StreamPrefs {
            val o = JSONObject(s)
            val d = StreamPrefs()
            return StreamPrefs(
                url = o.optString("url", d.url),
                width = o.optInt("width", d.width),
                height = o.optInt("height", d.height),
                fps = o.optInt("fps", d.fps),
                bitrateKbps = o.optInt("bitrateKbps", d.bitrateKbps),
                gopSec = o.optInt("gopSec", d.gopSec),
                rotation = o.optInt("rotation", d.rotation),
                profile = o.optString("profile", d.profile),
                audioOn = o.optBoolean("audioOn", d.audioOn),
                sampleRate = o.optInt("sampleRate", d.sampleRate),
                audioBitrateKbps = o.optInt("audioBitrateKbps", d.audioBitrateKbps),
                reconnectMs = o.optInt("reconnectMs", d.reconnectMs),
            )
        }

        private fun sp(c: Context): SharedPreferences =
            c.getSharedPreferences(STORE, Context.MODE_PRIVATE)

        private fun presetsObject(c: Context): JSONObject = runCatching {
            JSONObject(sp(c).getString("presets", null) ?: "{}")
        }.getOrDefault(JSONObject())

        fun load(c: Context): StreamPrefs {
            val raw = sp(c).getString("current", null) ?: return StreamPrefs()
            return runCatching { fromJson(raw) }.getOrDefault(StreamPrefs())
        }

        fun saveCurrent(c: Context, p: StreamPrefs): Boolean {
            val ok = sp(c).edit().putString("current", p.toJson()).commit()
            android.util.Log.i("CamCast", "saveCurrent commit=$ok")
            return ok
        }

        fun presetNames(c: Context): Set<String> {
            val out = sortedSetOf<String>()
            for (k in presetsObject(c).keys()) out.add(k)
            return out
        }

        fun savePreset(c: Context, name: String, p: StreamPrefs) {
            val o = presetsObject(c)
            o.put(name, JSONObject(p.toJson()))
            sp(c).edit().putString("presets", o.toString()).apply()
        }

        fun loadPreset(c: Context, name: String): StreamPrefs? {
            val o = presetsObject(c)
            if (!o.has(name)) return null
            return runCatching { fromJson(o.getJSONObject(name).toString()) }.getOrNull()
        }

        fun presetsRaw(c: Context): String = sp(c).getString("presets", "{}") ?: "{}"

        fun importPresets(c: Context, json: String) {
            JSONObject(json) // 校验，非法直接抛
            sp(c).edit().putString("presets", json).apply()
        }
    }
}
