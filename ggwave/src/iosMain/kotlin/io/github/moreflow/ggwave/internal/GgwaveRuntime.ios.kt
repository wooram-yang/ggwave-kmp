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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioPlayerDelegateProtocol
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionDefaultToSpeaker
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionModeDefault
import platform.AVFAudio.AVAudioSessionPortBuiltInMic
import platform.AVFAudio.AVAudioSessionPortDescription
import platform.AVFAudio.availableInputs
import platform.AVFAudio.setActive
import platform.AVFAudio.setPreferredSampleRate
import platform.AVFAudio.inputAvailable
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
import platform.CoreAudioTypes.kAudioFormatFlagIsPacked
import platform.CoreAudioTypes.kAudioFormatLinearPCM
import platform.CoreAudioTypes.kLinearPCMFormatFlagIsSignedInteger
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSMutableData
import platform.Foundation.NSLock
import platform.Foundation.NSThread
import platform.Foundation.appendData
import platform.Foundation.create
import platform.darwin.NSObject
import platform.darwin.OSStatus
import platform.darwin.UInt32
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume

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
        val status = AudioQueueEnqueueBuffer(queue, buffer, 0u, null)
        if (status != 0) {
            runtime.failCapture(GgwaveException("AudioQueueEnqueueBuffer failed: $status"))
        }
    } catch (error: Throwable) {
        runtime.failCapture(error)
    }
}

