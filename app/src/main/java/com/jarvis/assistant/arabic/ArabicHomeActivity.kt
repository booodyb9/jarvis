package com.jarvis.assistant.arabic

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.ui.padForSystemBars

/** The launcher is a real conversation; phone-control setup is a separate destination. */
class ArabicHomeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = GlassUi.column(this).apply { setBackgroundColor(0xFFEFF8FA.toInt()) }
        root.padForSystemBars()
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(RobotView(this), LinearLayout.LayoutParams(GlassUi.dp(this, 44), GlassUi.dp(this, 44)))
        header.addView(GlassUi.text(this, "مساعدك", 25f), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = GlassUi.dp(this@ArabicHomeActivity, 10) })
        header.addView(GlassUi.button(this, "الإعدادات") { startActivity(Intent(this, ArabicSettingsActivity::class.java)) },
            LinearLayout.LayoutParams(GlassUi.dp(this, 96), GlassUi.dp(this, 52)))
        root.addView(header)
        val newChat = GlassUi.button(this, "محادثة جديدة") {
            AlertDialog.Builder(this).setTitle("محادثة جديدة؟").setMessage("هنوقف الرد الجاري ونمسح سجل المحادثة من الجهاز.")
                .setPositiveButton("ابدأ جديد") { _, _ -> ChatConversation.get(this).clear() }.setNegativeButton("إلغاء", null).show()
        }
        root.addView(newChat)
        val chat = ConversationView(this).apply {
            onTalk = { startActivity(Intent(this@ArabicHomeActivity, ArabicVoiceActivity::class.java)
                .putExtra(ArabicVoiceActivity.EXTRA_DESTINATION, ArabicVoiceActivity.DEST_HOME)) }
            onScreen = {
                val service = JarvisAccessibilityService.instance
                if (service == null || !getSharedPreferences("arabic_consent", 0).getBoolean("screen_text", false)) {
                    Toast.makeText(this@ArabicHomeActivity, "جهّز إذن الشاشة وإمكانية الوصول من الإعدادات الأول.", Toast.LENGTH_LONG).show()
                    startActivity(Intent(this@ArabicHomeActivity, ArabicSettingsActivity::class.java))
                } else {
                    service.arabic.openScreen()
                    moveTaskToBack(true)
                }
            }
        }
        root.addView(chat, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }
}
