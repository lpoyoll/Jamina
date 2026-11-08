package com.jamina

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.jamina.ui.JaminaRoot
import com.jamina.ui.theme.JaminaTheme

class MainActivity : ComponentActivity() {
    private val container get() = (application as JaminaApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { JaminaTheme { JaminaRoot() } }
    }

    override fun onStart() {
        super.onStart()
        container.connection.start()
        container.scanner.refreshEnvironment()
    }

    override fun onStop() {
        super.onStop()
        container.connection.stop()
    }
}
