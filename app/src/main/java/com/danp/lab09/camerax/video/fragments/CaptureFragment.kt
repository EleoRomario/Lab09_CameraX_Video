package com.danp.lab09.camerax.video.fragments

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.res.Configuration
import java.text.SimpleDateFormat
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.Navigation
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.video.*
import androidx.concurrent.futures.await
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.whenCreated
import androidx.recyclerview.widget.LinearLayoutManager
import com.danp.lab09.camera.utils.GenericListAdapter
import com.danp.lab09.camerax.video.databinding.FragmentCaptureBinding
import com.danp.lab09.camerax.video.extensions.getAspectRatio
import com.danp.lab09.camerax.video.extensions.getAspectRatioString
import com.danp.lab09.camerax.video.extensions.getNameString
import kotlinx.coroutines.*
import java.util.*
import com.danp.lab09.camerax.video.R

class CaptureFragment : Fragment() {

    private var _captureViewBinding: FragmentCaptureBinding? = null
    private val captureViewBinding get() = _captureViewBinding!!
    private val captureLiveStatus = MutableLiveData<String>()

    private val navController: NavController by lazy {
        Navigation.findNavController(requireActivity(), R.id.fragment_container)
    }

    private val cameraCapabilities = mutableListOf<CameraCapability>()

    private lateinit var videoCapture: VideoCapture<Recorder>
    private var currentRecording: Recording? = null
    private lateinit var recordingState:VideoRecordEvent

    enum class UiState {
        IDLE,       // Not recording, all UI controls are active.
        RECORDING,  // Camera is recording, only display Pause/Resume & Stop button.
        FINALIZED,  // Recording just completes, disable all RECORDING UI controls.
        RECOVERY    // For future use.
    }
    private var cameraIndex = 0
    private var qualityIndex = DEFAULT_QUALITY_IDX
    private var audioEnabled = false

    private val mainThreadExecutor by lazy { ContextCompat.getMainExecutor(requireContext()) }
    private var enumerationDeferred:Deferred<Unit>? = null

