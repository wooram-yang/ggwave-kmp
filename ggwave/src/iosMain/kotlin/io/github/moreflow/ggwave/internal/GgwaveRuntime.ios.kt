package io.github.moreflow.ggwave.internal

import io.github.moreflow.ggwave.GgwaveConfig
import io.github.moreflow.ggwave.GgwaveCodecConfig
import io.github.moreflow.ggwave.GgwaveException
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioPlayerDelegateProtocol
import platform.AudioToolbox.AudioQueueAllocateBuffer
import platform.AudioToolbox.AudioQueueBufferRef
import platform.AudioToolbox.AudioQueueBufferRefVar
import platform.AudioToolbox.AudioQueueDispose
import platform.AudioToolbox.AudioQueueEnqueueBuffer
import platform.AudioToolbox.AudioQueueNewInput
import platform.AudioToolbox.AudioQueueRef
import platform.AudioToolbox.AudioQueueRefVar
import platform.AudioToolbox.AudioQueueStart
import platform.AudioToolbox.AudioQueueStop
import platform.CoreAudioTypes.AudioStreamBasicDescription
import platform.CoreAudioTypes.AudioStreamPacketDescription
import platform.CoreAudioTypes.AudioTimeStamp
import platform.CoreAudioTypes.kAudioFormatLinearPCM
import platform.CoreAudioTypes.kLinearPCMFormatFlagIsSignedInteger
import platform.CoreFoundation.CFRunLoopGetCurrent
import platform.CoreFoundation.CFRunLoopRef
import platform.CoreFoundation.CFRunLoopRun
import platform.CoreFoundation.CFRunLoopStop
import platform.CoreFoundation.kCFRunLoopCommonModes
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSMutableData
import platform.Foundation.NSLock
import platform.Foundation.appendData
import platform.Foundation.create
import platform.darwin.NSObject
import platform.darwin.OSStatus
import platform.darwin.UInt32

@OptIn(ExperimentalForeignApi::class)
internal fun audioQueueInputCallback(
    inUserData: COpaquePointer?,
    inAQ: AudioQueueRef?,
    inBuffer: AudioQueueBufferRef?,
    inStartTime: CPointer<AudioTimeStamp>?,
    inNumberPacketDescriptions: UInt32,
    inPacketDescs: CPointer<AudioStreamPacketDescription>?,
) {
    val runtime = inUserData?.asStableRef<IosGgwaveRuntime>()?.get() ?: return
    if (!runtime.isCapturing()) return
    val queue = inAQ ?: return
    val buffer = inBuffer ?: return

    try {
        val audioData = buffer.pointed.mAudioData!!.reinterpret<ByteVar>()
        val audioDataSize = buffer.pointed.mAudioDataByteSize.toInt()
        val audioBytes = audioData.readBytes(audioDataSize)
        runtime.onCaptureBytes(audioBytes)
    } catch (error: Throwable) {
        runtime.failCapture(error)
        return
    }

    val status = AudioQueueEnqueueBuffer(queue, buffer, 0u, null)
    if (status != 0) {
        runtime.failCapture(GgwaveException("AudioQueueEnqueueBuffer failed: $status"))
    }
}

