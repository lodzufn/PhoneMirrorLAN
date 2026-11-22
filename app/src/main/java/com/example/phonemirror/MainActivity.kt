package com.example.phonemirror

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    private var projectionResult: Pair<Int, Intent>? = null
    private lateinit var screenPermission: ActivityResultLauncher<Intent>
    private lateinit var statusText: TextView

    private val permissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val denied = results.filter { !it.value }.keys.toList()
            if (denied.isNotEmpty()) {
                statusText.text = "Не выданы разрешения: ${denied.joinToString()}"
            } else {
                statusText.text = "Разрешения выданы. Нажмите «Разрешить экран»."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        statusText = TextView(this).apply {
            textSize = 15f
            text = "PhoneMirror LAN\n" +
                    "Только своё устройство / с согласия владельца.\n" +
                    "IP телефона в Wi‑Fi: ${localIpAddress()}\n" +
                    "Статус: ожидание разрешений"
        }

        val screenButton = Button(this).apply { text = "1. Разрешить захват экрана" }
        val startButton = Button(this).apply { text = "2. Запустить трансляцию" }
        val stopButton = Button(this).apply { text = "Остановить" }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(statusText)
            addView(screenButton)
            addView(startButton)
            addView(stopButton)
        }
        setContentView(root)

        screenPermission =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK && result.data != null) {
                    projectionResult = result.resultCode to result.data!!
                    statusText.text = "Экран разрешён. IP: ${localIpAddress()}\nНажмите «Запустить трансляцию»."
                } else {
                    Toast.makeText(this, "Захват экрана не разрешён", Toast.LENGTH_SHORT).show()
                }
            }

        val needed = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val toRequest = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (toRequest.isNotEmpty()) permissionsLauncher.launch(toRequest.toTypedArray())

        screenButton.setOnClickListener {
            val manager = getSystemService(MediaProjectionManager::class.java)
            screenPermission.launch(manager.createScreenCaptureIntent())
        }

        startButton.setOnClickListener {
            val result = projectionResult
            if (result == null) {
                Toast.makeText(this, "Сначала разрешите захват экрана", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val serviceIntent = Intent(this, StreamService::class.java).apply {
                putExtra(StreamService.EXTRA_RESULT_CODE, result.first)
                putExtra(StreamService.EXTRA_DATA, result.second)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            statusText.text = "Трансляция запущена.\nОткройте на ПК: http://${localIpAddress()}:8080/\n" +
                    "В браузере нажмите «Включить звук в браузере»."
        }

        stopButton.setOnClickListener {
            startService(Intent(this, StreamService::class.java).setAction("com.example.phonemirror.STOP"))
            statusText.text = "Остановлено."
        }
    }

    private fun localIpAddress(): String {
        NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { networkInterface ->
            if (!networkInterface.isUp || networkInterface.isLoopback) return@forEach
            if (!networkInterface.name.contains("wlan", ignoreCase = true) &&
                !networkInterface.displayName.contains("wlan", ignoreCase = true)
            ) return@forEach
            networkInterface.inetAddresses.toList().forEach { address ->
                if (address is Inet4Address && !address.isLoopbackAddress) {
                    return address.hostAddress ?: "?"
                }
            }
        }
        return "192.168.x.x"
    }
}
