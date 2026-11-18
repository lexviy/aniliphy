package com.aniliphy.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** What the phone hands a TV: the AniList login and, if asked, the Gist sync. Never written to a log. */
@Serializable
data class TvLogin(
    val anilistToken: String? = null,
    val gistToken: String? = null,
    val gistId: String? = null,
) {
    override fun toString(): String =
        "TvLogin(anilist=${anilistToken != null}, gist=${gistToken != null})"
}

/**
 * Logging a TV in from the phone, straight over the Wi-Fi. The TV shows a short code and listens for a few minutes;
 * the phone finds it, and the two make a fresh key together (ECDH P-256) that only works with the code mixed in
 * (HKDF). The TV first proves it knows the code (a check value from that key); only then does the phone send the
 * login, sealed with it (AES-GCM). Something on the Wi-Fi pretending to be the TV gets nothing to guess the code
 * from. Every phone that connects uses up one of the TV's tries; after a few, or once it worked, or when time is
 * up, the TV stops listening.
 */
object TvPairing {
    const val SERVICE_TYPE = "_aniliphy._tcp."
    const val VERSION = 1
    const val CODE_LENGTH = 6
    const val MAX_TRIES = 3
    const val LIFETIME_MS = 10 * 60 * 1000L

    /** No 0/O, 1/I/L: easy to read off a TV across the room. */
    private const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
    private const val MAX_LINE = 16 * 1024
    private val AAD = "aniliphy-tv-$VERSION".toByteArray()
    private val json = Json { ignoreUnknownKeys = true }
    private val random = SecureRandom()

    fun newCode(): String = buildString { repeat(CODE_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

    /** "K7Q4MX" as "K7Q-4MX". */
    fun shown(code: String): String = if (code.length == CODE_LENGTH) code.take(3) + "-" + code.drop(3) else code

    /** What was typed, cleaned: capitals, and nothing that can't be in a code (spaces, the dash). */
    fun clean(typed: String): String = typed.uppercase().filter { it in ALPHABET }.take(CODE_LENGTH)

    internal fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun publicKey(bytes: ByteArray): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))

    /** The sealing key and the check key: the shared secret, salted with the code, bound to both public keys. */
    internal class Keys(val seal: ByteArray, val check: ByteArray) {
        /** The TV's proof that it knows the code. */
        fun tvProof(): ByteArray = hmac(check, "tv".toByteArray())
    }

    internal fun sessionKeys(own: KeyPair, other: ByteArray, code: String, tvPub: ByteArray, phonePub: ByteArray): Keys {
        val both = sessionKey(own, other, code, tvPub, phonePub, length = 64)
        return Keys(both.copyOfRange(0, 32), both.copyOfRange(32, 64))
    }

    internal fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    internal fun sessionKey(own: KeyPair, other: ByteArray, code: String, tvPub: ByteArray, phonePub: ByteArray, length: Int = 32): ByteArray {
        val shared = KeyAgreement.getInstance("ECDH").run {
            init(own.private)
            doPhase(publicKey(other), true)
            generateSecret()
        }
        val info = "aniliphy tv login".toByteArray() + tvPub + phonePub
        return hkdf(shared, salt = code.toByteArray(), info = info, length = length)
    }

