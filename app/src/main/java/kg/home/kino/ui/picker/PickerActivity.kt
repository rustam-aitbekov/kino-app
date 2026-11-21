package kg.home.kino.ui.picker

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentActivity
import kg.home.kino.R
import kg.home.kino.ui.browse.MainActivity

/** "Что смотрим?" - pick OC.KG (films) or TS.KG (series). */
class PickerActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_picker)

        val oc = findViewById<View>(R.id.card_oc)
        val ts = findViewById<View>(R.id.card_ts)

        for (card in listOf(oc, ts)) {
            card.setOnFocusChangeListener { v, hasFocus ->
                val scale = if (hasFocus) 1.08f else 1f
                v.animate().scaleX(scale).scaleY(scale).setDuration(150).start()
            }
        }

        oc.setOnClickListener { startActivity(MainActivity.intent(this, series = false)) }
        ts.setOnClickListener { startActivity(MainActivity.intent(this, series = true)) }

        oc.requestFocus()
    }
}
