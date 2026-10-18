package io.github.moreflow.ggwave.internal

import io.github.moreflow.ggwave.GgwaveConfig
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
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
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
import platform.AVFAudio.inputAvailable
import platform.AVFAudio.setActive
import platform.AVFAudio.setPreferredSampleRate
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
    val input = inUserData?.asStableRef<AudioQueueInput>()?.get() ?: return
    if (!input.isActive) return
    val queue = inAQ ?: return
    val buffer = inBuffer ?: return

    try {
        val audioData = buffer.pointed.mAudioData!!.reinterpret<ByteVar>()
        val audioDataSize = buffer.pointed.mAudioDataByteSize.toInt()
        input.onBytes(audioData.readBytes(audioDataSize))
        val status = AudioQueueEnqueueBuffer(queue, buffer, 0u, null)
        if (status != 0) {
            input.fail(GgwaveException("AudioQueueEnqueueBuffer failed: $status"))
        }
    } catch (error: Throwable) {
        input.fail(error)
    }
}

/** An AudioQueue input queue together with the callback target it was created with. */
@OptIn(ExperimentalForeignApi::class)
private class CaptureQueue(
    val queue: AudioQueueRef,
    private val input: StableRef<AudioQueueInput>,
) {
    fun dispose() {
        AudioQueueStop(queue, true)
        AudioQueueDispose(queue, true)
        input.dispose()
    }
}

@OptIn(ExperimentalForeignApi::class)
internal class IosGgwaveRuntime(
    config: GgwaveConfig,
) : BaseGgwaveRuntime(config) {
    private val inputSampleRate = config.sampleRate.toDouble()
    private val captureStream = ActiveStream(CaptureQueue::dispose)
    private val playbackStream = ActiveStream<Nothing>()

    // Confined to the main thread.
    private var audioPlayer: AVAudioPlayer? = null
    private var playbackCompletion: CompletableDeferred<Throwable?>? = null
    private val audioPlayerDelegate = AVAudioPlayerDelegate()

    override fun startCapturing() {
        stopCapturing()
        captureStream.start(scope, onFailure = { listener.onCaptureFailed(it) }) {
            val permissionGranted = withContext(Dispatchers.Main) {
                requestMicrophonePermission()
            }
            if (!permissionGranted) {
                throw GgwaveException("Microphone permission was denied")
            }
            configureAudioSessionForCapture()
            ensureActive()

            val input = AudioQueueInput(
                chunkByteSize = config.captureBufferSize,
                decoder = CaptureDecoder(codecs, listener),
                job = coroutineContext.job,
                onFailure = { listener.onCaptureFailed(it) },
            )
            val capture = openCaptureQueue(input)
            captureStream.attach(capture)
            checkAudioQueueStatus(AudioQueueStart(capture.queue, null), "AudioQueueStart")
            awaitCancellation()
        }
    }

    override fun stopCapturing() {
        captureStream.stop()
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

    private fun openCaptureQueue(input: AudioQueueInput): CaptureQueue {
        val inputRef = StableRef.create(input)
        val queue = try {
            createAudioInputQueue(inputRef.asCPointer())
        } catch (error: Throwable) {
            inputRef.dispose()
            throw error
        }
        return CaptureQueue(queue, inputRef)
    }

    private fun createAudioInputQueue(userData: COpaquePointer): AudioQueueRef = memScoped {
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
                userData,
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
            repeat(CAPTURE_BUFFER_COUNT) {
                val bufferRef = alloc<AudioQueueBufferRefVar>()
                checkAudioQueueStatus(
                    AudioQueueAllocateBuffer(queue, config.captureBufferSize.toUInt(), bufferRef.ptr),
                    "AudioQueueAllocateBuffer",
                )
                checkAudioQueueStatus(
                    AudioQueueEnqueueBuffer(queue, bufferRef.value, 0u, null),
                    "AudioQueueEnqueueBuffer",
                )
            }
        } catch (error: Throwable) {
            AudioQueueStop(queue, true)
            AudioQueueDispose(queue, true)
            throw error
        }
        queue
    }

    override fun startPlayback(message: String) {
        stopPlayback()
        playbackStream.start(scope, onFailure = { listener.onPlayFailed(it) }) {
            val convertedAudioData = codecs.encode(message, config.volume).toLittleEndianByteArray()
            val completeAudioData = NSMutableData()
            completeAudioData.appendData(
                createPcm16MonoWaveHeader(convertedAudioData.size, config.sampleRate).toNSData(),
            )
            completeAudioData.appendData(convertedAudioData.toNSData())

            ensureActive()
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
            if (playbackError != null) {
                throw playbackError
            }
            listener.onPlayEnded()
        }
    }

    override fun stopPlayback() {
        playbackStream.stop()
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
        stopAudioPlayer()
    }

    private fun runOnMainThread(action: () -> Unit) {
        if (NSThread.isMainThread) {
            action()
        } else {
            dispatch_async(dispatch_get_main_queue(), action)
        }
    }

    private fun checkAudioQueueStatus(status: OSStatus, operation: String) {
        if (status != 0) {
            throw GgwaveException("$operation failed: $status")
        }
    }

    @OptIn(BetaInteropApi::class)
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
        const val CAPTURE_BUFFER_COUNT = 24
        const val PLAYBACK_STATUS_POLL_INTERVAL_MS = 50L
    }
}

internal actual fun createGgwaveRuntime(config: GgwaveConfig): GgwaveRuntime {
    return IosGgwaveRuntime(config)
}
