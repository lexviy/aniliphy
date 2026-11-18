package com.aniliphy.anime

import com.aniliphy.japanese.Cue
import com.aniliphy.japanese.SubtitleCues
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A small web server on the phone that hands a cast device the episode. The sites only answer with their own headers
 * (Referer and so on), which a Chromecast can't send, so the phone fetches each piece and passes it on.
 *
 * Only addresses registered here (and the parts named in their playlists) are served, under a random token, so it's
 * not an open proxy on the Wi-Fi. HLS playlists are rewritten so every segment and key comes through here too.
 * Subtitles are turned into WebVTT, the one format the cast receiver reads.
 */
class CastRelay(
    private val client: OkHttpClient,
    private val userAgent: String,
    private val headers: Map<String, String>,
    /** Fetches a subtitle file's text (from the site or the phone). */
    private val readText: (String) -> String,
) {
    private val token = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val upstream = ConcurrentHashMap<String, String>()
    private val ids = ConcurrentHashMap<String, String>()
    private val subtitles = ConcurrentHashMap<String, Pair<String, String>>()
    private var server: ServerSocket? = null
    private var pool: ExecutorService? = null
    @Volatile private var host = "127.0.0.1"

    val port: Int get() = server?.localPort ?: 0
    val running: Boolean get() = server?.isClosed == false

    /** Starts listening on all interfaces; [host] is the phone's address on the Wi-Fi, used in the links handed out. */
    @Synchronized
    fun start(host: String) {
        this.host = host
        if (running) return
        val socket = ServerSocket(0, 16, InetAddress.getByName("0.0.0.0"))
        server = socket
        val workers = Executors.newCachedThreadPool { r -> Thread(r, "cast-relay").apply { isDaemon = true } }
        pool = workers
        workers.execute {
            while (!socket.isClosed) {
                val connection = try { socket.accept() } catch (_: IOException) { break }
                workers.execute { serve(connection) }
            }
        }
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        pool?.shutdownNow()
        server = null
        pool = null
    }

    private fun base() = "http://$host:$port/$token"

    /** The link a cast device uses for [url] (a web address or a file: on the phone). */
    fun link(url: String): String {
        val id = ids.getOrPut(url) { (ids.size + 1).toString(36).also { upstream[it] = url } }
        return "${base()}/m/$id/${lastPart(url)}"
    }

    /** The link to [url]'s subtitles as WebVTT. */
    fun subtitleLink(url: String, label: String): String {
        val id = "s" + (subtitles.size + 1)
        subtitles[id] = url to label
        return "${base()}/$id.vtt"
    }

    private fun serve(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 30_000
            val input = BufferedInputStream(s.getInputStream())
            val out = s.getOutputStream()
            try {
                val requestLine = readLine(input) ?: return
                val asked = HashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) asked[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                }
                val parts = requestLine.split(' ')
                if (parts.size < 2) return respond(out, 400, "text/plain", "bad request".toByteArray())
                val method = parts[0]
                if (method == "OPTIONS") return respond(out, 204, null, ByteArray(0))
                val path = parts[1].substringBefore('?')
                val prefix = "/$token/"
                if (!path.startsWith(prefix)) return respond(out, 404, "text/plain", "not here".toByteArray())
                val rest = path.removePrefix(prefix)
                val head = method == "HEAD"
                when {
                    rest.startsWith("m/") -> media(out, rest.removePrefix("m/").substringBefore('/'), asked["range"], head)
                    rest.endsWith(".vtt") -> subtitle(out, rest.removeSuffix(".vtt"), head)
                    else -> respond(out, 404, "text/plain", "not here".toByteArray())
                }
            } catch (_: SocketException) {
                // The receiver hung up (seeking does that).
            } catch (e: IOException) {
                runCatching { respond(out, 502, "text/plain", (e.message ?: "failed").toByteArray()) }
            }
        }
    }

    private fun subtitle(out: OutputStream, id: String, head: Boolean) {
        val (url, label) = subtitles[id] ?: return respond(out, 404, "text/plain", "no subtitles".toByteArray())
        val text = runCatching { readText(url) }.getOrElse { throw IOException("subtitles couldn't be read: ${it.message}") }
        val body = vtt(SubtitleCues.parse(label + "." + lastPart(url).substringAfterLast('.', "vtt"), text)).toByteArray()
        respond(out, 200, "text/vtt; charset=utf-8", if (head) ByteArray(0) else body, length = body.size.toLong())
    }

    private fun media(out: OutputStream, id: String, range: String?, head: Boolean) {
        val url = upstream[id] ?: return respond(out, 404, "text/plain", "unknown".toByteArray())
        if (url.startsWith("file:")) return file(out, File(URI(url)), range, head)
        val request = Request.Builder().url(url).header("User-Agent", userAgent).apply {
            headers.forEach { (k, v) -> header(k, v) }
            if (range != null) header("Range", range)
        }.build()
        client.newCall(request).execute().use { response ->
            val type = response.header("Content-Type")
            val body = response.body ?: return respond(out, 502, "text/plain", "empty".toByteArray())
            if (isPlaylist(url, type)) {
                val text = rewritePlaylist(body.string(), response.request.url.toString(), ::link).toByteArray()
                return respond(out, response.code, "application/vnd.apple.mpegurl", if (head) ByteArray(0) else text, length = text.size.toLong())
            }
            val extra = buildMap {
                response.header("Content-Range")?.let { put("Content-Range", it) }
                put("Accept-Ranges", "bytes")
            }
            val length = body.contentLength().takeIf { it >= 0 }
            writeHead(out, response.code, type ?: mimeOf(url), length, extra)
            if (!head) body.byteStream().use { copy(it, out) }
            out.flush()
        }
    }

    private fun file(out: OutputStream, file: File, range: String?, head: Boolean) {
        if (!file.isFile) return respond(out, 404, "text/plain", "no file".toByteArray())
        val size = file.length()
        val (from, to) = parseRange(range, size) ?: (0L to size - 1)
        val partial = range != null
        val extra = buildMap {
            put("Accept-Ranges", "bytes")
            if (partial) put("Content-Range", "bytes $from-$to/$size")
        }
        writeHead(out, if (partial) 206 else 200, mimeOf(file.name), to - from + 1, extra)
        if (!head) RandomAccessFile(file, "r").use { raf ->
            raf.seek(from)
            var left = to - from + 1
            val buffer = ByteArray(64 * 1024)
            while (left > 0) {
                val n = raf.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (n < 0) break
                out.write(buffer, 0, n)
                left -= n
            }
        }
        out.flush()
    }

    private fun respond(out: OutputStream, code: Int, type: String?, body: ByteArray, length: Long = body.size.toLong()) {
        writeHead(out, code, type, length, emptyMap())
        out.write(body)
        out.flush()
    }

    private fun writeHead(out: OutputStream, code: Int, type: String?, length: Long?, extra: Map<String, String>) {
        val text = StringBuilder("HTTP/1.1 $code ${reason(code)}\r\n")
        type?.let { text.append("Content-Type: $it\r\n") }
        length?.let { text.append("Content-Length: $it\r\n") }
        // The cast receiver is a web page, so it needs these for playlists, segments and subtitles.
        text.append("Access-Control-Allow-Origin: *\r\n")
        text.append("Access-Control-Allow-Headers: Range, Content-Type\r\n")
        text.append("Access-Control-Expose-Headers: Content-Length, Content-Range\r\n")
        extra.forEach { (k, v) -> text.append("$k: $v\r\n") }
        text.append("Connection: close\r\n\r\n")
        out.write(text.toString().toByteArray())
    }

    companion object {
        private const val PLAYLIST_TYPES = "mpegurl"
        private val URI_ATTRIBUTE = Regex("""URI="([^"]+)"""")

        fun isPlaylist(url: String, type: String?): Boolean =
            type?.contains(PLAYLIST_TYPES, ignoreCase = true) == true || url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)

        /** Every address in an HLS playlist (lines and URI="…" attributes) made absolute and passed through [link]. */
        fun rewritePlaylist(text: String, playlistUrl: String, link: (String) -> String): String {
            val base = URI(playlistUrl)
            fun absolute(ref: String) = base.resolve(ref.trim().replace(" ", "%20")).toString()
            return text.lines().joinToString("\n") { line ->
                when {
                    line.isBlank() -> line
                    line.startsWith("#") -> URI_ATTRIBUTE.replace(line) { m -> "URI=\"" + link(absolute(m.groupValues[1])) + "\"" }
                    else -> link(absolute(line))
                }
            }
        }

        /** Cues as a WebVTT file. */
        fun vtt(cues: List<Cue>): String = buildString {
            append("WEBVTT\n\n")
            for (cue in cues) {
                append(time(cue.startMs)).append(" --> ").append(time(cue.endMs)).append('\n')
                append(cue.text.trim()).append("\n\n")
            }
        }

        private fun time(ms: Long): String {
            val t = ms.coerceAtLeast(0)
            return "%02d:%02d:%02d.%03d".format(java.util.Locale.ROOT, t / 3_600_000, t / 60_000 % 60, t / 1000 % 60, t % 1000)
        }

        /** "bytes=100-" style ranges within [size]; null when absent or not understood. */
        fun parseRange(range: String?, size: Long): Pair<Long, Long>? {
            val m = Regex("""bytes=(\d*)-(\d*)""").find(range ?: return null) ?: return null
            val (a, b) = m.destructured
            if (a.isEmpty() && b.isEmpty()) return null
            if (a.isEmpty()) return (size - b.toLong()).coerceAtLeast(0) to size - 1
            val from = a.toLong()
            val to = b.toLongOrNull()?.coerceAtMost(size - 1) ?: (size - 1)
            return if (from > to) null else from to to
        }

        fun mimeOf(url: String): String {
            val path = url.substringBefore('?').lowercase()
            return when {
                path.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
                path.endsWith(".mpd") -> "application/dash+xml"
                path.endsWith(".ts") -> "video/mp2t"
                path.endsWith(".m4s") || path.endsWith(".mp4") || path.endsWith(".m4v") -> "video/mp4"
                path.endsWith(".webm") || path.endsWith(".mkv") -> "video/webm"
                path.endsWith(".vtt") -> "text/vtt"
                path.endsWith(".aac") -> "audio/aac"
                else -> "application/octet-stream"
            }
        }

        private fun lastPart(url: String): String =
            url.substringBefore('?').substringBefore('#').substringAfterLast('/').filter { it.isLetterOrDigit() || it in "._-" }.take(60).ifEmpty { "x" }

        private fun reason(code: Int) = when (code) {
            200 -> "OK"
            204 -> "No Content"
            206 -> "Partial Content"
            404 -> "Not Found"
            else -> "Status"
        }

        private fun readLine(input: InputStream): String? {
            val line = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0) return if (line.isEmpty()) null else line.toString()
                if (c == '\n'.code) return line.toString().trimEnd('\r')
                line.append(c.toChar())
                if (line.length > 8192) throw IOException("line too long")
            }
        }

        private fun copy(input: InputStream, out: OutputStream) {
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
        }
    }
}
