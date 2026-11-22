package com.riverrunner.web

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.riverrunner.Platform
import com.riverrunner.Settings
import com.riverrunner.SoundOut
import com.riverrunner.TextFiles
import com.riverrunner.audio.Sound
import com.riverrunner.ui.AppFonts
import com.riverrunner.ui.AppViewModel
import com.riverrunner.ui.Palette
import com.riverrunner.ui.RiverScreens
import com.riverrunner.ui.RiverTheme
import com.riverrunner.web.res.Res
import com.riverrunner.web.res.barlow_condensed_bold
import com.riverrunner.web.res.barlow_condensed_semibold
import com.riverrunner.web.res.noto_sans_sc
import org.jetbrains.compose.resources.Font
import kotlin.random.Random

// Browser pieces, implemented separately for the JS and the Wasm build (see Browser.*.kt).
internal expect fun storageGet(key: String): String?
/** False when the browser refused (storage full or blocked). */
internal expect fun storageSet(key: String, value: String): Boolean
internal expect fun storageRemove(key: String)
internal expect fun browserLanguage(): String
internal expect fun playAudio(url: String, volume: Double, rate: Double)

/** The whole app, as on Android. [onFirstFrame] runs once the first frame is on screen. */
@Composable
fun WebApp(onFirstFrame: () -> Unit = {}) {
    LaunchedEffect(Unit) {
        withFrameNanos { }
        onFirstFrame()
    }
    val vm = remember { AppViewModel(WebPlatform) }
    // The browser has no system fonts for the canvas: Chinese comes from the bundled Noto Sans SC.
    // Preloading also makes it the fallback for text in other families (such as the numerals).
    val text = FontFamily(Font(Res.font.noto_sans_sc))
    val fonts = AppFonts(
        numeric = FontFamily(
            Font(Res.font.barlow_condensed_semibold, FontWeight.SemiBold),
            Font(Res.font.barlow_condensed_bold, FontWeight.Bold),
        ),
        text = text,
    )
    // The same font also carries the few symbols the app writes as text (⚙ ✕ ⟲ 🏆 ...),
    // see tools/make_web_symbols.py.
    val resolver = LocalFontFamilyResolver.current
    LaunchedEffect(text) { runCatching { resolver.preload(text) } }
    RiverTheme(vm.strings, vm.deckStyle, fonts) {
        Box(Modifier.fillMaxSize().background(Palette.Bg)) { RiverScreens(vm) }
    }
}

/** localStorage keys start with this, so other pages on the same site cannot collide. */
private const val PREFIX = "rr."

internal object WebPlatform : Platform {
    override val settings: Settings = StorageSettings
    override val files: TextFiles = StorageFiles
    override val sound: SoundOut = AudioSound
    override val appVersion: String = BuildInfo.VERSION
    override val systemChinese: Boolean get() = browserLanguage().lowercase().startsWith("zh")
    override fun changelogText(): String = BuildInfo.CHANGELOG
}

/** Settings as strings in localStorage ("rr.set.<key>"). */
private object StorageSettings : Settings {
    private fun k(key: String) = PREFIX + "set." + key
    override fun contains(key: String) = storageGet(k(key)) != null
    override fun getBoolean(key: String, default: Boolean) = storageGet(k(key))?.toBooleanStrictOrNull() ?: default
    override fun putBoolean(key: String, value: Boolean) { storageSet(k(key), value.toString()) }
    override fun getInt(key: String, default: Int) = storageGet(k(key))?.toIntOrNull() ?: default
    override fun putInt(key: String, value: Int) { storageSet(k(key), value.toString()) }
    override fun getString(key: String, default: String) = storageGet(k(key)) ?: default
    override fun putString(key: String, value: String) { storageSet(k(key), value) }
    override fun getFloat(key: String, default: Float) = storageGet(k(key))?.toFloatOrNull() ?: default
}

/**
 * Files as localStorage entries ("rr.file.<name>"). A save slot's 1000-hand history is about
 * 400 KB, so six full slots fit in the usual 5 MB; when storage is full a write is skipped
 * (the hand still counts for this visit).
 */
private object StorageFiles : TextFiles {
    private fun k(name: String) = PREFIX + "file." + name
    override fun exists(name: String) = storageGet(k(name)) != null
    override fun read(name: String) = storageGet(k(name))
    override fun write(name: String, text: String) { storageSet(k(name), text) }
    override fun append(name: String, text: String) { storageSet(k(name), (storageGet(k(name)) ?: "") + text) }
    override fun delete(name: String) = storageRemove(k(name))
    override fun rename(from: String, to: String) {
        val text = storageGet(k(from)) ?: return
        if (storageSet(k(to), text)) storageRemove(k(from))
    }
}

/** The same sound files as the Android app (sfx/sfx_<name>.wav), played with audio elements. */
private object AudioSound : SoundOut {
    override var enabled = true
    override fun play(sound: Sound, volume: Float, varyPitch: Boolean) {
        if (!enabled) return
        val rate = if (varyPitch) 0.94 + Random.nextDouble() * 0.12 else 1.0
        playAudio("sfx/sfx_" + sound.name.lowercase() + ".wav", volume.coerceIn(0f, 1f).toDouble(), rate)
    }
}