    internal fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        fun mac(key: ByteArray) = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        val prk = mac(salt).doFinal(ikm)
        val out = java.io.ByteArrayOutputStream()
        var block = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            block = mac(prk).run { update(block); update(info); update(counter.toByte()); doFinal() }
            out.write(block)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }

    internal fun seal(key: ByteArray, plain: ByteArray): Pair<ByteArray, ByteArray> {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            updateAAD(AAD)
        }
        return iv to cipher.doFinal(plain)
    }

    /** Null when the key is wrong (a wrong code) or the box was touched. */
    internal fun open(key: ByteArray, iv: ByteArray, box: ByteArray): ByteArray? = runCatching {
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            updateAAD(AAD)
            doFinal(box)
        }
    }.getOrNull()

    private val b64 = Base64.getEncoder()
    private val unb64 = Base64.getDecoder()

    internal fun encode(login: TvLogin): ByteArray = json.encodeToString(TvLogin.serializer(), login).toByteArray()
    internal fun decode(bytes: ByteArray): TvLogin? = runCatching { json.decodeFromString(TvLogin.serializer(), String(bytes)) }.getOrNull()

    internal fun line(reader: BufferedReader): JsonObject? {
        val sb = StringBuilder()
        while (true) {
            val c = reader.read()
            if (c == -1) return null
            if (c == '\n'.code) break
            sb.append(c.toChar())
            if (sb.length > MAX_LINE) return null
        }
        return runCatching { Json.parseToJsonElement(sb.toString()).jsonObject }.getOrNull()
    }

    internal fun send(writer: OutputStreamWriter, obj: Map<String, Any>) {
        val body = JsonObject(obj.mapValues { (_, v) -> when (v) { is Boolean -> JsonPrimitive(v); is Number -> JsonPrimitive(v); else -> JsonPrimitive(v.toString()) } })
        writer.write(body.toString() + "\n")
        writer.flush()
    }

    internal fun JsonObject.str(key: String) = runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()
    internal fun JsonObject.bytes(key: String) = str(key)?.let { runCatching { unb64.decode(it) }.getOrNull() }
    internal fun ByteArray.b64(): String = b64.encodeToString(this)

    /** How a try to send went, for the phone to say. */
    sealed interface Sent {
        data object Done : Sent
        data class WrongCode(val triesLeft: Int) : Sent
        /** The TV stopped listening (time up, too many tries, or already logged in). */
        data object Closed : Sent
        data class Failed(val reason: String) : Sent
    }

    /** The phone's side: one try at handing [login] to the TV at [host]:[port] with [code]. */
    fun send(host: InetAddress, port: Int, code: String, login: TvLogin, timeoutMs: Int = 10_000): Sent = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = timeoutMs
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
            val own = keyPair()
            val phonePub = own.public.encoded
            send(writer, mapOf("v" to VERSION, "pub" to phonePub.b64()))
            val hello = line(reader) ?: return Sent.Closed
            if (hello["closed"] != null) return Sent.Closed
            val tvPub = hello.bytes("pub") ?: return Sent.Failed("The TV answered in a way this version doesn't know")
            val left = hello["left"]?.jsonPrimitive?.intOrNull ?: 0
            val keys = sessionKeys(own, tvPub, clean(code), tvPub, phonePub)
            val proof = hello.bytes("proof")
            // Not the code on the TV, or not the TV at all: nothing leaves the phone.
            if (proof == null || !java.security.MessageDigest.isEqual(proof, keys.tvProof())) {
                return if (left > 0) Sent.WrongCode(left) else Sent.Closed
            }
            val (iv, box) = seal(keys.seal, encode(login))
            send(writer, mapOf("iv" to iv.b64(), "box" to box.b64()))
            val answer = line(reader) ?: return Sent.Closed
            when {
                answer["ok"]?.jsonPrimitive?.booleanOrNull == true -> Sent.Done
                else -> Sent.Failed("The TV couldn't open what was sent. Try again")
            }
        }
    } catch (e: SocketException) {
        Sent.Closed
    } catch (e: java.io.IOException) {
        Sent.Failed("Couldn't reach the TV")
    }
}

/**
 * The TV's side: listens until a phone hands over a login with the right [code], [maxTries] wrong tries pass, or
 * [lifetimeMs] runs out. [onLogin] gets the login once, on the listening thread.
 */
class TvPairingServer(
    val code: String,
    private val onLogin: (TvLogin) -> Unit,
    private val onClosed: (Reason) -> Unit = {},
    private val maxTries: Int = TvPairing.MAX_TRIES,
    private val lifetimeMs: Long = TvPairing.LIFETIME_MS,
    private val bind: InetAddress? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    enum class Reason { LoggedIn, TooManyTries, TimeUp, Stopped }

    private val server = ServerSocket()
    @Volatile private var closed = false
    @Volatile var triesLeft = maxTries
        private set
    private val started = now()

    val port: Int get() = server.localPort

    fun start(): TvPairingServer {
        server.bind(InetSocketAddress(bind, 0))
        server.soTimeout = 1000
        Thread({ loop() }, "tv-pairing").apply { isDaemon = true }.start()
        return this
    }

    fun stop() = close(Reason.Stopped)

    private fun close(reason: Reason) {
        if (closed) return
        closed = true
        runCatching { server.close() }
        onClosed(reason)
    }

    private fun loop() {
        while (!closed) {
            if (now() - started >= lifetimeMs) { close(Reason.TimeUp); return }
            val socket = try { server.accept() } catch (_: java.net.SocketTimeoutException) { continue } catch (_: Exception) { close(Reason.Stopped); return }
            // One phone at a time: a try is short, and it keeps the count of wrong tries simple.
            runCatching { socket.use { handle(it) } }
        }
    }

    private fun handle(socket: Socket) = with(TvPairing) {
        socket.soTimeout = 10_000
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
        val hello = line(reader) ?: return
        if (closed) { send(writer, mapOf("closed" to true)); return }
        val phonePub = hello.bytes("pub") ?: return
        val own = keyPair()
        val tvPub = own.public.encoded
        val keys = runCatching { sessionKeys(own, phonePub, code, tvPub, phonePub) }.getOrNull() ?: return
        // Each phone that gets this far uses a try, whether or not it sends anything back.
        triesLeft--
        var done = false
        try {
            send(writer, mapOf("pub" to tvPub.b64(), "proof" to keys.tvProof().b64(), "left" to triesLeft.coerceAtLeast(0)))
            val sealed = line(reader) ?: return
            val iv = sealed.bytes("iv") ?: return
            val box = sealed.bytes("box") ?: return
            val login = open(keys.seal, iv, box)?.let(::decode)
            if (login != null && !closed) {
                send(writer, mapOf("ok" to true))
                done = true
                onLogin(login)
                close(Reason.LoggedIn)
            } else {
                send(writer, mapOf("ok" to false))
            }
        } finally {
            if (!done && triesLeft <= 0) close(Reason.TooManyTries)
        }
    }
}
