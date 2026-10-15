package com.jarvis.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** The kind of wire API a provider speaks. */
enum class ApiKind { ANTHROPIC, OPENAI_COMPATIBLE }

/** A built-in provider preset. Every field except [kind] can be overridden by the user. */
data class ProviderPreset(
    val id: String,
    val label: String,
    val kind: ApiKind,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val needsKey: Boolean,
)

val PROVIDERS = listOf(
    ProviderPreset("anthropic", "Claude (Anthropic)", ApiKind.ANTHROPIC, "https://api.anthropic.com", "claude-opus-5-5", true),
    ProviderPreset("openai", "OpenAI", ApiKind.OPENAI_COMPATIBLE, "https://api.openai.com/v1", "gpt-5", true),
    ProviderPreset("lmstudio", "LM Studio (local)", ApiKind.OPENAI_COMPATIBLE, "http://192.168.1.100:1234/v1", "local-model", false),
    ProviderPreset("ollama", "Ollama (local)", ApiKind.OPENAI_COMPATIBLE, "http://192.168.1.100:11434/v1", "qwen2.5:14b", false),
    ProviderPreset("openrouter", "OpenRouter", ApiKind.OPENAI_COMPATIBLE, "https://openrouter.ai/api/v1", "anthropic/claude-opus-5-5", true),
    ProviderPreset("gemini", "Google Gemini", ApiKind.OPENAI_COMPATIBLE, "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash", true),
    ProviderPreset("groq", "Groq", ApiKind.OPENAI_COMPATIBLE, "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", true),
    ProviderPreset("custom", "Custom (OpenAI-compatible)", ApiKind.OPENAI_COMPATIBLE, "http://localhost:8080/v1", "", false),
)

/** A model the user has saved. Several can exist; one is primary, the rest are fallbacks in order. */
data class ModelProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val providerId: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val effort: String = "medium",
) {
    val preset get() = PROVIDERS.firstOrNull { it.id == providerId } ?: PROVIDERS.last()
    fun toConfig() = ProviderConfig(preset, baseUrl, apiKey, model, if (preset.kind == ApiKind.ANTHROPIC) effort else "")

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("provider", providerId).put("base", baseUrl)
        .put("key", apiKey).put("model", model).put("effort", effort)

    companion object {
        fun fromJson(o: JSONObject) = ModelProfile(
            o.getString("id"), o.optString("name"), o.optString("provider", "custom"), o.optString("base"),
            o.optString("key"), o.optString("model"), o.optString("effort", "medium"),
        )
    }
}

/** Resolved settings for the provider the agent should talk to. */
data class ProviderConfig(
    val preset: ProviderPreset,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    /** Claude only: "low" | "medium" | "high" | "xhigh" | "max" | "" (omit). */
    val effort: String,
)