@OptIn(ExperimentalForeignApi::class)
internal class IosGgwaveRuntime(
    private val config: GgwaveConfig,
) : GgwaveRuntime {
    private val inputSampleRate = config.sampleRate.toDouble()
    private val bufferSize = config.captureBufferSize
    private val bufferNum = 24

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
    private var onCaptureLevelChanged: (Float) -> Unit = {}
    private var onCaptureFailed: (Throwable) -> Unit = {}
    private var onPlayEnded: () -> Unit = {}
    private var onPlayFailed: (Throwable) -> Unit = {}

    private var audioPlayer: AVAudioPlayer? = null
    private var playbackCompletion: CompletableDeferred<Throwable?>? = null
    private val audioPlayerDelegate = AVAudioPlayerDelegate()
    private var playbackJob: Job? = null
    private var audioQueue: AudioQueueRef? = null
    private var captureJob: Job? = null
    private var capturedBufferCount = 0
    private val captureDecodeBuffer = ByteArray(bufferSize)
    private var captureDecodeBufferSize = 0
    private var closed = false

    override fun setListeners(
        onReceivedMessage: (String) -> Unit,
        onCaptureLevelChanged: (Float) -> Unit,
        onCaptureFailed: (Throwable) -> Unit,
        onPlayEnded: () -> Unit,
        onPlayFailed: (Throwable) -> Unit,
    ) {
        this.onReceivedMessage = onReceivedMessage
        this.onCaptureLevelChanged = onCaptureLevelChanged
        this.onCaptureFailed = onCaptureFailed
        this.onPlayEnded = onPlayEnded
        this.onPlayFailed = onPlayFailed
    }

    internal fun onCaptureBytes(byteData: ByteArray) {
        var sourceOffset = 0
        while (sourceOffset < byteData.size) {
            val byteCount = minOf(
                captureDecodeBuffer.size - captureDecodeBufferSize,
                byteData.size - sourceOffset,
            )
            byteData.copyInto(
                destination = captureDecodeBuffer,
                destinationOffset = captureDecodeBufferSize,
                startIndex = sourceOffset,
                endIndex = sourceOffset + byteCount,
            )
            captureDecodeBufferSize += byteCount
            sourceOffset += byteCount
            if (captureDecodeBufferSize == captureDecodeBuffer.size) {
                decodeCaptureChunk(captureDecodeBuffer)
                captureDecodeBufferSize = 0
            }
        }
    }

    private fun decodeCaptureChunk(byteData: ByteArray) {
        val samples = ShortArray(byteData.size / 2) { index ->
            val low = byteData[index * 2].toInt() and 0xFF
            val high = byteData[(index * 2) + 1].toInt() shl 8
            (low or high).toShort()
        }
        if (++capturedBufferCount % CAPTURE_LEVEL_UPDATE_INTERVAL_BUFFERS == 0) {
            val peak = samples.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
            onCaptureLevelChanged(peak.toFloat() / Short.MAX_VALUE)
        }
        val payload = withLock(decoderLock) { codecs.decoder.decode(samples) } ?: return
        val text = payload.toGgwavePayload()
        if (text.isNotBlank()) {
            onReceivedMessage(text)
        }
    }

    override fun startCapturing() {
        stopCapturing()
        capturedBufferCount = 0
        captureDecodeBufferSize = 0
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var ownedQueue: AudioQueueRef? = null
            try {
                val permissionGranted = withContext(Dispatchers.Main) {
                    requestMicrophonePermission()
                }
                if (!permissionGranted) {
                    throw GgwaveException("Microphone permission was denied")
                }
                configureAudioSessionForCapture()
                coroutineContext.ensureActive()

                val queue = createAudioInputQueue()
                ownedQueue = queue
                audioQueue = queue
                coroutineContext.ensureActive()
                checkAudioQueueStatus(AudioQueueStart(queue, null), "AudioQueueStart")
                awaitCancellation()
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
        val queue = audioQueue
        audioQueue = null
        disposeAudioQueue(queue)
        job?.cancel()
    }

    @Suppress("DEPRECATION")
    private suspend fun requestMicrophonePermission(): Boolean = suspendCancellableCoroutine { continuation ->
        AVAudioSession.sharedInstance().requestRecordPermission { granted ->
            continuation.resume(granted)
        }
    }

    @OptIn(BetaInteropApi::class)
    private fun configureAudioSessionForCapture() = memScoped {
        val session = AVAudioSession.sharedInstance()
        val errorPtr = alloc<ObjCObjectVar<NSError?>>()
        val categoryConfigured = session.setCategory(
            AVAudioSessionCategoryPlayAndRecord,
            withOptions = AVAudioSessionCategoryOptionDefaultToSpeaker,
            error = errorPtr.ptr,
        )
        if (!categoryConfigured) {
            throw GgwaveException(
                errorPtr.value?.localizedDescription ?: "Failed to configure the audio session",
            )
        }

        errorPtr.value = null
        if (!session.setMode(AVAudioSessionModeDefault, error = errorPtr.ptr)) {
            throw GgwaveException(
                errorPtr.value?.localizedDescription ?: "Failed to configure the audio measurement mode",
            )
        }

        errorPtr.value = null
        if (!session.setPreferredSampleRate(inputSampleRate, error = errorPtr.ptr)) {
            throw GgwaveException(
                errorPtr.value?.localizedDescription ?: "Failed to set the preferred sample rate",
            )
        }

        errorPtr.value = null
        if (!session.setActive(true, error = errorPtr.ptr)) {
            throw GgwaveException(
                errorPtr.value?.localizedDescription ?: "Failed to activate the audio session",
            )
        }
        if (!session.inputAvailable) {
            throw GgwaveException("No microphone input is available for the current audio route")
        }

        val builtInMicrophone = session.availableInputs
            ?.filterIsInstance<AVAudioSessionPortDescription>()
            ?.firstOrNull {
                it.portType == AVAudioSessionPortBuiltInMic
            }
        if (builtInMicrophone != null) {
            errorPtr.value = null
            if (!session.setPreferredInput(builtInMicrophone, error = errorPtr.ptr)) {
                throw GgwaveException(
                    errorPtr.value?.localizedDescription ?: "Failed to select the built-in microphone",
                )
            }
        }
    }

    private fun createAudioInputQueue(): AudioQueueRef = memScoped {
        val audioFormat = alloc<AudioStreamBasicDescription>().apply {
            mSampleRate = inputSampleRate
            mFormatID = kAudioFormatLinearPCM
            mFramesPerPacket = 1u
            mChannelsPerFrame = 1u
            mBytesPerFrame = 2u
            mBytesPerPacket = 2u
            mBitsPerChannel = 16u
            mReserved = 0u
            mFormatFlags = kLinearPCMFormatFlagIsSignedInteger or kAudioFormatFlagIsPacked
        }
        val queueRef = alloc<AudioQueueRefVar>()
        checkAudioQueueStatus(
            AudioQueueNewInput(
                audioFormat.ptr,
                staticCFunction(::audioQueueInputCallback),
                callbackContext.asCPointer(),
                null,
                null,
                0u,
                queueRef.ptr,
            ),
            "AudioQueueNewInput",
        )

        val queue = queueRef.value
            ?: throw GgwaveException("AudioQueueNewInput returned no queue")
        try {
            for (i in 0 until bufferNum) {
                val bufferRef = alloc<AudioQueueBufferRefVar>()
                checkAudioQueueStatus(
                    AudioQueueAllocateBuffer(queue, bufferSize.toUInt(), bufferRef.ptr),
                    "AudioQueueAllocateBuffer",
                )
                checkAudioQueueStatus(
                    AudioQueueEnqueueBuffer(queue, bufferRef.value, 0u, null),
                    "AudioQueueEnqueueBuffer",
                )
            }
        } catch (error: Throwable) {
            disposeAudioQueue(queue)
            throw error
        }
        queue
    }

    override fun startPlayback(message: String) {
        stopPlayback()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val encoded = withLock(encoderLock) {
                    codecs.encoder.encode(message.encodeToByteArray(), volume = config.volume)
                }
                if (encoded.isEmpty()) {
                    throw GgwaveException("Failed to encode message")
                }

                val convertedAudioData = encoded.toLittleEndianByteArray()
                val completeAudioData = NSMutableData()
                completeAudioData.appendData(
                    createPcm16MonoWaveHeader(convertedAudioData.size, config.sampleRate).toNSData(),
                )
                completeAudioData.appendData(convertedAudioData.toNSData())

                coroutineContext.ensureActive()
                val completion = CompletableDeferred<Throwable?>()
                val player = withContext(Dispatchers.Main) {
                    startAudioPlayer(completeAudioData, completion)
                }

                while (!completion.isCompleted) {
                    delay(PLAYBACK_STATUS_POLL_INTERVAL_MS)
                    val isPlaying = withContext(Dispatchers.Main) {
                        audioPlayer == player && player.playing
                    }
                    if (!isPlaying) {
                        completion.complete(null)
                    }
                }

                val playbackError = completion.await()
                withContext(Dispatchers.Main) {
                    releaseAudioPlayer(player)
                }
                if (playbackError == null) {
                    onPlayEnded()
                } else {
                    onPlayFailed(playbackError)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (isActive) {
                    onPlayFailed(error)
                }
            } finally {
                if (playbackJob === coroutineContext[Job]) {
                    playbackJob = null
                }
            }
        }
        playbackJob = job
        job.start()
    }

    override fun stopPlayback() {
        playbackJob?.cancel()
        playbackJob = null
        runOnMainThread(::stopAudioPlayer)
    }

    @OptIn(BetaInteropApi::class)
    private fun startAudioPlayer(
        data: NSData,
        completion: CompletableDeferred<Throwable?>,
    ): AVAudioPlayer {
        val player = memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()
            val createdPlayer = AVAudioPlayer(data = data, fileTypeHint = "wav", error = errorPtr.ptr)
            errorPtr.value?.let { throw GgwaveException(it.localizedDescription) }
            createdPlayer
        }

        audioPlayer = player
        playbackCompletion = completion
        player.setDelegate(audioPlayerDelegate)
        if (!player.play()) {
            stopAudioPlayer()
            throw GgwaveException("AVAudioPlayer failed to start playback")
        }
        return player
    }

    private fun stopAudioPlayer() {
        val player = audioPlayer
        audioPlayer = null
        playbackCompletion = null
        player?.setDelegate(null)
        player?.stop()
    }

    private fun releaseAudioPlayer(player: AVAudioPlayer) {
        if (audioPlayer != player) return
        audioPlayer = null
        playbackCompletion = null
        player.setDelegate(null)
        player.stop()
    }

    private fun runOnMainThread(action: () -> Unit) {
        if (NSThread.isMainThread) {
            action()
        } else {
            dispatch_async(dispatch_get_main_queue(), action)
        }
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
            if (audioPlayer != player) return
            val result = if (successfully) {
                null
            } else {
                GgwaveException("Audio playback did not finish successfully")
            }
            playbackCompletion?.complete(result)
        }

        override fun audioPlayerDecodeErrorDidOccur(player: AVAudioPlayer, error: NSError?) {
            if (audioPlayer != player) return
            playbackCompletion?.complete(
                GgwaveException(error?.localizedDescription ?: "Audio decode error"),
            )
        }
    }

    private companion object {
        const val CAPTURE_LEVEL_UPDATE_INTERVAL_BUFFERS = 6
        const val PLAYBACK_STATUS_POLL_INTERVAL_MS = 50L
    }
}

internal actual fun createGgwaveRuntime(config: GgwaveConfig): GgwaveRuntime {
    return IosGgwaveRuntime(config)
}
