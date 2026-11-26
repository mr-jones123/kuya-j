package ph.bettergov.kuyaj

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import android.graphics.Color
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ph.bettergov.kuyaj.ui.Attachment
import ph.bettergov.kuyaj.ui.ChatViewModel
import ph.bettergov.kuyaj.ui.KuyaJApp as KuyaJUi
import java.io.ByteArrayOutputStream

class MainActivity : ComponentActivity() {
    private val vm: ChatViewModel by viewModels {
        viewModelFactory { initializer { ChatViewModel((application as KuyaJApp).container) } }
    }

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) lifecycleScope.launch { withContext(Dispatchers.IO) { loadScaled(uri) }?.let(vm::attach) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // White status-bar icons over the blue welcome hero and in dark mode; dark icons over the light chat.
            val messages by vm.messages.collectAsState()
            val dark = isSystemInDarkTheme()
            LaunchedEffect(messages.isEmpty(), dark) {
                enableEdgeToEdge(
                    statusBarStyle = if (messages.isEmpty() || dark) SystemBarStyle.dark(Color.TRANSPARENT)
                    else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                )
            }
            KuyaJUi(vm, onPickPhoto = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
        }
    }

    /** Decodes at most ~1024 px on the long side and re-encodes as JPEG, so a 12 MP photo does not fill memory. */
    private fun loadScaled(uri: Uri): Attachment? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1024) sample *= 2
        val bitmap = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
        return Attachment(bitmap.asImageBitmap(), jpeg)
    }
}
