package ph.bettergov.kuyaj.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composeunstyled.DragIndication
import com.composeunstyled.ModalBottomSheetState
import com.composeunstyled.Scrim
import com.composeunstyled.Sheet
import com.composeunstyled.SheetDetent
import com.composeunstyled.Text
import com.composeunstyled.UnstyledModalBottomSheet
import ph.bettergov.kuyaj.app.Freshness
import ph.bettergov.kuyaj.app.ModelState
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import ph.bettergov.kuyaj.resources.Res
import ph.bettergov.kuyaj.resources.*

@Composable
fun SettingsSheet(vm: ChatViewModel, state: ModalBottomSheetState) {
    val c = LocalKuyaColors.current
    val close = { state.targetDetent = SheetDetent.Hidden }
    UnstyledModalBottomSheet(
        state = state,
        onDismiss = close,
        overlay = { Scrim(scrimColor = c.ink.copy(alpha = 0.35f), enter = fadeIn(), exit = fadeOut()) },
    ) {
        Sheet(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(c.background),
        ) {
            Column(Modifier.navigationBarsPadding()) {
                DragIndication(
                    Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp, bottom = 6.dp)
                        .size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(50)).background(c.border),
                )
                SettingsContent(vm, onClose = close)
            }
        }
    }
}

@Composable
private fun SettingsContent(vm: ChatViewModel, onClose: () -> Unit) {
    val c = LocalKuyaColors.current
    val services = vm.services
    val kb by services.kbStatus.collectAsStateWithLifecycle()
    val model by services.modelState.collectAsStateWithLifecycle()
    val engineError by services.engineError.collectAsStateWithLifecycle()
    val info = services.modelInfo

    LaunchedEffect(model) { services.prepareModel() }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Settings", style = Type.display, color = c.ink, modifier = Modifier.weight(1f))
            IconBtn(Res.drawable.ic_x, "Close", onClose, tint = c.muted, background = c.surfaceSunken, size = 34.dp, iconSize = 16.dp)
        }

        SectionLabel("Records")
        Group {
            InfoRow("Source", if (kb.fromBundle) "Downloaded update" else "Built into the app")
            InfoRow("Records", kb.recordCount.toString())
            kb.version?.let { InfoRow("Version", it) }
            InfoRow("Last update", kb.lastSync ?: "Never", last = !kb.syncConfigured)
            if (kb.syncConfigured) {
                InfoRow("Status", when (kb.freshness) {
                    Freshness.FRESH -> "Up to date"
                    Freshness.STALE -> "Over a day old"
                    Freshness.VERY_STALE -> "Over a week old"
                    Freshness.NEVER -> "Not updated yet"
                }, last = true)
            }
        }
        if (kb.syncConfigured) {
            Hint("Updates download every 6 hours on Wi-Fi. Each one must carry the Kuya J publisher's signature before it replaces anything.")
            KButton("Update on next Wi-Fi", { services.syncNow() }, kind = ButtonKind.Secondary)
        } else {
            Hint("This build has no update server, so Kuya J uses the records built into the app.")
        }

        SectionLabel("AI model")
        Group {
            InfoRow("Model", info.label)
            InfoRow("Size", gigabytes(info.sizeBytes), last = info.backend == null)
            info.backend?.let { InfoRow("Running on", it, last = true) }
        }
        when (val s = model) {
            ModelState.Missing -> {
                Hint("Without the model, answers are the official record as written. The model adds plain-language answers and reads photos.")
                KButton("Download on Wi-Fi", { services.startModelDownload() })
            }
            is ModelState.Downloading -> {
                ProgressBar(s.fraction)
                Hint("Downloading ${(s.fraction * 100).toInt()}%. It keeps going in the background on Wi-Fi.")
            }
            ModelState.Verifying -> Hint("Checking the file's fingerprint…")
            ModelState.Ready -> {
                Hint(if (info.running) "Ready. Answers are written on this phone." else engineError?.let { "Could not start: $it" } ?: "Starting…")
                KButton("Delete model", { services.deleteModel() }, kind = ButtonKind.Secondary)
            }
            is ModelState.Failed -> {
                Hint(s.message)
                KButton("Try again", { services.startModelDownload() })
            }
        }

        SectionLabel("About")
        Group {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Image(painterResource(if (isSystemInDarkTheme()) Res.drawable.bettergov_horizontal_white else Res.drawable.bettergov_horizontal), contentDescription = "BetterGov.ph", modifier = Modifier.width(140.dp).height(35.dp))
                Text(
                    "Kuya J is a BetterGov.ph civic assistant. This version answers from saved, dated public records. Check the agency's official page before you act on a fee, hotline or office holder.",
                    style = Type.small, color = c.muted,
                )
                Text(
                    "Records: BetterGov.ph guides, holidays, directory and hotlines (CC BY-NC-ND 4.0, used with permission), and Airgap by Xavier Puspus (MIT). Facts come from the government pages linked in each answer.",
                    style = Type.caption, color = c.faint,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), style = Type.label, color = LocalKuyaColors.current.faint, modifier = Modifier.padding(start = 4.dp, top = 14.dp))
}

@Composable
private fun Group(content: @Composable () -> Unit) {
    val c = LocalKuyaColors.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface)) { content() }
}

@Composable
private fun InfoRow(label: String, value: String, last: Boolean = false) {
    val c = LocalKuyaColors.current
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
            Text(label, style = Type.small, color = c.muted, modifier = Modifier.width(110.dp))
            Text(value, style = Type.smallMedium, color = c.ink)
        }
        if (!last) Box(Modifier.padding(start = 14.dp).fillMaxWidth().height(1.dp).background(c.border))
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = Type.small, color = LocalKuyaColors.current.muted, modifier = Modifier.padding(horizontal = 4.dp))
}

@Composable
private fun ProgressBar(fraction: Float) {
    val c = LocalKuyaColors.current
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(c.surfaceSunken)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(50)).background(c.primary))
    }
}

/** 2_588_147_712 -> "2.6 GB" */
fun gigabytes(bytes: Long): String = if (bytes < 0) "Unknown" else "${kotlin.math.round(bytes / 1e8) / 10.0} GB"
