package ph.bettergov.kuyaj.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composeunstyled.Text
import com.composeunstyled.TextInput
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.UnstyledTextField
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import ph.bettergov.kuyaj.app.ModelState
import ph.bettergov.kuyaj.chat.Reply
import ph.bettergov.kuyaj.kb.KbRecord
import ph.bettergov.kuyaj.resources.Res
import ph.bettergov.kuyaj.resources.ic_arrow_up
import ph.bettergov.kuyaj.resources.ic_calendar
import ph.bettergov.kuyaj.resources.ic_external
import ph.bettergov.kuyaj.resources.ic_file_text
import ph.bettergov.kuyaj.resources.ic_id_card
import ph.bettergov.kuyaj.resources.ic_image
import ph.bettergov.kuyaj.resources.ic_landmark
import ph.bettergov.kuyaj.resources.ic_new_chat
import ph.bettergov.kuyaj.resources.ic_phone
import ph.bettergov.kuyaj.resources.ic_plane
import ph.bettergov.kuyaj.resources.ic_receipt
import ph.bettergov.kuyaj.resources.ic_shield_check
import ph.bettergov.kuyaj.resources.ic_sliders
import ph.bettergov.kuyaj.resources.ic_x
import ph.bettergov.kuyaj.resources.kuya_j_mascot

/** A topic on the home screen. [category] picks its colour; [question] is what tapping it asks. */
private data class Topic(val icon: DrawableResource, val title: String, val caption: String, val category: String, val question: String)

// Only topics the built-in records can answer.
private val TOPICS = listOf(
    Topic(Res.drawable.ic_receipt, "NBI clearance", "Fee, steps", "fees", "Magkano ang NBI clearance?"),
    Topic(Res.drawable.ic_plane, "Passport", "Fee, appointment", "services", "Paano kumuha ng passport?"),
    Topic(Res.drawable.ic_id_card, "SSS number", "Register online", "requirements", "How do I get an SS number?"),
    Topic(Res.drawable.ic_calendar, "Holidays", "This year's dates", "holidays", "What are the regular holidays this year?"),
    Topic(Res.drawable.ic_phone, "Hotlines", "911, 8888, SSS", "hotlines", "Emergency hotline"),
    Topic(Res.drawable.ic_landmark, "Officials", "Who holds office", "officials", "Sino ang Pangulo ng Pilipinas?"),
)

// One example per language family, so people see they can ask in their own language.
private val EXAMPLES = listOf(
    "Ano ang hotline ng SSS?",
    "Kanus-a ang Eid'l Adha?",
    "Sino ang Senate President?",
    "Passport fee?",
)

@Composable
fun ChatScreen(vm: ChatViewModel, onOpenSettings: () -> Unit, onPickPhoto: () -> Unit) {
    val c = LocalKuyaColors.current
    val messages by vm.messages.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val attachment by vm.attachment.collectAsStateWithLifecycle()
    val kb by vm.services.kbStatus.collectAsStateWithLifecycle()
    val modelState by vm.services.modelState.collectAsStateWithLifecycle()
    val modelReady = modelState is ModelState.Ready
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(Modifier.fillMaxSize().background(c.background).navigationBarsPadding().imePadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty()) {
                Welcome(recordCount = kb.recordCount, modelReady = modelReady, onAsk = vm::send, onOpenSettings = onOpenSettings)
            } else {
                Column(Modifier.fillMaxSize()) {
                    TopBar(modelReady, onNewChat = vm::newChat, onOpenSettings = onOpenSettings)
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        items(messages, key = { it.id }) { m -> if (m.fromUser) UserMessage(m) else BotMessage(m) }
                    }
                }
            }
        }

        Composer(
            busy = busy,
            attachment = attachment?.preview,
            onClearAttachment = vm::clearAttachment,
            onPickPhoto = onPickPhoto,
            onSend = vm::send,
        )
    }
}

/** "Offline · AI on this phone": Kuya J never needs the internet to answer. */
private fun statusText(modelReady: Boolean) = if (modelReady) "Offline · AI on this phone" else "Offline · records only"

