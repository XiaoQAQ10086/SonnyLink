package com.sonnyapp

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.sonnyapp.theme.SonnyAppTheme
import com.sonnyapp.ui.AppRoot

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 取景时不要息屏
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 刷新率：平常跑屏幕上限，取景时降到 60Hz（见 RefreshRate 的说明）。
        RefreshRate.attach(window)

        // Android 16/17 的局域网访问权限。
        // 在 Android 17 上它对应 appop ACCESS_LOCAL_NETWORK；
        // 如果不是运行时权限，这次请求会静默失败，无副作用。
        val wanted = ArrayList<String>()
        try {
            if (checkSelfPermission(PERM_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) {
                wanted.add(PERM_LOCAL_NETWORK)
            }
        } catch (e: Exception) {
            // ignore
        }
        // Android 13+ 读 Wi-Fi SSID 需要它（声明了 neverForLocation，不涉及定位）
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                if (checkSelfPermission(PERM_NEARBY_WIFI) != PackageManager.PERMISSION_GRANTED) {
                    wanted.add(PERM_NEARBY_WIFI)
                }
            } catch (e: Exception) {
                // ignore
            }
        }
        if (wanted.isNotEmpty()) {
            try {
                requestPermissions(wanted.toTypedArray(), REQ_LOCAL_NETWORK)
            } catch (e: Exception) {
                // 不是运行时权限就静默失败
            }
        }

        setContent {
            SonnyAppTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot()
                }
            }
        }
    }

    companion object {
        private const val PERM_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
        private const val PERM_NEARBY_WIFI = "android.permission.NEARBY_WIFI_DEVICES"
        private const val REQ_LOCAL_NETWORK = 1001
    }
}
