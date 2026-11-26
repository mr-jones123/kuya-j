package ph.bettergov.kuyaj.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import org.jetbrains.compose.resources.DrawableResource
import ph.bettergov.kuyaj.resources.Res
import ph.bettergov.kuyaj.resources.ic_calendar
import ph.bettergov.kuyaj.resources.ic_clipboard
import ph.bettergov.kuyaj.resources.ic_file_text
import ph.bettergov.kuyaj.resources.ic_landmark
import ph.bettergov.kuyaj.resources.ic_phone
import ph.bettergov.kuyaj.resources.ic_receipt

/** How each record category looks on an answer card: a label, an icon and one accent colour. */
data class CategoryStyle(val label: String, val icon: DrawableResource, val accent: Color) {
    val soft: Color get() = accent.copy(alpha = 0.13f)
}

@Composable
fun categoryStyle(category: String?): CategoryStyle {
    val c = LocalKuyaColors.current
    fun pick(light: Long, dark: Long) = Color(if (c.dark) dark else light)
    return when (category) {
        "fees" -> CategoryStyle("Fee", Res.drawable.ic_receipt, pick(0xFF0B7F55, 0xFF41C993))
        "requirements" -> CategoryStyle("Requirements", Res.drawable.ic_clipboard, pick(0xFF5B3FCF, 0xFFA99BFF))
        "hotlines" -> CategoryStyle("Hotline", Res.drawable.ic_phone, pick(0xFFC8331F, 0xFFFF8373))
        "holidays" -> CategoryStyle("Holiday", Res.drawable.ic_calendar, pick(0xFFA85F00, 0xFFF4B961))
        "officials" -> CategoryStyle("Office holder", Res.drawable.ic_landmark, pick(0xFF2F43B5, 0xFF95A3FF))
        "services" -> CategoryStyle("How to", Res.drawable.ic_file_text, c.primaryText)
        "history" -> CategoryStyle("History", Res.drawable.ic_landmark, pick(0xFF8A4B1F, 0xFFE0A47A))
        else -> CategoryStyle("Record", Res.drawable.ic_file_text, c.primaryText)
    }
}