@OptIn(ExperimentalForeignApi::class)
internal class IosGgwaveRuntime(
    private val config: GgwaveConfig,
) : GgwaveRuntime {
    private val inputSampleRate = config.sampleRate.toDouble()
    private val bufferSize = config.captureBufferSize
    private val bufferNum = 3

    private val codecs = GgwaveRuntimeCodecs.create(
        GgwaveCodecConfig(
            inputSampleRate = config.sampleRate,
            outputSampleRate = config.sampleRate,
        ),
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val callbackContext = StableRef.create(this)
    private val encoderLock = NSLock()
    private val decoderLock = NSLock()

    private var onReceivedMessage: (String) -> Unit = {}
    private var onCaptureFailed: (Throwable) -> Unit = {}
    private var onPlayEnded: () -> Unit = {}
    private var onPlayFailed: (Throwable) -> Unit = {}

    private var audioPlayer: AVAudioPlayer? = null
    private val audioPlayerDelegate = AVAudioPlayerDelegate()
    private var playbackJob: Job? = null
    private var audioQueue: AudioQueueRef? = null
    private var captureRunLoop: CFRunLoopRef? = null
    private var captureJob: Job? = null
    private var closed = false

    override fun setListeners(
        onReceivedMessage: (String) -> Unit,
        onCaptureFailed: (Throwable) -> Unit,
        onPlayEnded: () -> Unit,
        onPlayFailed: (Throwable) -> Unit,
    ) {
        this.onReceivedMessage = onReceivedMessage
        this.onCaptureFailed = onCaptureFailed
        this.onPlayEnded = onPlayEnded
        this.onPlayFailed = onPlayFailed
    }

    internal fun onCaptureBytes(byteData: ByteArray) {
        val samples = ShortArray(byteData.size / 2) { index ->
            val low = byteData[index * 2].toInt() and 0xFF
            val high = byteData[(index * 2) + 1].toInt() shl 8
            (low or high).toShort()
        }
        val payload = withLock(decoderLock) {
            codecs.decoder.decode(samples)
        } ?: return
        val text = payload.toGgwavePayload()
        if (text.isNotBlank()) {
            onReceivedMessage(text)
        }
    }

    override fun startCapturing() {
        stopCapturing()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var ownedQueue: AudioQueueRef? = null
            try {
                memScoped {
                    val audioFormat = alloc<AudioStreamBasicDescription>().apply {
                        mSampleRate = inputSampleRate
                        mFormatID = kAudioFormatLinearPCM
                        mFramesPerPacket = 1u
                        mChannelsPerFrame = 1u
                        mBytesPerFrame = 2u
                        mBytesPerPacket = 2u
                        mBitsPerChannel = 16u
                        mReserved = 0u
                        mFormatFlags = kLinearPCMFormatFlagIsSignedInteger
                    }
                    val queueRef = alloc<AudioQueueRefVar>()

                    val callback = staticCFunction(::audioQueueInputCallback)
                    val status: OSStatus = AudioQueueNewInput(
                        audioFormat.ptr,
                        callback,
                        callbackContext.asCPointer(),
                        CFRunLoopGetCurrent(),
                        kCFRunLoopCommonModes,
                        0u,
                        queueRef.ptr,
                    )
                    checkAudioQueueStatus(status, "AudioQueueNewInput")

                    val queue = queueRef.value
                        ?: throw GgwaveException("AudioQueueNewInput returned no queue")
                    ownedQueue = queue
                    coroutineContext.ensureActive()
                    audioQueue = queue

                    for (i in 0 until bufferNum) {
                        val bufferRefVar: AudioQueueBufferRefVar = alloc<AudioQueueBufferRefVar>()
                        checkAudioQueueStatus(
                            AudioQueueAllocateBuffer(queue, bufferSize.toUInt(), bufferRefVar.ptr),
                            "AudioQueueAllocateBuffer",
                        )
                        checkAudioQueueStatus(
                            AudioQueueEnqueueBuffer(queue, bufferRefVar.value, 0u, null),
                            "AudioQueueEnqueueBuffer",
                        )
                    }

                    checkAudioQueueStatus(AudioQueueStart(queue, null), "AudioQueueStart")
                    coroutineContext.ensureActive()
                    captureRunLoop = CFRunLoopGetCurrent()
                    try {
                        CFRunLoopRun()
                    } finally {
                        captureRunLoop = null
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (isActive) {
                    onCaptureFailed(error)
                }
            } finally {
                if (audioQueue == ownedQueue) {
                    audioQueue = null
                    disposeAudioQueue(ownedQueue)
                }
                if (captureJob === coroutineContext[Job]) {
                    captureJob = null
                }
            }
        }
        captureJob = job
        job.start()
    }

    override fun stopCapturing() {
        val job = captureJob
        captureJob = null
        val runLoop = captureRunLoop
        captureRunLoop = null
        val queue = audioQueue
        audioQueue = null
        job?.cancel()
        runLoop?.let { CFRunLoopStop(it) }
        disposeAudioQueue(queue)
    }

    override fun startPlayback(message: String) {
        val encoded = try {
            withLock(encoderLock) {
                codecs.encoder.encode(message.encodeToByteArray(), volume = config.volume)
            }
        } catch (error: Throwable) {
            onPlayFailed(error)
            return
        }
        if (encoded.isEmpty()) {
            onPlayFailed(GgwaveException("Failed to encode message"))
            return
        }
        play(encoded)
    }

    override fun stopPlayback() {
        playbackJob?.cancel()
        playbackJob = null
        val player = audioPlayer
        audioPlayer = null
        player?.setDelegate(null)
        player?.stop()
    }

    private fun play(dataArray: ShortArray) {
        stopPlayback()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var createdPlayer: AVAudioPlayer? = null
            try {
                val convertedAudioData = dataArray.toLittleEndianByteArray()
                val completeAudioData = NSMutableData()
                completeAudioData.appendData(
                    createPcm16MonoWaveHeader(convertedAudioData.size, config.sampleRate).toNSData(),
                )
                completeAudioData.appendData(convertedAudioData.toNSData())

                memScoped {
                    val errorPtr = alloc<ObjCObjectVar<NSError?>>()
                    createdPlayer = AVAudioPlayer(data = completeAudioData, fileTypeHint = "wav", error = errorPtr.ptr)
                    errorPtr.value?.let { throw GgwaveException(it.localizedDescription) }
                }
                coroutineContext.ensureActive()
                val player = createdPlayer ?: throw GgwaveException("AVAudioPlayer failed to initialize")
                audioPlayer = player
                coroutineContext.ensureActive()
                player.setDelegate(audioPlayerDelegate)
                if (!player.play()) {
                    throw GgwaveException("AVAudioPlayer failed to start playback")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (isActive) {
                    onPlayFailed(error)
                }
            } finally {
                if (!isActive && audioPlayer === createdPlayer) {
                    audioPlayer = null
                    createdPlayer?.setDelegate(null)
                    createdPlayer?.stop()
                }
                if (playbackJob === coroutineContext[Job]) {
                    playbackJob = null
                }
            }
        }
        playbackJob = job
        job.start()
    }

    override fun close() {
        if (closed) return
        closed = true
        stopCapturing()
        stopPlayback()
        scope.cancel()
        try {
            try {
                withLock(encoderLock) {
                    codecs.encoder.close()
                }
            } finally {
                withLock(decoderLock) {
                    codecs.decoder.close()
                }
            }
        } finally {
            callbackContext.dispose()
        }
    }

    internal fun isCapturing(): Boolean = captureJob?.isActive == true

    internal fun failCapture(error: Throwable) {
        onCaptureFailed(error)
        captureJob?.cancel()
        captureRunLoop?.let { CFRunLoopStop(it) }
    }

    private fun checkAudioQueueStatus(status: OSStatus, operation: String) {
        if (status != 0) {
            throw GgwaveException("$operation failed: $status")
        }
    }

    private fun disposeAudioQueue(queue: AudioQueueRef?) {
        if (queue == null) return
        AudioQueueStop(queue, true)
        AudioQueueDispose(queue, true)
    }

    private inline fun <T> withLock(lock: NSLock, action: () -> T): T {
        lock.lock()
        return try {
            action()
        } finally {
            lock.unlock()
        }
    }

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    private fun ByteArray.toNSData() = this.usePinned {
        NSData.create(bytes = it.addressOf(0), length = this.size.convert())
    }

    inner class AVAudioPlayerDelegate : NSObject(), AVAudioPlayerDelegateProtocol {
        override fun audioPlayerDidFinishPlaying(player: AVAudioPlayer, successfully: Boolean) {
            if (audioPlayer !== player) return
            audioPlayer = null
            player.setDelegate(null)
            if (successfully) {
                onPlayEnded()
            } else {
                onPlayFailed(GgwaveException("Audio playback did not finish successfully"))
            }
        }

        override fun audioPlayerDecodeErrorDidOccur(player: AVAudioPlayer, error: NSError?) {
            if (audioPlayer !== player) return
            audioPlayer = null
            player.setDelegate(null)
            onPlayFailed(GgwaveException(error?.localizedDescription ?: "Audio decode error"))
        }
    }
}

internal actual fun createGgwaveRuntime(config: GgwaveConfig): GgwaveRuntime {
    return IosGgwaveRuntime(config)
}
