package com.example.rc_boat_base_station_cellular // Change this to your actual package name

import android.app.Application
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import java.util.UUID

// --- Data class for Telemetry ---
data class TelemetryData(
    val boatVoltage: String = "-.-- V",
    val boatTacho: String = "---- RPM",
    val phoneBattery: String = "--%",
    val phoneGps: String = "No Fix",
    val phoneSignal: String = "--",
    val phoneNetworkType: String = "Unknown",
    val phoneHeading: String = "---°"
)

// --- Main Activity ---
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BaseStationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    BaseStationScreen()
                }
            }
        }
    }
}

// --- ViewModel ---
class BaseStationViewModel(application: Application) : AndroidViewModel(application) {

    // --- State Flows for UI ---
    private val _mqttStatus = MutableStateFlow("Disconnected")
    val mqttStatus = _mqttStatus.asStateFlow()

    private val _telemetryData = MutableStateFlow(TelemetryData())
    val telemetryData = _telemetryData.asStateFlow()

    // --- MQTT Handling ---
    private val brokerHost = BuildConfig.MQTT_BROKER_HOST
    private val brokerPort = 8883
    private val mqttUsername = BuildConfig.MQTT_USERNAME
    private val mqttPassword = BuildConfig.MQTT_PASSWORD
    private var mqttClient: Mqtt5AsyncClient? = null
    private var commandPublishJob: Job? = null

    // --- Control State ---
    private var throttle = 0f
    private var steering = 0f

    fun connectMqtt() {
        _mqttStatus.value = "Connecting..."
        mqttClient = MqttClient.builder()
            .useMqttVersion5()
            .identifier("BaseStation-${UUID.randomUUID()}")
            .serverHost(brokerHost)
            .serverPort(brokerPort)
            .sslWithDefaultConfig()
            .automaticReconnectWithDefaultConfig()
            .addConnectedListener {
                viewModelScope.launch {
                    _mqttStatus.value = "Connected"
                }
            }
            .addDisconnectedListener {
                viewModelScope.launch {
                    _mqttStatus.value = "Disconnected"
                    commandPublishJob?.cancel()
                }
            }
            .buildAsync()

        mqttClient?.connectWith()
            ?.simpleAuth()
            ?.username(mqttUsername)
            ?.password(mqttPassword.toByteArray())
            ?.applySimpleAuth()
            ?.send()
            ?.whenComplete { _, throwable ->
                viewModelScope.launch {
                    if (throwable != null) {
                        _mqttStatus.value = "Failed: ${throwable.message}"
                    }
                }
            }

        startCommandPublishing()
        subscribeToTelemetry()
    }

    private fun subscribeToTelemetry() {
        mqttClient?.subscribeWith()
            ?.topicFilter("rcboat/telemetry/#")
            ?.callback { publish ->
                if (publish.payload.isPresent) {
                    val message =  StandardCharsets.UTF_8.decode(publish.payload.get()).toString()
                    val topic = publish.topic.toString()
                    Log.d(TAG, "Received message on topic '$topic': $message")
                    updateTelemetry(topic, message)
                }
            }
            ?.send()
    }

    private fun updateTelemetry(topic: String, payload: String) {
        viewModelScope.launch {
            val currentData = _telemetryData.value
            _telemetryData.value = when {
                topic.endsWith("voltage") -> currentData.copy(boatVoltage = "$payload V")
                topic.endsWith("prop_rpm") -> currentData.copy(boatTacho = "$payload RPM")
                topic.endsWith("battery") -> currentData.copy(phoneBattery = "$payload.%")
                topic.endsWith("signal") -> currentData.copy(phoneSignal = "Level: $payload/4")
                topic.endsWith("network_type") -> currentData.copy(phoneNetworkType = payload)
                topic.endsWith("gps") -> currentData.copy(phoneGps = payload)
                topic.endsWith("compass") -> currentData.copy(phoneHeading = "$payload°")
                else -> currentData
            }
        }
    }

    private fun startCommandPublishing() {
        commandPublishJob?.cancel()
        commandPublishJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                val power = (kotlin.math.abs(throttle) * 100).toInt()
                val direction = if (throttle > 0.1) 1 else if (throttle < -0.1) -1 else 0
                val angle = (steering * 45).toInt()

                val motorCommand = "M$power,$direction\n"
                val rudderCommand = "R$angle\n"

                mqttClient
                    ?.publishWith()
                    ?.topic("rcboat/command/motor_throttle")
                    ?.payload(motorCommand.toByteArray())
                    ?.qos(MqttQos.EXACTLY_ONCE)
                    ?.retain(false)
                    ?.send()


                mqttClient
                    ?.publishWith()
                    ?.topic("rcboat/command/rudder_angle")
                    ?.payload(rudderCommand.toByteArray())
                    ?.qos(MqttQos.EXACTLY_ONCE)
                    ?.retain(false)
                    ?.send()
                delay(200)
            }
        }
    }

    fun updateThrottle(value: Float) { throttle = value }
    fun updateSteering(value: Float) { steering = value }

    fun disconnectMqtt() {
        mqttClient?.disconnect()
    }


    override fun onCleared() {
        disconnectMqtt()
        super.onCleared()
    }

    companion object {
        const val TAG = "BoatBaseStationService"
    }
}