class Settings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    var maxSteps: Int
        get() = prefs.getInt("max_steps", 40)
        set(v) = prefs.edit().putInt("max_steps", v).apply()

    /** Whether the model may request screenshots (needs a vision-capable model and Android 11+). */
    var allowScreenshots: Boolean
        get() = prefs.getBoolean("screenshots", true)
        set(v) = prefs.edit().putBoolean("screenshots", v).apply()

    var wakeWordEnabled: Boolean
        get() = prefs.getBoolean("wake_enabled", true)
        set(v) = prefs.edit().putBoolean("wake_enabled", v).apply()

    /** Whether the user has Jarvis switched on. Only the user changes this, so "Turn off" sticks. */
    var switchedOn: Boolean
        get() = prefs.getBoolean("switched_on", false)
        set(v) = prefs.edit().putBoolean("switched_on", v).apply()

    var startOnBoot: Boolean
        get() = prefs.getBoolean("boot", false)
        set(v) = prefs.edit().putBoolean("boot", v).apply()

    /** Free-form facts the agent should know, e.g. "My wife is saved as 'Amaka ❤️' in WhatsApp". */
    var personalNotes: String
        get() = prefs.getString("notes", "")!!
        set(v) = prefs.edit().putString("notes", v).apply()

    /** Show the task island at the top of the screen while Jarvis works. */
    var showIsland: Boolean
        get() = prefs.getBoolean("island", true)
        set(v) = prefs.edit().putBoolean("island", v).apply()

    var onboarded: Boolean
        get() = prefs.getBoolean("onboarded", false)
        set(v) = prefs.edit().putBoolean("onboarded", v).apply()

    // ---- Models ----

    /** Try the next saved model if the primary one fails mid-task. */
    var fallbackEnabled: Boolean
        get() = prefs.getBoolean("fallback", true)
        set(v) = prefs.edit().putBoolean("fallback", v).apply()

    fun profiles(): List<ModelProfile> {
        migrateLegacyProvider()
        val arr = JSONArray(prefs.getString("profiles", "[]"))
        return (0 until arr.length()).map { ModelProfile.fromJson(arr.getJSONObject(it)) }
    }

    var primaryId: String?
        get() = prefs.getString("primary", null)?.takeIf { id -> profiles().any { it.id == id } } ?: profiles().firstOrNull()?.id
        set(v) = prefs.edit().putString("primary", v).apply()

    fun primary(): ModelProfile? = profiles().let { all -> all.firstOrNull { it.id == primaryId } ?: all.firstOrNull() }

    /** Primary first, then the others in the order they were added. */
    fun modelsInOrder(): List<ModelProfile> {
        val all = profiles()
        val p = primary() ?: return all
        return listOf(p) + all.filter { it.id != p.id }
    }

    fun saveProfile(profile: ModelProfile) {
        val all = profiles().toMutableList()
        val i = all.indexOfFirst { it.id == profile.id }
        if (i >= 0) all[i] = profile else all += profile
        writeProfiles(all)
        if (all.size == 1) primaryId = profile.id
    }

    fun deleteProfile(id: String) {
        writeProfiles(profiles().filter { it.id != id })
        if (prefs.getString("primary", null) == id) primaryId = profiles().firstOrNull()?.id
    }

    private fun writeProfiles(list: List<ModelProfile>) =
        prefs.edit().putString("profiles", JSONArray(list.map { it.toJson() }).toString()).apply()

    /** Earlier versions stored one setting per provider; turn any that were filled in into profiles. */
    private fun migrateLegacyProvider() {
        if (prefs.contains("profiles")) return
        val migrated = PROVIDERS.mapNotNull { p ->
            val key = prefs.getString("p.${p.id}.key", "")!!
            val model = prefs.getString("p.${p.id}.model", null)
            if (key.isBlank() && (p.needsKey || model == null)) return@mapNotNull null
            ModelProfile(
                name = p.label.substringBefore(" ("), providerId = p.id,
                baseUrl = prefs.getString("p.${p.id}.base", null) ?: p.defaultBaseUrl, apiKey = key,
                model = model ?: p.defaultModel, effort = prefs.getString("p.${p.id}.effort", "medium")!!.ifEmpty { "medium" },
            )
        }
        val legacyPrimary = prefs.getString("provider", null)
        prefs.edit()
            .putString("profiles", JSONArray(migrated.map { it.toJson() }).toString())
            .putString("primary", migrated.firstOrNull { it.providerId == legacyPrimary }?.id ?: migrated.firstOrNull()?.id)
            .apply()
    }

    // ---- Voice ----

    /** "kokoro" (natural on-device neural voice) or "system" (Android text-to-speech). */
    var voiceEngine: String
        get() = prefs.getString("voice_engine", "kokoro")!!
        set(v) = prefs.edit().putString("voice_engine", v).apply()

    var kokoroSpeaker: Int
        get() = prefs.getInt("kokoro_sid", KOKORO_VOICES.first().sid)
        set(v) = prefs.edit().putInt("kokoro_sid", v).apply()

    var voiceSpeed: Float
        get() = prefs.getFloat("voice_speed", 1.0f)
        set(v) = prefs.edit().putFloat("voice_speed", v).apply()
}

data class KokoroVoice(val sid: Int, val name: String, val accent: String)

/** Speakers in the kokoro-en-v0_19 model; sid is the model's speaker id. British male first: it suits Jarvis. */
val KOKORO_VOICES = listOf(
    KokoroVoice(9, "George", "British male"),
    KokoroVoice(10, "Lewis", "British male"),
    KokoroVoice(7, "Emma", "British female"),
    KokoroVoice(8, "Isabella", "British female"),
    KokoroVoice(5, "Adam", "American male"),
    KokoroVoice(6, "Michael", "American male"),
    KokoroVoice(1, "Bella", "American female"),
    KokoroVoice(2, "Nicole", "American female"),
    KokoroVoice(3, "Sarah", "American female"),
    KokoroVoice(4, "Sky", "American female"),
    KokoroVoice(0, "Default", "American female"),
)
