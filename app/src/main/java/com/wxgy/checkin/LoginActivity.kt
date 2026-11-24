package com.wxgy.checkin

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wxgy.checkin.databinding.ActivityLoginBinding
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var b: ActivityLoginBinding
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.txtVersion.text = "v" + BuildConfig.VERSION_NAME
        b.edtUser.setText(Prefs.getSecret(this, Prefs.KEY_USER).orEmpty())
        b.btnLogin.setOnClickListener { doLogin() }
        b.edtPass.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                doLogin()
                true
            } else {
                false
            }
        }
    }

    private fun doLogin() {
        if (loading) return
        val user = b.edtUser.text?.toString()?.trim().orEmpty()
        val pass = b.edtPass.text?.toString().orEmpty()
        b.txtError.visibility = View.GONE
        if (user.isBlank() || pass.isBlank()) {
            showError("请输入学号和密码")
            return
        }
        setLoading(true)
        lifecycleScope.launch {
            try {
                val token = Api.casLogin(user, pass)
                Api.token = token
                Prefs.putSecret(this@LoginActivity, Prefs.KEY_USER, user)
                Prefs.putSecret(this@LoginActivity, Prefs.KEY_PASS, pass)
                Prefs.put(this@LoginActivity, Prefs.KEY_TOKEN, token)

                runCatching { Api.fetchStudent() }.getOrNull()?.let { s ->
                    Prefs.put(this@LoginActivity, Prefs.KEY_NAME, s.name)
                    Prefs.put(this@LoginActivity, Prefs.KEY_NO, s.no)
                    Prefs.put(this@LoginActivity, Prefs.KEY_GRADE, s.grade)
                }

                startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                finish()
            } catch (e: Exception) {
                showError(e.message ?: "登录失败")
            } finally {
                setLoading(false)
            }
        }
    }

    private fun setLoading(on: Boolean) {
        loading = on
        b.btnLogin.isEnabled = !on
        b.btnLogin.text = if (on) "正在登录…" else "登录"
        b.progress.visibility = if (on) View.VISIBLE else View.GONE
    }

    private fun showError(msg: String) {
        b.txtError.text = msg
        b.txtError.visibility = View.VISIBLE
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 登录页按返回直接退出应用，避免回到已经销毁的主页
        finishAffinity()
    }
}
