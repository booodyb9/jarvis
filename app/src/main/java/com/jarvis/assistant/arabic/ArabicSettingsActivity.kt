package com.jarvis.assistant.arabic

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.jarvis.assistant.*
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.agent.*
import com.jarvis.assistant.ui.padForSystemBars
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.util.concurrent.Executors

/** Credentials and screen permissions live here, outside ordinary chat. */
class ArabicSettingsActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var testSession: ChatSession? = null
    private var testing = false
    private lateinit var state: TextView
    private lateinit var profileLabel: TextView
    private lateinit var serviceLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val settings = Settings(this)
        val column = GlassUi.column(this)
        column.addView(GlassUi.button(this, "رجوع للدردشة") { finish() })
        column.addView(GlassUi.text(this, "الإعدادات", 28f))
        column.addView(GlassUi.text(this, "نموذجك، صوتك، وطريقة مساعدتك", 16f, GlassUi.MUTED))
        profileLabel = GlassUi.text(this, "", 18f); column.addView(profileLabel)
        column.addView(GlassUi.button(this, "اختيار النموذج الأساسي") {
            val profiles = settings.profiles()
            if (profiles.isEmpty()) { editModel(null); return@button }
            AlertDialog.Builder(this).setTitle("النموذج الأساسي")
                .setSingleChoiceItems(profiles.map { it.name + " · " + it.preset.label }.toTypedArray(),
                    profiles.indexOfFirst { it.id == settings.primaryId }) { dialog, which ->
                    settings.primaryId = profiles[which].id
                    ChatConversation.get(this).refreshModel()
                    refresh(); dialog.dismiss()
                }.setNegativeButton("إلغاء", null).show()
        })
        column.addView(GlassUi.button(this, "تعديل النموذج الحالي") { editModel(settings.primary()) })
        column.addView(GlassUi.button(this, "إضافة نموذج آخر") { editModel(null) })
        state = GlassUi.text(this, "", 14f, GlassUi.MUTED); column.addView(state)
        column.addView(GlassUi.button(this, "اختبار الدردشة") { test(false) })
        column.addView(GlassUi.button(this, "اختبار أدوات الشاشة") { test(true) })
        column.addView(GlassUi.text(this, "الاختبار يرسل طلبًا صغيرًا وقد يستهلك رصيدًا. نجاح الدردشة وحده لا يثبت دعم أدوات التحكم أو الصور.", 13f, GlassUi.MUTED))
        column.addView(Switch(this).apply {
            text = "استخدام نموذج احتياطي عند فشل الأساسي"; textSize = 15f; setTextColor(GlassUi.INK)
            isChecked = settings.fallbackEnabled
            setOnCheckedChangeListener { _, checked -> settings.fallbackEnabled = checked }
        })
        column.addView(GlassUi.text(this, "عند تفعيل الاحتياطي قد تُرسل رسالتك لنموذج آخر من النماذج المحفوظة. النموذج الذي رد يظهر مع الرد.", 13f, GlassUi.MUTED))
        column.addView(GlassUi.text(this, "المساعدة على الشاشة", 22f))
        serviceLabel = GlassUi.text(this, "", 14f, GlassUi.MUTED); column.addView(serviceLabel)
        val consent = getSharedPreferences("arabic_consent", 0)
        column.addView(CheckBox(this).apply {
            text = "أسمح بإرسال نص الشاشة أثناء طلباتي لنموذج AI الذي أختاره"
            textSize = 15f; setTextColor(GlassUi.INK); isChecked = consent.getBoolean("screen_text", false)
            setOnCheckedChangeListener { _, checked ->
                consent.edit().putBoolean("screen_text", checked).apply()
                if (!checked) JarvisAccessibilityService.instance?.arabic?.stop(cancelChat = false)
            }
        })
        column.addView(GlassUi.text(this, "الدردشة العادية لا تقرأ الشاشة. وضع الشاشة يشارك العناصر الظاهرة أثناء الطلب، ويحجب حقول كلمات المرور من النص.", 13f, GlassUi.MUTED))
        column.addView(GlassUi.button(this, "تفعيل إمكانية الوصول") {
            AlertDialog.Builder(this).setTitle("التحكم خطوة بخطوة")
                .setMessage("فعّل «مساعدك — التحكم بموافقتك» لعرض الفقاعة. قراءة الشاشة تحتاج الإذن أعلاه، وكل فعل تنفيذي له موافقة مستقلة.")
                .setPositiveButton("افتح الإعدادات") { _, _ -> startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) }
                .setNegativeButton("إلغاء", null).show()
        })
        column.addView(GlassUi.button(this, "تشغيل الفقاعة", true) {
            val service = JarvisAccessibilityService.instance
            if (service == null) state.text = "فعّل إمكانية الوصول الأول، ثم ارجع هنا."
            else { service.arabic.enable(); moveTaskToBack(true) }
        })
        column.addView(GlassUi.button(this, "إيقاف المساعد وإخفاء الفقاعة", stop = true) {
            ChatConversation.get(this).cancel()
            JarvisAccessibilityService.instance?.arabic?.disable()
            state.text = "تم إيقاف المساعد"
        })
        column.addView(Switch(this).apply {
            text = "السماح بطلب صورة للشاشة"; textSize = 15f; setTextColor(GlassUi.INK)
            isChecked = settings.allowScreenshots
            setOnCheckedChangeListener { _, checked -> settings.allowScreenshots = checked }
        })
        column.addView(GlassUi.text(this, "كل صورة تحتاج موافقة إضافية ونموذجًا يدعم الرؤية. الشاشات المحمية قد ترفض القراءة أو التصوير.", 13f, GlassUi.MUTED))
        column.addView(GlassUi.text(this, "على Honor: لو النظام بيقفل الفقاعة، راجع تشغيل التطبيقات والبطارية يدويًا. الصوت العربي يعتمد على الخدمات واللغات المثبتة في الهاتف.", 13f, GlassUi.MUTED))
        val root = ScrollView(this).apply { setBackgroundColor(0xFFEFF8FA.toInt()); layoutDirection = View.LAYOUT_DIRECTION_RTL; addView(column) }
        root.padForSystemBars()
        setContentView(root)
        refresh()
    }

    override fun onResume() { super.onResume(); if (::state.isInitialized) refresh() }
    override fun onDestroy() { testSession?.cancel(); worker.shutdownNow(); super.onDestroy() }

    private fun refresh() {
        profileLabel.text = "النموذج الأساسي: " + (Settings(this).primary()?.name ?: "لم تضف نموذجًا بعد")
        serviceLabel.text = if (JarvisAccessibilityService.instance != null) "إمكانية الوصول متصلة ✓" else "إمكانية الوصول غير مفعّلة"
    }

    private fun test(tools: Boolean) {
        if (testing) return
        val profile = Settings(this).primary() ?: run { state.text = "أضف نموذجًا الأول."; return }
        testing = true
        state.text = "بختبر " + (if (tools) "الأدوات" else "الدردشة") + " مع " + profile.name + "…"
        worker.execute {
            val result = runCatching {
                val specs = if (tools) listOf(ToolSpec("ping", "Confirm this connection test.",
                    JSONObject("""{"type":"object","properties":{"ok":{"type":"boolean"}},"required":["ok"]}"""))) else emptyList()
                val session = Llm.newSession(profile.toConfig(),
                    if (tools) "Call the ping tool once with ok=true." else "Reply with OK only.", specs)
                testSession = session
                try {
                    val turn = session.send("Test connection.", emptyList())
                    if (tools) check(turn.toolCalls.any { it.name == "ping" }) { "No tool support" }
                    else check(turn.text.isNotBlank() && turn.toolCalls.isEmpty()) { "Empty reply" }
                } finally { session.cancel(); testSession = null }
            }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                testing = false
                state.text = if (result.isSuccess) {
                    if (tools) "اختبار استدعاء الأدوات نجح ✓" else "اختبار الدردشة نجح ✓"
                } else if (result.exceptionOrNull()?.message == "No tool support")
                    "المزود لم يرجع استدعاء أداة؛ راجع دعم Tool Calling للنموذج."
                else ChatConversation.connectionError(result.exceptionOrNull())
            }
        }
    }

    private fun editModel(current: ModelProfile?) {
        val column = GlassUi.column(this)
        val provider = Spinner(this)
        provider.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, PROVIDERS.map { it.label })
        val name = GlassUi.field(this, "اسم للاتصال").apply { setText(current?.name ?: "نموذجي") }
        val endpoint = GlassUi.field(this, "رابط API").apply {
            textDirection = View.TEXT_DIRECTION_LTR
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        val model = GlassUi.field(this, "اسم النموذج").apply { textDirection = View.TEXT_DIRECTION_LTR; setSingleLine() }
        val key = GlassUi.field(this, "مفتاح API").apply {
            textDirection = View.TEXT_DIRECTION_LTR
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        var selected = current?.providerId ?: "openrouter"
        val preset = PROVIDERS.firstOrNull { it.id == selected } ?: PROVIDERS.last()
        endpoint.setText(current?.baseUrl ?: preset.defaultBaseUrl)
        model.setText(current?.model ?: preset.defaultModel)
        key.setText(current?.apiKey.orEmpty())
        provider.setSelection(PROVIDERS.indexOf(preset))
        provider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val next = PROVIDERS[position]
                if (next.id == selected) return
                selected = next.id
                endpoint.setText(next.defaultBaseUrl); model.setText(next.defaultModel)
                key.setText("") // A provider change must never silently reuse another provider's key.
            }
        }
        column.addView(GlassUi.text(this, "المزود", 14f)); column.addView(provider)
        column.addView(name)
        column.addView(GlassUi.text(this, "الرابط", 14f)); column.addView(endpoint)
        column.addView(GlassUi.text(this, "النموذج", 14f)); column.addView(model)
        column.addView(GlassUi.text(this, "المفتاح — محفوظ في مساحة التطبيق الخاصة", 13f)); column.addView(key)
        column.addView(GlassUi.text(this, "مفيش مفتاح مدمج. التسعير حسب مزودك. لا تشارك المفتاح داخل الدردشة.", 13f, GlassUi.MUTED))
        val error = GlassUi.text(this, "", 14f, GlassUi.RED); column.addView(error)
        val scroll = ScrollView(this).apply { addView(column) }
        val dialog = AlertDialog.Builder(this).setTitle(if (current == null) "إضافة نموذج AI" else "تعديل نموذج AI")
            .setView(scroll).setPositiveButton("حفظ", null).setNegativeButton("إلغاء", null).create()
        dialog.setOnShowListener {
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val p = PROVIDERS[provider.selectedItemPosition]
                val url = endpoint.text.toString().trim().trimEnd('/').toHttpUrlOrNull()
                if (url == null || (!url.isHttps && !isLocalHost(url.host)) ||
                    url.username.isNotBlank() || url.password.isNotBlank() || url.query != null || url.fragment != null) {
                    error.text = "استخدم رابط HTTPS صحيح، أو HTTP لخادم محلي فقط."; return@setOnClickListener
                }
                if (model.text.isBlank() || (p.needsKey && key.text.isBlank())) {
                    error.text = "أدخل اسم النموذج ومفتاح المزود المطلوب."; return@setOnClickListener
                }
                val profile = ModelProfile(id = current?.id ?: java.util.UUID.randomUUID().toString(),
                    name = name.text.toString().trim().ifEmpty { p.label }, providerId = p.id,
                    baseUrl = url.toString().trimEnd('/'), apiKey = key.text.toString().trim(),
                    model = model.text.toString().trim(), effort = current?.effort ?: "medium")
                Settings(this).apply { saveProfile(profile); primaryId = profile.id }
                state.text = "تم حفظ النموذج. اختبر الدردشة، واختبر الأدوات لو هتستخدم الشاشة."
                ChatConversation.get(this).refreshModel()
                refresh(); dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun isLocalHost(host: String): Boolean {
        if (host == "localhost" || host == "::1" || host.endsWith(".local")) return true
        val p = host.split('.').map { it.toIntOrNull() ?: return false }
        if (p.size != 4 || p.any { it !in 0..255 }) return false
        return p[0] == 10 || p[0] == 127 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31)
    }
}
