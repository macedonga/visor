package com.macedonga.visor

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.macedonga.visor.core.assistant.mcp.McpHub
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Where an MCP server's sign-in returns (com.macedonga.visor:/oauth): the code goes to the hub and the app comes back. */
@AndroidEntryPoint
class OAuthRedirectActivity : ComponentActivity() {
    @Inject
    lateinit var hub: McpHub

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.let { hub.complete(it.toString()) }
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