// --- UI: Main Screen ---
@Composable
fun BaseStationScreen(viewModel: BaseStationViewModel = viewModel()) {
    val mqttStatus by viewModel.mqttStatus.collectAsState()
    val telemetryData by viewModel.telemetryData.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Base Station",
                    style = MaterialTheme.typography.headlineLarge,
                    color = Color.White
                )
                Button(onClick = {
                    if (mqttStatus == "Connected") viewModel.disconnectMqtt() else viewModel.connectMqtt()
                }) {
                    Text(if (mqttStatus == "Connected") "Disconnect" else "Connect")
                }
            }
            Text(
                "MQTT Status: $mqttStatus",
                color = if (mqttStatus == "Connected") Color.Green else Color.LightGray,
                fontSize = 14.sp
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ThrottleSlider(onMove = { viewModel.updateThrottle(it) })
                TelemetryDashboard(data = telemetryData)
                SteeringSlider(onMove = { viewModel.updateSteering(it) })
            }
        }
    }
}

// --- Other UI Composables (TelemetryDashboard, Sliders, etc.)
@Composable
fun TelemetryDashboard(data: TelemetryData) {
    Card(
        modifier = Modifier.width(250.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Live Telemetry", style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.padding(bottom = 8.dp))
            TelemetryRow("Boat Voltage", data.boatVoltage)
            TelemetryRow("Boat Tacho", data.boatTacho)
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = Color.Gray)
            TelemetryRow("Phone Battery", data.phoneBattery)
            TelemetryRow("Phone Signal", data.phoneSignal)
            TelemetryRow("Phone Network", data.phoneNetworkType)
            TelemetryRow("Phone Heading", data.phoneHeading)
            TelemetryRow("Phone GPS", data.phoneGps)
        }
    }
}

@Composable
fun ThrottleSlider(modifier: Modifier = Modifier, onMove: (Float) -> Unit) {
    var sliderPosition by remember { mutableFloatStateOf(0f) } // This is in pixels
    val sliderHeight = 250.dp
    val sliderWidth = 80.dp
    val density = LocalDensity.current

    val trackWidthPx = with(density) { 20.dp.toPx() }
    val thumbHeightPx = with(density) { 40.dp.toPx() }
    val trackCornerRadiusPx = with(density) { 10.dp.toPx() }
    val thumbCornerRadiusPx = with(density) { 5.dp.toPx() }

    Box(
        modifier = modifier
            .height(sliderHeight)
            .width(sliderWidth)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { },
                    onDragEnd = {
                        sliderPosition = 0f
                        onMove(0f)
                    },
                    onDragCancel = {
                        sliderPosition = 0f
                        onMove(0f)
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val newY = sliderPosition + dragAmount.y
                        val maxHeightPx = size.height / 2f
                        sliderPosition = newY.coerceIn(-maxHeightPx, maxHeightPx)
                        // Normalize to -1 (bottom) to 1 (top)
                        onMove(-sliderPosition / maxHeightPx)
                    }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRoundRect(
                color = Color.LightGray.copy(alpha = 0.5f),
                topLeft = Offset(center.x - trackWidthPx / 2, 0f),
                size = Size(trackWidthPx, size.height),
                cornerRadius = CornerRadius(trackCornerRadiusPx)
            )
            drawRoundRect(
                color = Color.DarkGray,
                topLeft = Offset(center.x - (thumbHeightPx / 2), center.y + sliderPosition - (thumbHeightPx / 2)),
                size = Size(thumbHeightPx, thumbHeightPx),
                cornerRadius = CornerRadius(thumbCornerRadiusPx)
            )
        }
    }
}

@Composable
fun SteeringSlider(modifier: Modifier = Modifier, onMove: (Float) -> Unit) {
    var sliderPosition by remember { mutableFloatStateOf(0f) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Steering: ${(sliderPosition * 45).toInt()}°", color = Color.White)
        Slider(
            value = sliderPosition,
            onValueChange = {
                sliderPosition = it
                onMove(it)
            },
            onValueChangeFinished = {
                sliderPosition = 0f
                onMove(0f)
            },
            valueRange = -1f..1f,
            modifier = modifier.width(200.dp)
        )
    }
}

@Composable
fun TelemetryRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontWeight = FontWeight.Bold, color = Color.White)
        Text(value, fontSize = 14.sp, color = Color.White)
    }
}

@Composable
fun BaseStationTheme(content: @Composable () -> Unit) {
    val colorScheme = darkColorScheme(
        primary = Color(0xFF3F51B5),
        secondary = Color(0xFF03A9F4),
        tertiary = Color(0xFF009688)
    )
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}