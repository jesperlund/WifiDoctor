package io.github.jesperlund.wifidoctor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import io.github.jesperlund.wifidoctor.platform.EnvironmentMonitor
import io.github.jesperlund.wifidoctor.ui.navigation.WifiDoctorRoot
import io.github.jesperlund.wifidoctor.ui.theme.WifiDoctorTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var environmentMonitor: EnvironmentMonitor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WifiDoctorTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    WifiDoctorRoot(onPermissionsChanged = { environmentMonitor.refresh() })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Permissions, location or Wi-Fi may have changed while we were in the background.
        environmentMonitor.refresh()
    }
}