@Composable
private fun TopBar(modelReady: Boolean, onNewChat: () -> Unit, onOpenSettings: () -> Unit) {
    val c = LocalKuyaColors.current
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painterResource(Res.drawable.kuya_j_mascot), contentDescription = null,
            modifier = Modifier.size(36.dp).clip(CircleShape).background(c.surface),
        )
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text("Kuya J", style = Type.title, color = c.ink)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(if (modelReady) c.success else c.faint))
                Text(statusText(modelReady), style = Type.caption, color = c.muted)
            }
        }
        IconBtn(Res.drawable.ic_new_chat, "New chat", onNewChat, tint = c.ink)
        IconBtn(Res.drawable.ic_sliders, "Settings", onOpenSettings, tint = c.ink)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Welcome(recordCount: Int, modelReady: Boolean, onAsk: (String) -> Unit, onOpenSettings: () -> Unit) {
    val c = LocalKuyaColors.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // Hero: BetterGov blue, the mascot, and what Kuya J promises in one line.
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp))
                .background(Brush.verticalGradient(listOf(c.heroStart, c.heroEnd)))
                .statusBarsPadding()
                .padding(start = 20.dp, end = 8.dp, bottom = 24.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(if (modelReady) c.sun else Color.White.copy(alpha = 0.5f)))
                    Text(statusText(modelReady), style = Type.caption, color = Color.White.copy(alpha = 0.85f))
                }
                IconBtn(Res.drawable.ic_sliders, "Settings", onOpenSettings, tint = Color.White)
            }
            Row(Modifier.padding(top = 8.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Kumusta! Ako si Kuya J.", style = Type.hero, color = Color.White)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Ask about government services in your own language. No internet needed, and every answer shows where it came from.",
                        style = Type.body, color = Color.White.copy(alpha = 0.86f),
                    )
                }
                Image(
                    painterResource(Res.drawable.kuya_j_mascot), contentDescription = null,
                    modifier = Modifier.padding(start = 12.dp).size(84.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f)),
                )
            }
        }

        Column(Modifier.padding(horizontal = 16.dp, vertical = 20.dp)) {
            SectionTitle("Ano'ng kailangan mo?", "What do you need?")
            Spacer(Modifier.height(12.dp))
            TOPICS.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { t -> TopicTile(t, Modifier.weight(1f)) { onAsk(t.question) } }
                }
                Spacer(Modifier.height(10.dp))
            }

            Spacer(Modifier.height(14.dp))
            SectionTitle("Subukang itanong", "Try asking")
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EXAMPLES.forEach { q -> ExampleChip(q) { onAsk(q) } }
            }

            Spacer(Modifier.height(22.dp))
            Pill("$recordCount saved records · works without internet", icon = painterResource(Res.drawable.ic_file_text), color = c.muted, background = c.surfaceSunken)
        }
    }
}

/** A Filipino heading with its English line under it. */
@Composable
private fun SectionTitle(filipino: String, english: String) {
    val c = LocalKuyaColors.current
    Column {
        Text(filipino, style = Type.title, color = c.ink)
        Text(english, style = Type.caption, color = c.muted)
    }
}

