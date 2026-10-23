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
import com.jarvis.assistant.ModelProfile
import com.jarvis.assistant.Settings
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.agent.Llm
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.Executors

class ArabicHomeActivity : AppCompatActivity() {
    private lateinit var state: TextView
    private val worker = Executors.newSingleThreadExecutor()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A key can be entered here; never allow this configuration screen in screenshots.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val settings = Settings(this)
        val prefs = getSharedPreferences("arabic_consent", 0)
        val column = GlassUi.column(this)
        column.addView(RobotView(this), LinearLayout.LayoutParams(GlassUi.dp(this, 82), GlassUi.dp(this, 82)))
        column.addView(GlassUi.text(this, "مساعدك العربي", 30f))
        column.addView(GlassUi.text(this, "يفهمك، يرشدك، وينفّذ بموافقتك", 18f))
        column.addView(GlassUi.text(this, "فقاعة خفيفة على الشاشة، وكلامك تراجعه قبل الإرسال. كل ضغطة بموافقة منفصلة، والإيقاف متاح في أي وقت.", 16f, GlassUi.MUTED))
        state = GlassUi.text(this, "")
        column.addView(state)
        val share = CheckBox(this).apply {
            text = "أسمح بإرسال نص الشاشة أثناء طلباتي لنموذج AI الذي أختاره"; textSize = 16f; setTextColor(GlassUi.INK)
            isChecked = prefs.getBoolean("screen_text", false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("screen_text", checked).apply()
                if (!checked) JarvisAccessibilityService.instance?.arabic?.disable()
            }
        }
        column.addView(share)
        column.addView(GlassUi.text(this, "القراءة تبدأ عند إرسال الطلب فقط. حقول كلمات المرور لا تُرسل كنص. تفعيل إمكانية الوصول يسمح بعرض الفقاعة وقراءة العناصر وتنفيذ الفعل الموافق عليه.", 14f, GlassUi.MUTED))
        column.addView(GlassUi.button(this, "١  تفعيل إمكانية الوصول") {
            AlertDialog.Builder(this).setTitle("التحكم خطوة بخطوة")
                .setMessage("فعّل «مساعدك — التحكم بموافقتك» في إعدادات إمكانية الوصول. التطبيق يقرأ عناصر الشاشة عند طلبك، وقد يرسلها للنموذج المختار، ويعرض كل فعل للموافقة. يمكنك تعطيله من نفس الإعدادات.")
                .setPositiveButton("افتح الإعدادات") { _, _ -> startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) }
                .setNegativeButton("إلغاء", null).show()
        })
        column.addView(GlassUi.button(this, "٢  إعداد نموذج AI") { editModel() })
        column.addView(GlassUi.button(this, "اختبار اتصال النموذج") {
            val profile = settings.primary()
            if (profile == null) { state.text = "أضف نموذج الأول"; return@button }
            state.text = "بختبر الاتصال؛ الاختبار يرسل كلمة قصيرة فقط…"
            worker.execute {
                val result = runCatching { Llm.test(profile.toConfig()) }
                runOnUiThread { if (!isDestroyed) state.text = if (result.isSuccess) "الاتصال نجح ✓" else "الاتصال لم ينجح؛ راجع الرابط والمفتاح واسم النموذج" }
            }
        })
        column.addView(GlassUi.button(this, "تشغيل الفقاعة", true) {
            val service = JarvisAccessibilityService.instance
            when {
                !share.isChecked -> state.text = "راجع إذن إرسال نص الشاشة وفعّله لو موافق"
                service == null -> state.text = "فعّل إمكانية الوصول الأول، ثم ارجع هنا"
                else -> { service.arabic.enable(); moveTaskToBack(true) }
            }
        })
        column.addView(GlassUi.button(this, "إيقاف المساعد وإخفاء الفقاعة", stop = true) {
            JarvisAccessibilityService.instance?.arabic?.disable(); state.text = "تم إيقاف المساعد"
        })
        column.addView(Switch(this).apply {
            text = "السماح بطلب صورة للشاشة"; textSize = 16f; setTextColor(GlassUi.INK); isChecked = settings.allowScreenshots
            setOnCheckedChangeListener { _, checked -> settings.allowScreenshots = checked }
        })
        column.addView(GlassUi.text(this, "الصورة تحتاج موافقة إضافية عند كل التقاط، وقد تحتوي بيانات ظاهرة. الشاشات المحمية قد ترفض القراءة أو التصوير. الصوت العربي يعتمد على خدمات الكلام والصوت المثبتة في الهاتف.", 14f, GlassUi.MUTED))
        column.addView(GlassUi.text(this, "على Honor: لو النظام بيقفل الفقاعة، راجع تشغيل التطبيقات والبطارية يدويًا. التطبيق لا يغيّر إعدادات البطارية تلقائيًا.", 14f, GlassUi.MUTED))
        setContentView(ScrollView(this).apply { setBackgroundColor(0xFFEFF8FA.toInt()); layoutDirection = View.LAYOUT_DIRECTION_RTL; addView(column) })
    }

    override fun onResume() { super.onResume(); if (::state.isInitialized) state.text = if (JarvisAccessibilityService.instance != null) "إمكانية الوصول متصلة ✓" else "إمكانية الوصول غير مفعّلة" }
    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }

    private fun editModel() {
        val settings = Settings(this)
        val current = settings.primary()?.takeIf { it.preset.kind == com.jarvis.assistant.ApiKind.OPENAI_COMPATIBLE }
        val column = GlassUi.column(this)
        column.addView(GlassUi.text(this, "مزود يدعم Chat Completions وTool Calling. لا يوجد مفتاح مدمج، والتكلفة حسب مزودك.", 14f))
        val endpoint = GlassUi.field(this, "رابط API ينتهي بـ /v1").apply {
            textDirection = View.TEXT_DIRECTION_LTR; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(current?.baseUrl ?: "https://openrouter.ai/api/v1")
        }
        val model = GlassUi.field(this, "اسم النموذج من حساب المزود").apply { textDirection = View.TEXT_DIRECTION_LTR; setText(current?.model.orEmpty()) }
        val key = GlassUi.field(this, "مفتاح API الخاص بك").apply {
            textDirection = View.TEXT_DIRECTION_LTR; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; setText(current?.apiKey.orEmpty())
        }
        column.addView(endpoint); column.addView(model); column.addView(key)
        val error = GlassUi.text(this, "", 14f, GlassUi.RED); column.addView(error)
        val dialog = AlertDialog.Builder(this).setTitle("ربط نموذج AI").setView(column)
            .setPositiveButton("حفظ", null).setNegativeButton("إلغاء", null).create()
        dialog.setOnShowListener {
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = endpoint.text.toString().trim().trimEnd('/').toHttpUrlOrNull()
                if (url == null || !url.isHttps || url.username.isNotBlank() || url.password.isNotBlank() || url.query != null || url.fragment != null || model.text.isBlank()) {
                    error.text = "أدخل رابط HTTPS صحيح واسم نموذج يدعم الأدوات"; return@setOnClickListener
                }
                val profile = ModelProfile(id = current?.id ?: java.util.UUID.randomUUID().toString(), name = "نموذجي", providerId = "custom", baseUrl = url.toString().trimEnd('/'), apiKey = key.text.toString().trim(), model = model.text.toString().trim())
                settings.saveProfile(profile)
                settings.primaryId = profile.id
                state.text = "تم حفظ النموذج. جرّب اختبار الاتصال."; dialog.dismiss()
            }
        }
        dialog.show()
    }
}
