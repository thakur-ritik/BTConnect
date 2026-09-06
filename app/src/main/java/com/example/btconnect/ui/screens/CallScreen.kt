package com.example.btconnect.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneCallback
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.btconnect.ui.theme.CallGradientEnd
import com.example.btconnect.ui.theme.CallGradientStart
import com.example.btconnect.ui.theme.DangerRed
import com.example.btconnect.ui.theme.OnlineGreen
import kotlinx.coroutines.delay

enum class CallScreenMode { INCOMING, OUTGOING, IN_CALL }

@Composable
fun CallScreen(
    peerName: String,
    mode: CallScreenMode,
    isMuted: Boolean,
    isSpeakerOn: Boolean,
    callStartedAtMs: Long?,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onEndCall: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit
) {
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(mode) {
        while (mode == CallScreenMode.IN_CALL) {
            delay(1000)
            tick++
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(CallGradientStart, CallGradientEnd)))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(64.dp))

            Text(
                when (mode) {
                    CallScreenMode.INCOMING -> "Incoming call"
                    CallScreenMode.OUTGOING -> "Calling…"
                    CallScreenMode.IN_CALL -> { tick; callTimer(callStartedAtMs) }
                },
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 16.sp
            )

            Spacer(Modifier.height(24.dp))

            PulsingAvatar(active = mode != CallScreenMode.IN_CALL)

            Spacer(Modifier.height(20.dp))

            Text(peerName, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Text("via Bluetooth", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)

            Spacer(Modifier.weight(1f))

            when (mode) {
                CallScreenMode.INCOMING -> IncomingControls(onAccept, onReject)
                CallScreenMode.OUTGOING -> OutgoingControls(onEndCall)
                CallScreenMode.IN_CALL -> InCallControls(isMuted, isSpeakerOn, onToggleMute, onToggleSpeaker, onEndCall)
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun callTimer(startedAtMs: Long?): String {
    if (startedAtMs == null) return "00:00"
    val elapsed = (System.currentTimeMillis() - startedAtMs) / 1000
    val m = elapsed / 60
    val s = elapsed % 60
    return String.format("%02d:%02d", m, s)
}

@Composable
private fun PulsingAvatar(active: Boolean) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (active) 1.15f else 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "scale"
    )
    Box(
        modifier = Modifier
            .size(150.dp)
            .scale(if (active) scale else 1f)
            .background(Color.White.copy(alpha = 0.15f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(110.dp)
                .background(Color.White.copy(alpha = 0.25f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(56.dp))
        }
    }
}

@Composable
private fun IncomingControls(onAccept: () -> Unit, onReject: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        CallActionButton(icon = Icons.Filled.CallEnd, background = DangerRed, label = "Decline", onClick = onReject)
        CallActionButton(icon = Icons.Filled.PhoneCallback, background = OnlineGreen, label = "Accept", onClick = onAccept)
    }
}

@Composable
private fun OutgoingControls(onEndCall: () -> Unit) {
    CallActionButton(icon = Icons.Filled.CallEnd, background = DangerRed, label = "Cancel", onClick = onEndCall)
}

@Composable
private fun InCallControls(
    isMuted: Boolean,
    isSpeakerOn: Boolean,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onEndCall: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CallActionButton(
            icon = if (isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
            background = if (isMuted) Color.White else Color.White.copy(alpha = 0.2f),
            iconTint = if (isMuted) CallGradientStart else Color.White,
            label = if (isMuted) "Unmute" else "Mute",
            onClick = onToggleMute,
            size = 56.dp
        )
        CallActionButton(icon = Icons.Filled.CallEnd, background = DangerRed, label = "End", onClick = onEndCall)
        CallActionButton(
            icon = Icons.Filled.VolumeUp,
            background = if (isSpeakerOn) Color.White else Color.White.copy(alpha = 0.2f),
            iconTint = if (isSpeakerOn) CallGradientStart else Color.White,
            label = "Speaker",
            onClick = onToggleSpeaker,
            size = 56.dp
        )
    }
}

@Composable
private fun CallActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    background: Color,
    iconTint: Color = Color.White,
    label: String,
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp = 64.dp
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(size)
                .background(background, CircleShape)
        ) {
            Icon(icon, contentDescription = label, tint = iconTint)
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
    }
}