@Composable
private fun TopicTile(t: Topic, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalKuyaColors.current
    val style = categoryStyle(t.category)
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .clip(shape)
            .background(c.surface)
            .border(1.dp, c.border, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(style.soft), contentAlignment = Alignment.Center) {
            UnstyledIcon(painterResource(t.icon), contentDescription = null, tint = style.accent, modifier = Modifier.size(19.dp))
        }
        Column {
            Text(t.title, style = Type.smallMedium, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(t.caption, style = Type.caption, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ExampleChip(text: String, onClick: () -> Unit) {
    val c = LocalKuyaColors.current
    Text(
        text, style = Type.small, color = c.primaryText,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(c.primarySoft)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

@Composable
private fun UserMessage(m: ChatMessage) {
    val c = LocalKuyaColors.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        m.photo?.let {
            Image(
                it, contentDescription = "Your photo",
                modifier = Modifier.size(180.dp).clip(RoundedCornerShape(18.dp)), contentScale = ContentScale.Crop,
            )
        }
        if (m.text.isNotEmpty()) {
            Text(
                m.text, style = Type.body, color = c.onPrimary,
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                    .background(c.primary)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun BotMessage(m: ChatMessage) {
    val c = LocalKuyaColors.current
    val reply = m.reply
    val record = reply?.sources?.firstOrNull()?.takeIf { reply.kind == Reply.Kind.RECORD || reply.kind == Reply.Kind.MODEL }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Image(painterResource(Res.drawable.kuya_j_mascot), contentDescription = null, modifier = Modifier.size(24.dp).clip(CircleShape))
            Text("Kuya J", style = Type.smallMedium, color = c.ink)
        }
        reply?.photoReading?.let {
            Text(
                "From your photo: ${it.take(220)}${if (it.length > 220) "…" else ""}",
                style = Type.small, color = c.muted,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(c.surfaceSunken).padding(10.dp),
            )
        }
        when {
            m.text.isEmpty() && m.streaming -> Box(Modifier.padding(vertical = 6.dp)) { TypingDots() }
            record != null && !m.streaming -> AnswerCard(m.text, reply, record)
            reply?.kind == Reply.Kind.REFUSAL -> Text(
                m.text, style = Type.body, color = c.ink,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surfaceSunken).padding(14.dp),
            )
            else -> Text(m.text, style = Type.body, color = c.ink)
        }
    }
}

/**
 * An answer from a record: what kind of fact it is, the one value that matters (fee, number, date) large,
 * the answer text, and the source with its "checked" date at the foot. The large value always comes from the
 * record itself (see [keyFact]), never from the model's wording.
 */
@Composable
private fun AnswerCard(text: String, reply: Reply, record: KbRecord) {
    val c = LocalKuyaColors.current
    val style = categoryStyle(record.category)
    val fact = keyFact(record)
    val uriHandler = LocalUriHandler.current
    val shape = RoundedCornerShape(20.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.border, shape)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(30.dp).clip(CircleShape).background(style.soft), contentAlignment = Alignment.Center) {
                    UnstyledIcon(painterResource(style.icon), contentDescription = null, tint = style.accent, modifier = Modifier.size(16.dp))
                }
                Text(
                    listOfNotNull(style.label, record.metadata["agency"] as? String).joinToString(" · "),
                    style = Type.smallMedium, color = style.accent, modifier = Modifier.padding(start = 8.dp).weight(1f),
                )
                if (reply.kind == Reply.Kind.MODEL) {
                    Pill("Checked against record", icon = painterResource(Res.drawable.ic_shield_check), color = c.success, background = c.success.copy(alpha = 0.12f))
                } else {
                    Pill("Official record", icon = painterResource(Res.drawable.ic_file_text))
                }
            }
            if (fact != null) {
                Column {
                    Text(fact.value, style = Type.figure, color = c.ink)
                    Text(record.title, style = Type.caption, color = c.muted)
                }
            }
            Text(withoutSourceLine(text), style = Type.body, color = c.ink)
            reply.note?.let {
                Text(
                    it, style = Type.small, color = c.warning,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.warningSoft).padding(10.dp),
                )
            }
            if (fact?.kind == KeyFact.Kind.PHONE) {
                KButton("Call ${fact.value}", { uriHandler.openUri("tel:${fact.dialable}") }, modifier = Modifier.fillMaxWidth())
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
        SourceRow(record.publisher ?: record.title, record.source, record.asOf)
    }
}

private val TRAILING_SOURCE = Regex("""\s*Source:\s*https?://\S+?(\s*\(checked [^)]*\))?\.?\s*$""", RegexOption.IGNORE_CASE)

/** Records end with "Source: <url> (checked <date>)." The card shows that, so the text drops it. */
fun withoutSourceLine(text: String): String = text.replace(TRAILING_SOURCE, "").ifBlank { text }

@Composable
private fun SourceRow(publisher: String, source: String?, asOf: String?) {
    val c = LocalKuyaColors.current
    val uriHandler = LocalUriHandler.current
    val url = source?.takeIf { it.startsWith("https://") }
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (url != null) Modifier.clickable(role = Role.Button) { uriHandler.openUri(url) } else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Source · $publisher", style = Type.smallMedium, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(url?.let { host(it) }, asOf?.let { "checked $it" }).joinToString(" · "),
                style = Type.caption, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (url != null) UnstyledIcon(painterResource(Res.drawable.ic_external), contentDescription = "Open source", tint = c.faint, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun Composer(
    busy: Boolean,
    attachment: ImageBitmap?,
    onClearAttachment: () -> Unit,
    onPickPhoto: () -> Unit,
    onSend: (String) -> Unit,
) {
    val c = LocalKuyaColors.current
    val state = rememberTextFieldState()
    val canSend = !busy && (state.text.isNotBlank() || attachment != null)
    val shape = RoundedCornerShape(26.dp)
    Column(
        Modifier
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp)
            .fillMaxWidth()
            .shadow(10.dp, shape, ambientColor = c.primary.copy(alpha = 0.10f), spotColor = c.primary.copy(alpha = 0.14f))
            .clip(shape)
            .background(c.surface)
            .border(1.dp, c.border, shape)
            .padding(6.dp),
    ) {
        if (attachment != null) {
            Box(Modifier.padding(start = 6.dp, top = 6.dp, bottom = 4.dp)) {
                Image(
                    attachment, contentDescription = "Attached photo",
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop,
                )
                IconBtn(
                    Res.drawable.ic_x, "Remove photo", onClearAttachment,
                    modifier = Modifier.align(Alignment.TopEnd).padding(2.dp),
                    tint = c.onPrimary, background = c.ink.copy(alpha = 0.7f), size = 22.dp, iconSize = 13.dp,
                )
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            IconBtn(Res.drawable.ic_image, "Attach a photo", onPickPhoto, tint = c.muted, enabled = !busy)
            UnstyledTextField(
                state = state,
                modifier = Modifier.weight(1f).heightIn(min = 40.dp).padding(horizontal = 4.dp, vertical = 9.dp),
                textStyle = Type.body,
                textColor = c.ink,
                cursorBrush = SolidColor(c.primary),
                lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 5),
                accessibilityLabel = "Message",
            ) {
                TextInput(placeholder = { Text("Magtanong kay Kuya J…", style = Type.body, color = c.faint) })
            }
            IconBtn(
                Res.drawable.ic_arrow_up, "Send",
                onClick = { onSend(state.text.toString()); state.clearText() },
                enabled = canSend,
                tint = if (canSend) c.onPrimary else c.faint,
                background = if (canSend) c.primary else c.surfaceSunken,
                iconSize = 19.dp,
            )
        }
    }
}

/** "https://www.nbi.gov.ph/x" -> "nbi.gov.ph" */
fun host(url: String): String = url.substringAfter("://").substringBefore('/').substringBefore('?').removePrefix("www.")
