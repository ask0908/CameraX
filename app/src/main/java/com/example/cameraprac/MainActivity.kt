package com.example.cameraprac

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.core.content.PermissionChecker.PERMISSION_GRANTED
import com.example.cameraprac.databinding.ActivityMainBinding
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// 이미지의 광도를 분석하는 클래스의 주 생성자에 넣을 typealias
typealias LumaListener = (luma: Double) -> Unit

/* CameraX 코드랩(https://developer.android.com/codelabs/camerax-getting-started#7)
* - 카메라로 사진 찍기
* - 영상 촬영
* - 영상 촬영하면서 캡쳐
* - 사진, 비디오 파일을 지정된 경로에 저장(파일명은 타임스탬프) */
class MainActivity : AppCompatActivity() {

    companion object {
        private val TAG = this.javaClass.simpleName
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS =
            mutableListOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }

    private lateinit var viewBinding: ActivityMainBinding

    // 사진 촬영, 음성이 들어가게 영상 촬영할 때 필요한 객체
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    // 사진 촬영 + 토스트 출력, 영상 촬영 + 버튼 글자 변경 + 토스트 출력 등 동시에 해야 하는 작업이 많기 때문에 선언한 ExecutorService
    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        // Set up the listeners for take photo and video capture buttons
        viewBinding.imageCaptureButton.setOnClickListener { takePhoto() }
        viewBinding.videoCaptureButton.setOnClickListener { captureVideo() }