    private suspend fun bindCaptureUsecase() {
        val cameraProvider = ProcessCameraProvider.getInstance(requireContext()).await()

        val cameraSelector = getCameraSelector(cameraIndex)


        val quality = cameraCapabilities[cameraIndex].qualities[qualityIndex]
        val qualitySelector = QualitySelector.from(quality)

        captureViewBinding.previewView.updateLayoutParams<ConstraintLayout.LayoutParams> {
            val orientation = this@CaptureFragment.resources.configuration.orientation
            dimensionRatio = quality.getAspectRatioString(quality,
                (orientation == Configuration.ORIENTATION_PORTRAIT))
        }

        val preview = Preview.Builder()
            .setTargetAspectRatio(quality.getAspectRatio(quality))
            .build().apply {
                setSurfaceProvider(captureViewBinding.previewView.surfaceProvider)
            }


        val recorder = Recorder.Builder()
            .setQualitySelector(qualitySelector)
            .build()
        videoCapture = VideoCapture.withOutput(recorder)

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                viewLifecycleOwner,
                cameraSelector,
                videoCapture,
                preview
            )
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
            resetUIandState("bindToLifecycle failed: $exc")
        }
        enableUI(true)
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val name = "CameraX-recording-" +
            SimpleDateFormat(FILENAME_FORMAT, Locale.US)
                .format(System.currentTimeMillis()) + ".mp4"
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
        }
        val mediaStoreOutput = MediaStoreOutputOptions.Builder(
            requireActivity().contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()

        currentRecording = videoCapture.output
               .prepareRecording(requireActivity(), mediaStoreOutput)
               .apply { if (audioEnabled) withAudioEnabled() }
               .start(mainThreadExecutor, captureListener)

        Log.i(TAG, "Recording started")
    }

    private val captureListener = Consumer<VideoRecordEvent> { event ->
        if (event !is VideoRecordEvent.Status)
            recordingState = event

        updateUI(event)

        if (event is VideoRecordEvent.Finalize) {
            lifecycleScope.launch {
                navController.navigate(
                    CaptureFragmentDirections.actionCaptureToVideoViewer(
                        event.outputResults.outputUri
                    )
                )
            }
        }
    }

    private fun getCameraSelector(idx: Int) : CameraSelector {
        if (cameraCapabilities.size == 0) {
            Log.i(TAG, "Error: This device does not have any camera, bailing out")
            requireActivity().finish()
        }
        return (cameraCapabilities[idx % cameraCapabilities.size].camSelector)
    }

    data class CameraCapability(val camSelector: CameraSelector, val qualities:List<Quality>)
    init {
        enumerationDeferred = lifecycleScope.async {
            whenCreated {
                val provider = ProcessCameraProvider.getInstance(requireContext()).await()

                provider.unbindAll()
                for (camSelector in arrayOf(
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    CameraSelector.DEFAULT_FRONT_CAMERA
                )) {
                    try {
                        if (provider.hasCamera(camSelector)) {
                            val camera = provider.bindToLifecycle(requireActivity(), camSelector)
                            QualitySelector
                                .getSupportedQualities(camera.cameraInfo)
                                .filter { quality ->
                                    listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD)
                                        .contains(quality)
                                }.also {
                                    cameraCapabilities.add(CameraCapability(camSelector, it))
                                }
                        }
                    } catch (exc: java.lang.Exception) {
                        Log.e(TAG, "Camera Face $camSelector is not supported")
                    }
                }
            }
        }
    }

    private fun initCameraFragment() {
        initializeUI()
        viewLifecycleOwner.lifecycleScope.launch {
            if (enumerationDeferred != null) {
                enumerationDeferred!!.await()
                enumerationDeferred = null
            }
            initializeQualitySectionsUI()

            bindCaptureUsecase()
        }
    }

    @SuppressLint("ClickableViewAccessibility", "MissingPermission")
    private fun initializeUI() {
        captureViewBinding.cameraButton.apply {
            setOnClickListener {
                cameraIndex = (cameraIndex + 1) % cameraCapabilities.size
                qualityIndex = DEFAULT_QUALITY_IDX
                initializeQualitySectionsUI()
                enableUI(false)
                viewLifecycleOwner.lifecycleScope.launch {
                    bindCaptureUsecase()
                }
            }
            isEnabled = false
        }

        captureViewBinding.audioSelection.isChecked = audioEnabled
        captureViewBinding.audioSelection.setOnClickListener {
            audioEnabled = captureViewBinding.audioSelection.isChecked
        }

        captureViewBinding.captureButton.apply {
            setOnClickListener {
                if (!this@CaptureFragment::recordingState.isInitialized ||
                    recordingState is VideoRecordEvent.Finalize)
                {
                    enableUI(false)  // Our eventListener will turn on the Recording UI.
                    startRecording()
                } else {
                    when (recordingState) {
                        is VideoRecordEvent.Start -> {
                            currentRecording?.pause()
                            captureViewBinding.stopButton.visibility = View.VISIBLE
                        }
                        is VideoRecordEvent.Pause -> currentRecording?.resume()
                        is VideoRecordEvent.Resume -> currentRecording?.pause()
                        else -> throw IllegalStateException("recordingState in unknown state")
                    }
                }
            }
            isEnabled = false
        }

        captureViewBinding.stopButton.apply {
            setOnClickListener {
                // stopping: hide it after getting a click before we go to viewing fragment
                captureViewBinding.stopButton.visibility = View.INVISIBLE
                if (currentRecording == null || recordingState is VideoRecordEvent.Finalize) {
                    return@setOnClickListener
                }

                val recording = currentRecording
                if (recording != null) {
                    recording.stop()
                    currentRecording = null
                }
                captureViewBinding.captureButton.setImageResource(R.drawable.ic_start)
            }
            // ensure the stop button is initialized disabled & invisible
            visibility = View.INVISIBLE
            isEnabled = false
        }

        captureLiveStatus.observe(viewLifecycleOwner) {
            captureViewBinding.captureStatus.apply {
                post { text = it }
            }
        }
        captureLiveStatus.value = getString(R.string.Idle)
    }

    private fun updateUI(event: VideoRecordEvent) {
        val state = if (event is VideoRecordEvent.Status) recordingState.getNameString()
                    else event.getNameString()
        when (event) {
                is VideoRecordEvent.Status -> {
                    // placeholder: we update the UI with new status after this when() block,
                    // nothing needs to do here.
                }
                is VideoRecordEvent.Start -> {
                    showUI(UiState.RECORDING, event.getNameString())
                }
                is VideoRecordEvent.Finalize-> {
                    showUI(UiState.FINALIZED, event.getNameString())
                }
                is VideoRecordEvent.Pause -> {
                    captureViewBinding.captureButton.setImageResource(R.drawable.ic_resume)
                }
                is VideoRecordEvent.Resume -> {
                    captureViewBinding.captureButton.setImageResource(R.drawable.ic_pause)
                }
        }

        val stats = event.recordingStats
        val size = stats.numBytesRecorded / 1000
        val time = java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(stats.recordedDurationNanos)
        var text = "${state}: recorded ${size}KB, in ${time}second"
        if(event is VideoRecordEvent.Finalize)
            text = "${text}\nFile saved to: ${event.outputResults.outputUri}"

        captureLiveStatus.value = text
        Log.i(TAG, "recording event: $text")
    }

    private fun enableUI(enable: Boolean) {
        arrayOf(captureViewBinding.cameraButton,
                captureViewBinding.captureButton,
                captureViewBinding.stopButton,
                captureViewBinding.audioSelection,
                captureViewBinding.qualitySelection).forEach {
                    it.isEnabled = enable
        }
        if (cameraCapabilities.size <= 1) {
            captureViewBinding.cameraButton.isEnabled = false
        }
        if (cameraCapabilities[cameraIndex].qualities.size <= 1) {
            captureViewBinding.qualitySelection.apply { isEnabled = false }
        }
    }

    private fun showUI(state: UiState, status:String = "idle") {
        captureViewBinding.let {
            when(state) {
                UiState.IDLE -> {
                    it.captureButton.setImageResource(R.drawable.ic_start)
                    it.stopButton.visibility = View.INVISIBLE

                    it.cameraButton.visibility= View.VISIBLE
                    it.audioSelection.visibility = View.VISIBLE
                    it.qualitySelection.visibility=View.VISIBLE
                }
                UiState.RECORDING -> {
                    it.cameraButton.visibility = View.INVISIBLE
                    it.audioSelection.visibility = View.INVISIBLE
                    it.qualitySelection.visibility = View.INVISIBLE

                    it.captureButton.setImageResource(R.drawable.ic_pause)
                    it.captureButton.isEnabled = true
                    it.stopButton.visibility = View.VISIBLE
                    it.stopButton.isEnabled = true
                }
                UiState.FINALIZED -> {
                    it.captureButton.setImageResource(R.drawable.ic_start)
                    it.stopButton.visibility = View.INVISIBLE
                }
                else -> {
                    val errorMsg = "Error: showUI($state) is not supported"
                    Log.e(TAG, errorMsg)
                    return
                }
            }
            it.captureStatus.text = status
        }
    }

    private fun resetUIandState(reason: String) {
        enableUI(true)
        showUI(UiState.IDLE, reason)

        cameraIndex = 0
        qualityIndex = DEFAULT_QUALITY_IDX
        audioEnabled = false
        captureViewBinding.audioSelection.isChecked = audioEnabled
        initializeQualitySectionsUI()
    }

    private fun initializeQualitySectionsUI() {
        val selectorStrings = cameraCapabilities[cameraIndex].qualities.map {
            it.getNameString()
        }
        captureViewBinding.qualitySelection.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = GenericListAdapter(
                selectorStrings,
                itemLayoutId = R.layout.video_quality_item
            ) { holderView, qcString, position ->

                holderView.apply {
                    findViewById<TextView>(R.id.qualityTextView)?.text = qcString
                    isSelected = (position == qualityIndex)
                }

                holderView.setOnClickListener { view ->
                    if (qualityIndex == position) return@setOnClickListener

                    captureViewBinding.qualitySelection.let {
                        it.findViewHolderForAdapterPosition(qualityIndex)
                            ?.itemView
                            ?.isSelected = false
                    }
                    view.isSelected = true
                    qualityIndex = position

                    enableUI(false)
                    viewLifecycleOwner.lifecycleScope.launch {
                        bindCaptureUsecase()
                    }
                }
            }
            isEnabled = false
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _captureViewBinding = FragmentCaptureBinding.inflate(inflater, container, false)
        return captureViewBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initCameraFragment()
    }
    override fun onDestroyView() {
        _captureViewBinding = null
        super.onDestroyView()
    }

    companion object {
        const val DEFAULT_QUALITY_IDX = 0
        val TAG:String = CaptureFragment::class.java.simpleName
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
    }
}