package com.phoenix.recover

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModelProvider
import com.phoenix.recover.ui.PhoenixApp

class MainActivity : ComponentActivity() {

    private lateinit var vm: RecoverViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        vm = ViewModelProvider(this)[RecoverViewModel::class.java]
        setContent {
            PhoenixApp(vm)
        }
    }
}