        /* Executors : 쓰레드 풀 + Queue로 구성된 클래스. task들은 Queue에 들어가고 순차적으로 쓰레드에 할당된다. 쓰레드가 없으면 Queue에서 대기한다
        * 쓰레드를 직접적으로 다루는 최상위 API. 쓰레드 풀을 만들어 작업들을 병렬 처리할 때 사용한다 -> 개발자가 직접 비용이 큰 쓰레드를 만들 필요가 없다 */
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    /* 사용자에게 권한 요청한 다음에 수행할 작업을 정의한 메서드 */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                // 카메라 권한이 부여됐다면 호출할 카메라 함수
                startCamera()
            } else {
                // 카메라 권한이 부여되지 않으면 토스트 출력 후 앱 종료
                Toast.makeText(this, "권한이 승낙되지 않아서 앱이 종료됨", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun takePhoto() {
        // Get a stable reference of the modifiable image capture use case
        // ImageCapture 사용 사례에 대한 참조 얻음
        // 이미지 캡쳐가 설정되기 전에 사진 버튼을 탭하면 null이 된다. 그래서 return이 없으면 앱이 null일 때 충돌이 발생한다
        val imageCapture = imageCapture ?: return

        // Create time stamped name and MediaStore entry.
        // 이미지를 보관할 MediaStore 컨텐츠 값 생성. 고유값을 위해 타임스탬프 사용
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                // 2번 인자로 넣은 경로에 사진이 저장된다
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
            }
        }

        // Create output options object which contains file + metadata
        // OutputFileOptions : 내가 원하는 출력물을 지정할 수 있는 객체
        // 다른 앱에서 출력을 표시할 수 있게 MediaStore에 출력을 지정해야 하므로 MediaStore 항목 추가(134번 줄에서 사용)
        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(
                contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            )
            .build()

        // Set up image capture listener, which is triggered after photo has been taken
        // 사진 촬영 후 트리거되는 이미지 캡쳐 리스너 설정
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),    // 안드로이드 파이(28)부터 Context.getMainExecutor()로 메인 쓰레드에서 동작하는 Executor를 가져올 수 있다
            object : ImageCapture.OnImageSavedCallback {
                // 이미지 캡쳐 실패, 이미지 캡쳐 저장에 실패한 경우 호출
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "사진 촬영 실패!! : ${exc.message}", exc)
                }

                // 이미지 캡쳐 성공 시 파일에 이미지를 저장하고 토스트, 로그 출력
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val msg = "사진 촬영 성공 : ${output.savedUri}"
                    Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                    Log.e(TAG, msg)
                }
            }
        )
    }

    // Implements VideoCapture use case, including start and stop capturing.
    private fun captureVideo() {
        // VideoCapture 유스케이스 생성 확인. 생성되지 않았다면 어떤 처리도 하지 않음
        val videoCapture = this.videoCapture ?: return

        // CameraX가 요청받은 작업을 끝낼 때까지 UI 비활성화
        // VideoRecordListener 안에서 재활성화됨
        viewBinding.videoCaptureButton.isEnabled = false

        // 진행 중인 녹음이 있으면 중지해서 현재 녹음 해제
        val curRecording = recording
        if (curRecording != null) {
            // Stop the current recording session.
            curRecording.stop()
            recording = null
            return
        }

        // create and start a new recording session
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())

        // 녹화한 영상 파일이 앱에서 사용할 준비가 되면 알림을 받는다
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraX-Video")
            }
        }

        // 외부 컨텐츠 옵션을 써서 MediaStoreOutputOptions.Builder 생성성
        val mediaStoreOutputOptions = MediaStoreOutputOptions
            .Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()
        recording = videoCapture.output
            .prepareRecording(this, mediaStoreOutputOptions)
            .apply {
                if (PermissionChecker.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.RECORD_AUDIO
                    ) == PERMISSION_GRANTED
                ) {
                    // 소리가 녹음되게 오디오 활성화
                    withAudioEnabled()
                }
            }
            // 새 녹음을 시작하고 lambda VideoRecordEvent 리스너 등록
            .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                when (recordEvent) {
                    // 카메라 장치에서 영상 녹화가 시작되면 "캡쳐 시작" -> "캡쳐 중지"로 글자 변환
                    is VideoRecordEvent.Start -> {
                        viewBinding.videoCaptureButton.apply {
                            text = getString(R.string.stop_capture)
                            isEnabled = true
                        }
                    }
                    // 녹음이 끝나면 알림으로 사용자에게 알리고 "캡쳐 중지" -> "캡쳐 시작"으로 글자 변환
                    is VideoRecordEvent.Finalize -> {
                        if (!recordEvent.hasError()) {
                            val msg = "영상 녹화 성공 : ${recordEvent.outputResults.outputUri}"
                            Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                            Log.e(TAG, msg)
                        } else {
                            recording?.close()
                            recording = null
                            Log.e(TAG, "영상 녹화 에러 : ${recordEvent.error}")
                        }
                        viewBinding.videoCaptureButton.apply {
                            text = getString(R.string.start_capture)
                            isEnabled = true
                        }
                    }
                }
            }
    }

    private fun startCamera() {
        // ProcessCameraProvider 인스턴스 생성. 카메라 생명주기를 생명주기 소유자에게 바인딩할 때 사용
        // CameraX는 생명주기를 인식하기 때문에 카메라를 열고 닫는 작업이 불필요하다
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        // cameraProviderFuture에 Runnable을 인수로 하는 리스너 추가
        // 2번 인자(ContextCompat.getMainExecutor()) : 메인 쓰레드에서 실행되는 Executor 리턴
        cameraProviderFuture.addListener(Runnable {
            // Used to bind the lifecycle of cameras to the lifecycle owner
            // 앱 프로세스에서 카메라 생명주기를 LifecycleOwner에 바인딩할 때 사용
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview 인스턴스 초기화. 그 위에 빌드를 호출하고 뷰파인더에서 surface provider를 가져온 다음 Preview에서 설정한다
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
                }

            // 영상 녹화에 사용할 인스턴스 초기화
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(
                        Quality.HIGHEST,
                        // Quality.HIGHEST가 imageCapture 유스케이스에서 지원되지 않는 경우 CameraX가 지원되는 해상도를 선택할 수 있게 한다
                        FallbackStrategy.higherQualityOrLowerThan(Quality.SD)
                    )
                )
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            /* 미리보기에 필요한 코드 추가 */
            imageCapture = ImageCapture.Builder().build()

            /* 이미지의 평균 광도를 분석하는 LuminosityAnalyzer 인스턴스 생성
            * 평균 광도는 특수한 경우 아니면 필요없을 듯 */
//            val imageAnalyzer = ImageAnalysis.Builder()
//                .build()
//                .also {
//                    it.setAnalyzer(cameraExecutor, LuminosityAnalyzer { luma ->
//                        Log.e(TAG, "평균 밝기 : $luma") // 초에 2~3개 꼴로 로그가 찍힌다
//                    })
//                }

            // CameraSelector 인스턴스를 만들고 DEFAULT_BACK_CAMERA(후면 카메라) 선택
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                /* 미리보기에 필요한 imageCapture, LuminosityAnalyzer 인스턴스를 4, 5번 인자로 꽂음 */
                // Bind use cases to camera
                // Preview + imageCapture + videoCapture + imageAnalysis 조합은 앱이 에러를 내면서 죽기 때문에 imageAnalysis는 뺀다. 아직 지원하지 않는다
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture, videoCapture
                )
            } catch (exc: Exception) {
                // 앱이 더 이상 포커스에 있지 않은 경우 등 에러 발생 시 catch 블록에 래핑
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    /* LuminosityAnalyzer : 이미지의 평균 광도를 분석하는 클래스 */
    private class LuminosityAnalyzer(private val listener: LumaListener) : ImageAnalysis.Analyzer {
        override fun analyze(image: ImageProxy) {
            val buffer = image.planes[0].buffer
            val data = buffer.toByteArray()
            val pixels = data.map { it.toInt() and 0xFF }
            val luma = pixels.average()

            listener(luma)

            image.close()
        }

        private fun ByteBuffer.toByteArray(): ByteArray {
            rewind()    // Rewind the buffer to zero
            val data = ByteArray(remaining())   // 현재 위치(0)에서 한계까지 읽어들일 수 있는 데이터의 개수를 ByteArray로 만든다
            get(data)   // Copy the buffer into a byte array(버퍼를 바이트 배열에 복사)
            return data // Return the byte array
        }
    }
}