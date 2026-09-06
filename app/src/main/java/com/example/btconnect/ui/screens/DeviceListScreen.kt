@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.btconnect.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.btconnect.model.DeviceInfo
import com.example.btconnect.ui.theme.OnlineGreen

@Composable
fun DeviceListScreen(
    pairedDevices: List<DeviceInfo>,
    nearbyDevices: List<DeviceInfo>,
    isScanning: Boolean,
    connectionStatusText: String?,
    onScanClick: () -> Unit,
    onDeviceClick: (DeviceInfo) -> Unit
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Nearby Devices", fontWeight = FontWeight.SemiBold) }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onScanClick,
                icon = { Icon(if (isScanning) Icons.Filled.BluetoothSearching else Icons.Filled.Bluetooth, null) },
                text = { Text(if (isScanning) "Scanning…" else "Scan for devices") }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            connectionStatusText?.let {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        it,
                        color = Color.White,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            LazyColumn(
                contentPadding = PaddingValues(vertical = 8.dp, horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (pairedDevices.isNotEmpty()) {
                    item { SectionHeader("Paired") }
                    items(pairedDevices) { device ->
                        DeviceRow(device, onClick = { onDeviceClick(device) })
                    }
                }

                item { SectionHeader("Nearby") }
                if (nearbyDevices.isEmpty()) {
                    item {
                        Text(
                            if (isScanning) "Looking for devices…" else "Tap \"Scan for devices\" to find people nearby",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp)
                        )
                    }
                } else {
                    items(nearbyDevices) { device ->
                        DeviceRow(device, onClick = { onDeviceClick(device) })
                    }
                }
                item { Spacer(Modifier.height(72.dp)) }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 4.dp)
    )
}

@Composable
private fun DeviceRow(device: DeviceInfo, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.PhoneAndroid, null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(device.address, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (device.bonded) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(OnlineGreen)
                )
            }
        }
    }
}
