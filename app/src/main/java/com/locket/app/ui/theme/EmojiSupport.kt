package com.locket.app.ui.theme

import android.content.Context
import android.graphics.Typeface
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal data class EmojiEntry(val value: String, val name: String, val group: String)
internal data class EmojiCatalog(val entries: List<EmojiEntry>, val matches: Set<String>) {
    val maxLength = matches.maxOfOrNull { it.length } ?: 0
}
internal data class EmojiState(
    val family: FontFamily? = null,
    val catalog: EmojiCatalog? = null,
    val loading: Boolean = true,
    val error: Boolean = false,
    val retry: () -> Unit = {}
)
internal val LocalEmojiState = staticCompositionLocalOf { EmojiState(loading = false) }

private const val FONT_URL = "https://raw.githubusercontent.com/n4bi10p/Ios-emoji/46aface1d8b03f2aeb411cea9ac762baa70cd241/system/fonts/NotoColorEmoji.ttf"
private const val FONT_SIZE = 35321980L
private const val FONT_BLOB = "a4d2cd98b0e9f253ed633d6bb79fcc1a4bbceb8c"
private const val CATALOG_URL = "https://www.unicode.org/Public/17.0.0/emoji/emoji-test.txt"
private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

private fun validFont(file: File): Boolean {
    if (file.length() != FONT_SIZE) return false
    val digest = MessageDigest.getInstance("SHA-1")
    digest.update("blob $FONT_SIZE\u0000".toByteArray(Charsets.UTF_8))
    file.inputStream().use { input ->
        val buffer = ByteArray(65536)
        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == FONT_BLOB
}

private fun download(url: String, file: File, limit: Long, validate: (File) -> Boolean) {
    if (file.isFile && validate(file)) return
    val temp = File(file.parentFile, file.name + ".tmp")
    try {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            val body = response.body ?: error("Empty response")
            body.byteStream().use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        total += n; check(total <= limit) { "File too large" }
                        output.write(buffer, 0, n)
                    }
                }
            }
        }
        check(validate(temp)) { "Invalid emoji asset" }
        check(temp.renameTo(file)) { "Cannot install emoji asset" }
    } finally { temp.delete() }
}

private fun parseCatalog(file: File): EmojiCatalog {
    val entries = mutableListOf<EmojiEntry>()
    val matches = mutableSetOf<String>()
    var group = ""
    file.useLines { lines ->
        lines.forEach { line ->
            if (line.startsWith("# group:")) group = line.substringAfter(':').trim()
            else if (line.isNotBlank() && !line.startsWith("#") && ';' in line && '#' in line) {
                val status = line.substringAfter(';').substringBefore('#').trim()
                val value = line.substringBefore(';').trim().split(Regex("""\s+"""))
                    .joinToString("") { String(Character.toChars(it.toInt(16))) }
                if (status in setOf("fully-qualified", "minimally-qualified", "unqualified", "component")) matches.add(value)
                if (status == "fully-qualified" || status == "component") {
                    val name = line.substringAfter('#').trim().substringAfter(' ').substringAfter(' ')
                    entries.add(EmojiEntry(value, name, group))
                }
            }
        }
    }
    check(entries.size > 3000) { "Incomplete emoji catalog" }
    return EmojiCatalog(entries.distinctBy { it.value }, matches)
}

@Composable
internal fun ProvideEmojiSupport(content: @Composable () -> Unit) {
    val context = LocalContext.current.applicationContext
    var state by remember { mutableStateOf(EmojiState()) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        state = state.copy(loading = true, error = false)
        val folder = File(context.filesDir, "emoji").apply { mkdirs() }
        val catalog = withContext(Dispatchers.IO) {
            runCatching {
                val file = File(folder, "unicode17.txt")
                download(CATALOG_URL, file, 2_000_000L) { runCatching { parseCatalog(it) }.isSuccess }
                parseCatalog(file)
            }.getOrNull()
        }
        state = state.copy(catalog = catalog ?: state.catalog)
        val typeface = withContext(Dispatchers.IO) {
            runCatching {
                val file = File(folder, "ios26_4.ttf")
                download(FONT_URL, file, FONT_SIZE, ::validFont)
                Typeface.createFromFile(file)
            }.getOrNull()
        }
        state = state.copy(family = typeface?.let { FontFamily(it) } ?: state.family,
            loading = false, error = typeface == null || catalog == null)
    }
    CompositionLocalProvider(LocalEmojiState provides state.copy(retry = { if (!state.loading) attempt++ }), content = content)
}

private fun EmojiState.styled(value: AnnotatedString): AnnotatedString {
    val font = family ?: return value
    val list = catalog ?: return value
    val text = value.text
    return buildAnnotatedString {
        append(value)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            var end = (i + list.maxLength).coerceAtMost(text.length)
            if (cp < 128 && cp !in 48..57 && cp != 35 && cp != 42) { i++; continue }
            var found = false
            while (end > i) {
                if (text.substring(i, end) in list.matches) {
                    addStyle(SpanStyle(fontFamily = font), i, end)
                    i = end
                    found = true
                    break
                }
                end--
            }
            if (!found) i += Character.charCount(cp)
        }
    }
}

@Composable
internal fun emojiStyled(text: String): AnnotatedString {
    val state = LocalEmojiState.current
    return remember(text, state.family, state.catalog) { state.styled(AnnotatedString(text)) }
}

@Composable
internal fun emojiInputTransformation(): VisualTransformation {
    val state = LocalEmojiState.current
    return remember(state.family, state.catalog) {
        VisualTransformation { value -> TransformedText(state.styled(value), OffsetMapping.Identity) }
    }
}
