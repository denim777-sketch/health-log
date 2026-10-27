package com.denim.healthlog

import android.os.Bundle
import android.view.WindowInsets
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity

/** ヘルスコネクトの許可画面から開かれる「データの使い道」の説明 */
class PrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val text = TextView(this).apply {
            textSize = 16f
            setPadding(pad, pad, pad, pad)
            text = "このアプリについて\n\n" +
                "ヘルスコネクトから睡眠と運動（距離を含む）の記録を読み取り、この端末の画面に一覧表示するだけの個人用アプリです。\n\n" +
                "読み取ったデータは保存・送信せず、外部に共有することもありません。\n\n" +
                "許可は、ヘルスコネクトの設定からいつでも取り消せます。"
        }
        val scroll = ScrollView(this).apply { addView(text) }
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        setContentView(scroll)
    }
}
