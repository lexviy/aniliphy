package com.aniliphy.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class TvPairingTest {
    private val local = InetAddress.getLoopbackAddress()
    private val login = TvLogin(anilistToken = "anilist-secret", gistToken = "gist-secret", gistId = "abc123")

    private fun server(
        code: String = "K7Q4MX",
        got: AtomicReference<TvLogin?> = AtomicReference(null),
        closed: AtomicReference<TvPairingServer.Reason?> = AtomicReference(null),
        latch: CountDownLatch = CountDownLatch(1),
        lifetime: Long = TvPairing.LIFETIME_MS,
        now: () -> Long = System::currentTimeMillis,
    ) = TvPairingServer(code, onLogin = { got.set(it) }, onClosed = { closed.set(it); latch.countDown() }, lifetimeMs = lifetime, bind = local, now = now).start()

    @Test
    fun codesAreReadable() {
        repeat(200) {
            val code = TvPairing.newCode()
            assertEquals(6, code.length)
            assertTrue(code.none { it in "01OIL" })
        }
        assertEquals("K7Q-4MX", TvPairing.shown("K7Q4MX"))
        assertEquals("K7Q4MX", TvPairing.clean(" k7q-4mx "))
    }

    @Test
    fun rightCodeLogsIn() {
        val got = AtomicReference<TvLogin?>(null)
        val closed = AtomicReference<TvPairingServer.Reason?>(null)
        val latch = CountDownLatch(1)
        val tv = server(got = got, closed = closed, latch = latch)
        assertEquals(TvPairing.Sent.Done, TvPairing.send(local, tv.port, "k7q-4mx", login))
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(login, got.get())
        assertEquals(TvPairingServer.Reason.LoggedIn, closed.get())
    }

    @Test
    fun wrongCodeIsCountedAndRightOneStillWorks() {
        val got = AtomicReference<TvLogin?>(null)
        val tv = server(got = got)
        assertEquals(TvPairing.Sent.WrongCode(2), TvPairing.send(local, tv.port, "AAAAAA", login))
        assertNull(got.get())
        assertEquals(TvPairing.Sent.Done, TvPairing.send(local, tv.port, "K7Q4MX", login))
    }

    @Test
    fun tooManyWrongTriesCloses() {
        val closed = AtomicReference<TvPairingServer.Reason?>(null)
        val latch = CountDownLatch(1)
        val tv = server(closed = closed, latch = latch)
        assertEquals(TvPairing.Sent.WrongCode(2), TvPairing.send(local, tv.port, "AAAAAA", login))
        assertEquals(TvPairing.Sent.WrongCode(1), TvPairing.send(local, tv.port, "BBBBBB", login))
        assertEquals(TvPairing.Sent.Closed, TvPairing.send(local, tv.port, "CCCCCC", login))
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(TvPairingServer.Reason.TooManyTries, closed.get())
        // Even the right code is too late now.
        assertFalse(TvPairing.send(local, tv.port, "K7Q4MX", login) == TvPairing.Sent.Done)
    }

    @Test
    fun secondPhoneAfterLoginIsRefused() {
        val latch = CountDownLatch(1)
        val tv = server(latch = latch)
        assertEquals(TvPairing.Sent.Done, TvPairing.send(local, tv.port, "K7Q4MX", login))
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(TvPairing.Sent.Closed, TvPairing.send(local, tv.port, "K7Q4MX", login))
    }

    @Test
    fun timeUpCloses() {
        var clock = 0L
        val closed = AtomicReference<TvPairingServer.Reason?>(null)
        val latch = CountDownLatch(1)
        val tv = server(closed = closed, latch = latch, lifetime = 1000, now = { clock })
        clock = 2000
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(TvPairingServer.Reason.TimeUp, closed.get())
        assertEquals(TvPairing.Sent.Closed, TvPairing.send(local, tv.port, "K7Q4MX", login, timeoutMs = 2000))
    }

    @Test
    fun sealedBoxNeedsTheSameKey() {
        val a = TvPairing.keyPair()
        val b = TvPairing.keyPair()
        val k1 = TvPairing.sessionKey(a, b.public.encoded, "K7Q4MX", b.public.encoded, a.public.encoded)
        val k2 = TvPairing.sessionKey(b, a.public.encoded, "K7Q4MX", b.public.encoded, a.public.encoded)
        val wrong = TvPairing.sessionKey(b, a.public.encoded, "K7Q4MY", b.public.encoded, a.public.encoded)
        val (iv, box) = TvPairing.seal(k1, "hello".toByteArray())
        assertEquals("hello", String(TvPairing.open(k2, iv, box)!!))
        assertNull(TvPairing.open(wrong, iv, box))
        // The secret never shows in the box or in a log line.
        assertFalse(String(box, Charsets.ISO_8859_1).contains("hello"))
        assertFalse(login.toString().contains("secret"))
    }

    @Test
    fun fakeTvGetsNothing() {
        // Something on the Wi-Fi posing as a TV: it does the key exchange but doesn't know the code.
        val server = java.net.ServerSocket(0, 1, local)
        val received = java.util.Collections.synchronizedList(mutableListOf<String>())
        val done = CountDownLatch(1)
        Thread {
            server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                val writer = socket.getOutputStream().bufferedWriter()
                reader.readLine()?.let(received::add)
                val own = TvPairing.keyPair()
                val pub = java.util.Base64.getEncoder().encodeToString(own.public.encoded)
                val fake = java.util.Base64.getEncoder().encodeToString(ByteArray(32))
                writer.write("{\"pub\":\"$pub\",\"proof\":\"$fake\",\"left\":2}\n")
                writer.flush()
                socket.soTimeout = 2000
                runCatching { reader.readLine() }.getOrNull()?.let(received::add)
            }
            done.countDown()
        }.start()
        val sent = TvPairing.send(local, server.localPort, "K7Q4MX", login)
        assertTrue(done.await(5, TimeUnit.SECONDS))
        server.close()
        assertEquals(TvPairing.Sent.WrongCode(2), sent)
        // Only the phone's hello (its public key) ever reached it: no sealed box to guess the code from.
        assertEquals(1, received.size)
        assertFalse(received.any { it.contains("box") || it.contains("secret") })
    }

    @Test
    fun hkdfMatchesRfc5869() {
        // RFC 5869, test case 1.
        fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val okm = TvPairing.hkdf(hex("0b".repeat(22)), hex("000102030405060708090a0b0c"), hex("f0f1f2f3f4f5f6f7f8f9"), 42)
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            okm.joinToString("") { "%02x".format(it) },
        )
    }
}
