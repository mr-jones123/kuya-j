package ph.bettergov.kuyaj.ui

import androidx.compose.runtime.Composable
import com.composeunstyled.ModalBottomSheetHost
import com.composeunstyled.SheetDetent
import com.composeunstyled.rememberModalBottomSheetState

/** The whole app: the chat screen with the settings sheet over it. Each platform calls this once. */
@Composable
fun KuyaJApp(vm: ChatViewModel, onPickPhoto: () -> Unit) {
    KuyaJTheme {
        ModalBottomSheetHost {
            val settings = rememberModalBottomSheetState(initialDetent = SheetDetent.Hidden)
            ChatScreen(vm, onOpenSettings = { settings.targetDetent = SheetDetent.FullyExpanded }, onPickPhoto = onPickPhoto)
            SettingsSheet(vm, settings)
        }
    }
}
