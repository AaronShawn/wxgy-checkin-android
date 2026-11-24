package com.wxgy.checkin

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wxgy.checkin.databinding.ActivityMainBinding
import com.wxgy.checkin.databinding.DialogBusyBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var busyBinding: DialogBusyBinding
    private var busyDialog: AlertDialog? = null
    private var submitting = false
    private var today: Today? = null

    private val cameraLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            PhotoHolder.take()?.let { photo ->
                if (result.resultCode == RESULT_OK) submit(photo)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        busyBinding = DialogBusyBinding.inflate(layoutInflater)

        Api.token = Prefs.get(this, Prefs.KEY_TOKEN).orEmpty()
        if (Api.token.isBlank()) {
            goLogin()
            return
        }
        val ctx = applicationContext
        Api.onToken = { Prefs.put(ctx, Prefs.KEY_TOKEN, it) }
        Api.relogin = {
            val u = Prefs.getSecret(ctx, Prefs.KEY_USER)
            val p = Prefs.getSecret(ctx, Prefs.KEY_PASS)
            if (u.isNullOrBlank() || p.isNullOrBlank()) null else u to p
        }

        b.txtWho.text = Prefs.get(this, Prefs.KEY_NAME).orEmpty().ifBlank { "同学" }
        b.txtNo.text = Prefs.get(this, Prefs.KEY_NO).orEmpty().ifBlank { "—" }
        b.txtGrade.text = Prefs.get(this, Prefs.KEY_GRADE).orEmpty().ifBlank { "—" }
        updateDate()

        b.btnCheckIn.setOnClickListener { onCheckIn() }
        b.btnRefresh.setOnClickListener { refresh(manual = true) }
        b.btnLogout.setOnClickListener { confirmLogout() }
    }

    override fun onResume() {
        super.onResume()
        if (!Api.token.isBlank() && !submitting) refresh(manual = false)
    }

    // ------------------------------------------------------------ 状态

    private fun goLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    private fun refresh(manual: Boolean) {
        if (Api.token.isBlank()) {
            goLogin()
            return
        }
        if (manual) b.btnRefresh.isEnabled = false
        lifecycleScope.launch {
            try {
                val s = Api.fetchStudent()
                Prefs.put(this@MainActivity, Prefs.KEY_NAME, s.name)
                Prefs.put(this@MainActivity, Prefs.KEY_NO, s.no)
                Prefs.put(this@MainActivity, Prefs.KEY_GRADE, s.grade)
                b.txtWho.text = s.name.ifBlank { "同学" }
                b.txtNo.text = s.no.ifBlank { "—" }
                b.txtGrade.text = s.grade.ifBlank { "—" }
                render(Api.todayStatus())
            } catch (e: Exception) {
                val msg = e.message ?: "刷新失败"
                if (msg.contains("登录已失效") || msg.contains("重新登录")) {
                    Api.logout()
                    Prefs.put(this@MainActivity, Prefs.KEY_TOKEN, null)
                    goLogin()
                    return@launch
                }
                if (manual) toast(msg)
            } finally {
                b.btnRefresh.isEnabled = true
            }
        }
    }

    private fun render(t: Today?) {
        today = t
        val now = Date()
        updateDate()

        val (title, sub) = when {
            t?.done == true -> "已打卡" to "今天已经完成晚查寝打卡"
            t?.leave == true -> "今日请假" to "请假期间无需打卡"
            t?.free == true -> "免查寝" to "今天不用打卡"
            else -> "未打卡" to "打卡窗口 21:30 - 24:00"
        }
        b.txtStatus.text = title
        b.txtStatusSub.text = sub

        val done = t?.done == true || t?.leave == true || t?.free == true
        b.btnCheckIn.isEnabled = !done
        b.btnCheckIn.text = if (t?.done == true) "今日已完成" else "现场拍照打卡"
        b.txtHint.text = if (done) {
            "今天不用再打卡了"
        } else {
            "现在 " + SimpleDateFormat("HH:mm", Locale.CHINA).format(now) + " · 到点后服务端才会收单"
        }
    }

    private fun updateDate() {
        b.txtDate.text = SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(Date())
    }

    // ------------------------------------------------------------ 打卡

    private fun onCheckIn() {
        if (submitting) return
        if (Api.token.isBlank()) {
            goLogin()
            return
        }
        PhotoHolder.clear()
        cameraLauncher.launch(Intent(this, CameraActivity::class.java))
    }

    private fun submit(photo: ByteArray) {
        submitting = true
        showBusy("正在准备打卡…")
        lifecycleScope.launch {
            val result = try {
                Api.checkIn(photo) { step -> runOnUiThread { busyBinding.txtBusy.text = step } }
            } catch (e: Exception) {
                CheckResult(false, e.message ?: "打卡失败")
            } finally {
                photo.fill(0)
            }
            hideBusy()
            submitting = false
            showResult(result)
            refresh(manual = false)
        }
    }

    private fun showBusy(text: String) {
        busyBinding.txtBusy.text = text
        if (busyDialog?.isShowing != true) {
            if (busyDialog == null) {
                busyDialog = AlertDialog.Builder(this)
                    .setView(busyBinding.root)
                    .setCancelable(false)
                    .create()
                    .also { it.window?.setBackgroundDrawableResource(R.drawable.bg_dialog) }
            }
            busyDialog?.show()
        }
    }

    private fun hideBusy() {
        busyDialog?.dismiss()
    }

    private fun showResult(r: CheckResult) {
        val title = if (r.ok) "打卡成功" else "打卡未完成"
        val extra = if (!r.ok && r.msg.contains("时间段")) "\n\n提示：学校只在 21:30 之后接收打卡。" else ""
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(r.msg + extra)
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("退出登录")
            .setMessage("会清除本机保存的账号、密码和登录态。")
            .setNegativeButton("取消", null)
            .setPositiveButton("退出") { _, _ ->
                Api.logout()
                Prefs.clearAll(this)
                goLogin()
            }
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
