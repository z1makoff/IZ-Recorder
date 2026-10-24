package com.izrecorder.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {

    /** Увеличивается при каждом возвращении в приложение — чтобы перепроверить разрешения. */
    private val resumeTick = mutableIntStateOf(0)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { resumeTick.intValue++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
        }
        setContent {
            IZTheme {
                var detailId by rememberSaveable { mutableStateOf<String?>(null) }
                var showSettings by rememberSaveable { mutableStateOf(false) }
                val tick = resumeTick.intValue
                val requestPermissions = {
                    permissionLauncher.launch(
                        arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
                    )
                }
                when {
                    detailId != null -> {
                        BackHandler { detailId = null }
                        DetailScreen(detailId!!, onBack = { detailId = null })
                    }
                    showSettings -> {
                        BackHandler { showSettings = false }
                        SettingsScreen(tick, requestPermissions, onBack = { showSettings = false })
                    }
                    else -> HomeScreen(
                        tick = tick,
                        onOpen = { detailId = it },
                        onSettings = { showSettings = true },
                        requestPermissions = requestPermissions,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }
}
