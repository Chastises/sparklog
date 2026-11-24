package com.example.sparklog

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import com.example.sparklog.ui.SparkLogRoot
import com.example.sparklog.ui.SparkLogTheme
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // draws behind the system bars and picks dark/light status bar icons
        val openToday = intent?.getBooleanExtra("openToday", false) == true
        val openTab = intent?.getStringExtra("openTab")
        setContent {
            val askNotifications = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { /* nothing to do; the reminder worker checks the permission itself */ }
            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            SparkLogTheme {
                Surface {
                    SparkLogRoot(initialDay = if (openToday) LocalDate.now() else null, initialTab = openTab)
                }
            }
        }
    }
}
