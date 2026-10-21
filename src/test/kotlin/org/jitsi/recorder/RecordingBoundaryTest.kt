package org.jitsi.recorder

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.jitsi.mediajson.Event
import org.jitsi.utils.logging2.Logger
import java.lang.reflect.Proxy
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RecordingBoundaryTest : ShouldSpec({
    should("reject excess tracks before allocating persistent child logger state") {
        withConfig("jitsi-multitrack-recorder.recording.max-tracks = 1") {
            val children = AtomicInteger()
            lateinit var logger: Logger
            logger = Proxy.newProxyInstance(
                Logger::class.java.classLoader,
                arrayOf(Logger::class.java)
            ) { _, method, _ ->
                when {
                    method.name == "createChildLogger" -> {
                        children.incrementAndGet()
                        logger
                    }
                    method.returnType == Boolean::class.javaPrimitiveType -> false
                    else -> null
                }
            } as Logger
            val recorder = MediaJsonMkaRecorder(Files.createTempDirectory("jmr-wrapper-limit").toFile(), logger)
            val initialChildren = children.get()
            RecorderMetrics.instance.queueExceptions.get() shouldBe 0
            try {
                repeat(101) { sequence ->
                    recorder.addEvent(
                        Event.parse(
                            """{"event":"start","sequenceNumber":"$sequence","start":{"tag":"track-$sequence",
                            "mediaFormat":{"encoding":"opus","sampleRate":48000,"channels":2}}}"""
                        )
                    )
                }
            } finally {
                recorder.stop()
            }
            // The accepted track creates its logger and PLC logger; rejected
            // tracks must create neither. Account for expected policy errors
            // explicitly before resetting the test metrics. Do not hide an
            // unexpected queue failure or dropped event from the project gate.
            RecorderMetrics.instance.queueExceptions.get() shouldBe 100
            RecorderMetrics.instance.queueEventsDropped.get() shouldBe 0
            RecorderMetrics.instance.resetAll()
            children.get() shouldBe initialChildren + 2
        }
    }
    should("bound cumulative physical tracks including resets with a repeated name") {
        withConfig("jitsi-multitrack-recorder.recording.max-tracks = 2") {
            val root = Files.createTempDirectory("jmr-track-limit")
            val recorder = MkaRecorder(root.toFile())
            try {
                recorder.startTrack("same", "endpoint")
                recorder.startTrack("same", "endpoint")
                shouldThrow<IllegalArgumentException> { recorder.startTrack("different") }
                shouldThrow<IllegalArgumentException> { recorder.startTrack("same") }
            } finally {
                recorder.close()
            }
            countMkaElements(root.resolve("recording.mka")) { it.elementType.name == "TrackEntry" } shouldBe 2
        }
    }
    should("bound metadata by UTF-8 bytes before retaining or counting a track") {
        withConfig(
            """jitsi-multitrack-recorder.recording {
                max-tracks = 1
                max-track-metadata-bytes = 4
            }"""
        ) {
            val root = Files.createTempDirectory("jmr-metadata-limit")
            val recorder = MkaRecorder(root.toFile())
            try {
                shouldThrow<IllegalArgumentException> { recorder.startTrack("ééé") }
                shouldThrow<IllegalArgumentException> { recorder.startTrack("ok", "12345") }
                recorder.startTrack("éé", "1234")
            } finally {
                recorder.close()
            }
            countMkaElements(root.resolve("recording.mka")) { it.elementType.name == "TrackEntry" } shouldBe 1
        }
    }
    should("atomically claim distinct directories for concurrent same-meeting sessions") {
        val root = Files.createTempDirectory("jmr-directory-race")
        val pool = Executors.newFixedThreadPool(16)
        try {
            val results = pool.invokeAll(
                (1..32).map { Callable { selectRecordingDirectory(root, "meeting").toPath() } }
            )
                .map { it.get(10, TimeUnit.SECONDS) }
            results.toSet().size shouldBe 32
            results.all { Files.isDirectory(it) } shouldBe true
        } finally {
            pool.shutdownNow()
        }
    }
    should("preserve suffix collisions and fail instead of claiming another file") {
        val root = Files.createTempDirectory("jmr-directory-collisions").resolve("new-root")
        selectRecordingDirectory(root, "meeting").name shouldBe "meeting"
        Files.createFile(root.resolve("meeting-1"))
        selectRecordingDirectory(root, "meeting").name shouldBe "meeting-2"
        (3..99).forEach { Files.createDirectory(root.resolve("meeting-$it")) }
        shouldThrow<IllegalStateException> { selectRecordingDirectory(root, "meeting") }
        shouldThrow<IllegalArgumentException> { selectRecordingDirectory(root, "../outside") }
        val fileRoot = root.resolve("meeting-1")
        shouldThrow<FileSystemException> { selectRecordingDirectory(fileRoot, "meeting") }
    }
})
