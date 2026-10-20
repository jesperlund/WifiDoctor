package io.github.jesperlund.wifidoctor.ui.permission

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.jesperlund.wifidoctor.platform.AppPermissions

fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

fun Context.hasPermissions(permissions: List<String>): Boolean =
    permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/**
 * Explains why WifiDoctor asks for location / nearby-devices access before the system dialog
 * appears, and handles denial (including "don't ask again").
 */
@Composable
fun PermissionRationaleScreen(onFinished: (granted: Boolean) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val permissions = AppPermissions.wifiPermissions()
    var denied by rememberSaveable { mutableStateOf(false) }
    var permanentlyDenied by rememberSaveable { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = permissions.all { result[it] == true || context.hasPermissions(listOf(it)) }
        if (granted) {
            onFinished(true)
        } else {
            denied = true
            val activity = context.findActivity()
            // After a denial, no rationale means the system will not show the dialog again.
            permanentlyDenied = activity != null && permissions.none { activity.shouldShowRequestPermissionRationale(it) }
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Let WifiDoctor see your Wi-Fi",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            "Android treats Wi-Fi details as location data, because nearby network names can reveal where you are. " +
                "To diagnose your network, WifiDoctor needs:",
            style = MaterialTheme.typography.bodyLarge,
        )
        Reason(Icons.Filled.LocationOn, "Location", "To read your network name (SSID) and router ID (BSSID), so results can be grouped per network.")
        Reason(Icons.Filled.Search, "Nearby Wi-Fi devices", "To scan nearby networks for channel congestion and to find a faster 5 GHz band of your network.")
        Reason(Icons.Filled.Lock, "Stays on your phone", "No location is stored or sent anywhere. WifiDoctor has no analytics and no ads; it only contacts the probe targets and the speed-test server.")

        if (denied) {
            Text(
                if (permanentlyDenied) {
                    "Access was denied. You can still run basic checks, or allow access in the app settings under Permissions."
                } else {
                    "Without access, the network name, router ID and congestion check are unavailable. Latency, DNS and speed tests still work."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.size(8.dp))
        if (permanentlyDenied) {
            Button(onClick = { context.openAppSettings() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Open app settings")
            }
        } else {
            Button(onClick = { launcher.launch(permissions.toTypedArray()) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (denied) "Try again" else "Allow access")
            }
        }
        OutlinedButton(onClick = { onFinished(false) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Continue with limited checks")
        }
    }
}

@Composable
private fun Reason(icon: ImageVector, title: String, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
