package com.wxgy.checkin

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Rational
import android.view.Surface
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.wxgy.checkin.databinding.ActivityCameraBinding
import java.util.concurrent.Executors

/**
 * 现场拍照页。
 *
 * 防「拉升 / 歪图」的三个关键点：
 * 1. Activity 锁竖屏，两个 UseCase 的 targetRotation 都固定为 ROTATION_0 ——
 *    竖屏应用里如果去读 display.rotation，手一歪就会拿到 90/270，导致照片横躺；
 * 2. PreviewView 用 FILL_CENTER，按比例裁剪填充而不是拉伸画面；
 * 3. 拍照走内存回调，随后按 EXIF 把像素真正转正再重新编码（见 ImageTools）。
 */
class CameraActivity : AppCompatActivity() {

    private lateinit var b: ActivityCameraBinding
    private var imageCapture: ImageCapture? = null
    private var provider: ProcessCameraProvider? = null
    private var busy = false
    private val worker = Executors.newSingleThreadExecutor()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                b.preview.post { startCamera() }
            } else {
                toast("需要相机权限才能现场拍照")
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.preview.scaleType = PreviewView.ScaleType.FILL_CENTER
        // COMPATIBLE 走 TextureView，避开部分机型 SurfaceView 变换未生效导致的预览拉伸
        b.preview.implementationMode = PreviewView.ImplementationMode.COMPATIBLE

        b.btnClose.setOnClickListener { finish() }
        b.btnShutter.setOnClickListener { shoot() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            b.preview.post { startCamera() }
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                provider = future.get()
                bindUseCases()
            } catch (e: Exception) {
                toast("相机启动失败：" + (e.message ?: "未知错误"))
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindUseCases() {
        val p = provider ?: return
        p.unbindAll()

        val preview = Preview.Builder()
            .setTargetRotation(Surface.ROTATION_0)
            .build()
        preview.setSurfaceProvider(b.preview.surfaceProvider)

        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetRotation(Surface.ROTATION_0)
            .setJpegQuality(95)
            .build()
        imageCapture = capture

        val w = b.preview.width
        val h = b.preview.height
        // ViewPort 让「取景框里看到的」和「真正拍到的」是同一块画面，
        // 避免预览是全身、上传只剩半张脸的落差。个别机型不支持就退回普通绑定。
        val group = if (w > 0 && h > 0) runCatching {
            UseCaseGroup.Builder()
                .addUseCase(preview)
                .addUseCase(capture)
                .setViewPort(
                    ViewPort.Builder(Rational(w, h), Surface.ROTATION_0)
                        .setScaleType(ViewPort.FILL_CENTER)
                        .build(),
                )
                .build()
        }.getOrNull() else null

        try {
            val selector = CameraSelector.DEFAULT_FRONT_CAMERA
            if (group != null) {
                p.bindToLifecycle(this, selector, group)
            } else {
                p.bindToLifecycle(this, selector, preview, capture)
            }
        } catch (e: Exception) {
            // 极少数设备没有前置，退回后置
            runCatching {
                val selector = CameraSelector.DEFAULT_BACK_CAMERA
                if (group != null) p.bindToLifecycle(this, selector, group)
                else p.bindToLifecycle(this, selector, preview, capture)
            }.onFailure {
                toast("相机不可用：" + (it.message ?: "未知错误"))
                finish()
            }
        }
    }

    private fun shoot() {
        if (busy) return
        val cap = imageCapture ?: return
        busy = true
        b.progress.visibility = View.VISIBLE
        b.btnShutter.isEnabled = false
        b.btnClose.isEnabled = false

        cap.takePicture(ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bytes = try {
                    val buf = image.planes[0].buffer
                    ByteArray(buf.remaining()).also { buf.get(it) }
                } catch (e: Exception) {
                    image.close()
                    fail("读取照片失败：" + (e.message ?: ""))
                    return
                }
                image.close()

                worker.execute {
                    val jpeg = runCatching { ImageTools.toUprightJpeg(bytes) }.getOrNull()
                    runOnUiThread {
                        if (jpeg == null) {
                            fail("照片处理失败，请重拍")
                        } else {
                            PhotoHolder.put(jpeg)
                            setResult(RESULT_OK)
                            finish()
                        }
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                fail("拍照失败：" + (exception.message ?: "未知错误"))
            }
        })
    }

    private fun fail(msg: String) {
        busy = false
        b.progress.visibility = View.GONE
        b.btnShutter.isEnabled = true
        b.btnClose.isEnabled = true
        toast(msg)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
    }
}
