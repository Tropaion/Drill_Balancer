package dev.drillbalancer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.drillbalancer.ui.AppRoot
import dev.drillbalancer.ui.theme.DrillBalancerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as DrillBalancerApp
        setContent {
            DrillBalancerTheme {
                AppRoot(app.session, app.scanner::scan, app.lastDevice.address, app.training)
            }
        }
    }

    // The session is process-wide so it outlives rotations; leaving the app ends the link.
    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) (application as DrillBalancerApp).session.release()
    }
}
