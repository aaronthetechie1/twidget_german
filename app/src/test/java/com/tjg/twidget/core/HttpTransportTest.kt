package com.tjg.twidget.core

import java.io.ByteArrayInputStream
import java.io.InterruptedIOException
import org.junit.Assert.assertEquals
import org.junit.Test

class HttpTransportTest {
    @Test fun utf8TextPreservesMultibytePostAndAccountText() {
        val text = "Grüße, français, 日本語 👋"
        assertEquals(text, HttpTransport.readUtf8(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)), 100))
    }
    @Test(expected = IllegalArgumentException::class) fun chunkedBodyCannotExceedLimit() {
        HttpTransport.readUtf8(ByteArrayInputStream(ByteArray(101)), 100)
    }
    @Test(expected = InterruptedIOException::class) fun deadlineRejectsSlowResponse() {
        HttpTransport.readUtf8(ByteArrayInputStream(byteArrayOf(1)), 100, System.nanoTime() - 1)
    }
    @Test fun cancellationPreservesInterruptForOwner() {
        Thread.currentThread().interrupt()
        try {
            try { HttpTransport.readUtf8(ByteArrayInputStream(byteArrayOf(1)), 100); error("Expected cancellation") }
            catch (_: InterruptedIOException) { org.junit.Assert.assertTrue(Thread.currentThread().isInterrupted) }
        } finally { Thread.interrupted() }
    }
}
