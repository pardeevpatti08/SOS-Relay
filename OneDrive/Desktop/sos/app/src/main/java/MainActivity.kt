package com.example.sosrelay

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.sosrelay.databinding.ActivityMainBinding
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.BindException
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

const val MESSAGE_TYPE_SOS_REQUEST = "sos_request"
const val MESSAGE_TYPE_SOS_CONFIRMATION = "sos_confirmation"

interface MeshMessage {
    val messageType: String
    val eventId: String
}

data class SosRequest(
    override val eventId: String,
    val originatorDeviceId: String,
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val locationTimestamp: Long,
    val alertText: String = "emergency alert",
    var hops: Int = 0,
    override val messageType: String = MESSAGE_TYPE_SOS_REQUEST
) : MeshMessage

data class SosConfirmation(
    override val eventId: String,
    val gatewayDeviceId: String,
    val gatewayTimestamp: Long,
    override val messageType: String = MESSAGE_TYPE_SOS_CONFIRMATION
) : MeshMessage

data class BaseMessageWrapper(val messageType: String, val eventId: String)

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var manager: WifiP2pManager
    private lateinit var channel: WifiP2pManager.Channel
    private lateinit var receiver: BroadcastReceiver
    private lateinit var fusedLocationProviderClient: FusedLocationProviderClient

    private val PORT = 8888
    private var serverSocket: ServerSocket? = null
    private val mainActivityJob = SupervisorJob()
    private val uiScope = CoroutineScope(Dispatchers.Main + mainActivityJob)
    private val ioScope = CoroutineScope(Dispatchers.IO + mainActivityJob)

    @Volatile
    private var isP2pEnabled: Boolean = false
    private var currentGroupInfo: WifiP2pInfo? = null
    private var thisDeviceDetails: WifiP2pDevice? = null

    private lateinit var deviceId: String
    private val activeSosEvents = ConcurrentHashMap<String, SosRequest>()
    private val confirmedSosEvents = ConcurrentHashMap<String, SosConfirmation>()
    private val gson = Gson()

    private var discoveryJob: Job? = null
    private val peerDiscoveryIntervalMs = 30000L
    private val maxPeersToConnectOpportunistically = 1
    private val myDesiredGroupOwnerIntent = 7
    private val knownAvailablePeers = ConcurrentHashMap<String, WifiP2pDevice>()

    companion object {
        private const val TAG = "SOSRelayMesh"
        private const val PERMISSIONS_REQUEST_CODE = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        deviceId = getOrCreateDeviceId(this)

        log("onCreate started.") // Logcat only for this
        // UI log for device ID
        uiScope.launch {
            binding.tvLogs.append("Device ID: ${deviceId.takeLast(12)}")
            binding.scrollViewLogs.post { binding.scrollViewLogs.fullScroll(ScrollView.FOCUS_DOWN) }
        }


        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this)

        if (!checkRequiredPermissions()) {
            requestRequiredPermissions()
        }

        manager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        channel = manager.initialize(this, mainLooper) {
            log("P2P Channel Disconnected.") // Logcat
            uiScope.launch {
                binding.tvLogs.append("\n⚠️ P2P Channel Disconnected.")
                binding.scrollViewLogs.post { binding.scrollViewLogs.fullScroll(ScrollView.FOCUS_DOWN) }
            }
            isP2pEnabled = false
            stopDynamicPeerDiscovery()
            currentGroupInfo = null
        }
        log("WifiP2pManager and Channel initialized.") // Logcat

        setupReceiver()
        startServer()

        binding.btnSendSOS.setOnClickListener {
            log("Initiate SOS button clicked.") // Logcat
            initiateSOS()
        }

        binding.btnDiscoverPeers.setOnClickListener {
            log("Manual Discover Peers button clicked.") // Logcat
            uiScope.launch {
                binding.tvLogs.append("\n🔍 Manual Peer Discovery...")
                binding.scrollViewLogs.post { binding.scrollViewLogs.fullScroll(ScrollView.FOCUS_DOWN) }
            }
            if (isP2pEnabled && checkRequiredPermissions()) {
                discoverPeersAndTriggerOpportunisticAction()
            } else {
                if (!isP2pEnabled) {
                    Toast.makeText(this, "Wi-Fi P2P is not enabled.", Toast.LENGTH_SHORT).show()
                    uiScope.launch {
                        binding.tvLogs.append("\n🚫 Wi-Fi P2P not enabled for discovery.")
                        binding.scrollViewLogs.post { binding.scrollViewLogs.fullScroll(ScrollView.FOCUS_DOWN) }
                    }
                }
                if (!checkRequiredPermissions()) {
                    requestRequiredPermissions()
                    uiScope.launch {
                        binding.tvLogs.append("\n🔒 Permissions needed for discovery.")
                        binding.scrollViewLogs.post { binding.scrollViewLogs.fullScroll(ScrollView.FOCUS_DOWN) }
                    }
                }
            }
        }
        log("onCreate finished.") // Logcat
    }

    private fun getOrCreateDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences("SOSRelayPrefs", Context.MODE_PRIVATE)
        var id = prefs.getString("deviceId", null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString("deviceId", id).apply()
        }
        return id
    }

    private fun checkRequiredPermissions(): Boolean {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestRequiredPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }
        if (permissionsToRequest.isNotEmpty()) {
            log("Requesting permissions: ${permissionsToRequest.joinToString()}") // Logcat
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), PERMISSIONS_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                log("All required permissions granted.") // Logcat
                uiScope.launch {
                    binding.tvLogs.append("\n✅ Permissions Granted.")
                    binding.scrollViewLogs.post { binding.scrollViewLogs.fullScroll(ScrollView.FOCUS_DOWN) }
                }
                if(isP2pEnabled) startDynamicPeerDiscovery()
            } else {
                log("One or more required permissions were denied.") // Logcat
                Toast.makeText(this, "Required permissions are needed.", Toast.LENGTH_LONG).show()
                uiScope.launch {
                    binding.tvLogs.append("\n🚫 Permissions Denied. Functionality limited.")
                    binding.scrollViewLogs.post { binding.scrollViewLogs.fullScroll(ScrollView.FOCUS_DOWN) }
                }
            }
        }
    }

    private fun setupReceiver() {
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action
                when (action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        val previouslyEnabled = isP2pEnabled
                        isP2pEnabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                        log("P2P State Changed: ${if (isP2pEnabled) "ENABLED" else "DISABLED"}") // Logcat
                        uiScope.launch {
                            binding.tvLogs.append("\n📶 Wi-Fi P2P: ${if (isP2pEnabled) "Enabled" else "Disabled"}")
                            binding.scrollViewLogs.post { binding.scrollViewLogs.fullScroll(ScrollView.FOCUS_DOWN) }
                        }

                        if (isP2pEnabled && !previouslyEnabled && checkRequiredPermissions()) {
                            log("P2P became enabled. Starting dynamic discovery.")// Logcat
                            startDynamicPeerDiscovery()
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                manager.requestDeviceInfo(channel) { dev ->
                                    thisDeviceDetails = dev
                                    dev?.let { log("Updated this device info: ${it.deviceName}") } // Logcat
                                }
                            }
                        } else if (!isP2pEnabled) {
                            log("P2P became disabled. Stopping dynamic discovery and handling P2P disable.") // Logcat
                            stopDynamicPeerDiscovery()
                            handleP2pDisabled()
                        }
                    }
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        log("WIFI_P2P_PEERS_CHANGED_ACTION: Peer list potentially changed.") // Logcat
                        if (isP2pEnabled && checkRequiredPermissions()) {
                            manager.requestPeers(channel) { peers ->
                                val currentAvailablePeers = peers.deviceList.filter {
                                    (it.status == WifiP2pDevice.AVAILABLE || it.status == WifiP2pDevice.INVITED) && it.deviceAddress != thisDeviceDetails?.deviceAddress
                                }
                                log("Peer list updated from broadcast. Found ${currentAvailablePeers.size} available distinct peers.") // Logcat
                                if (currentAvailablePeers.isNotEmpty()) {
                                    uiScope.launch {
                                        binding.tvLogs.append("\n👀 Discovered ${currentAvailablePeers.size} nearby peer(s).")
                                        binding.scrollViewLogs.post { binding.scrollViewLogs.fullScroll(ScrollView.FOCUS_DOWN) }
                                    }
                                }
                                updateKnownPeers(currentAvailablePeers)
                            }
                        }
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val networkInfo = intent.getParcelableExtra<android.net.NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                        val p2pInfo = intent.getParcelableExtra<WifiP2pInfo>(WifiP2pManager.EXTRA_WIFI_P2P_INFO)

                        log("Connection Changed: NetworkInfo Connected: ${networkInfo?.isConnected}, Group Formed: ${p2pInfo?.groupFormed}") // Logcat

                        if (networkInfo?.isConnected == true && p2pInfo?.groupFormed == true && p2pInfo.groupOwnerAddress != null) {
                            currentGroupInfo = p2pInfo
                            val role = if (p2pInfo.isGroupOwner) "Group Owner" else "Client (GO: ${p2pInfo.groupOwnerAddress.hostAddress})"
                            log("Connected to P2P group. Is Group Owner: ${p2pInfo.isGroupOwner}. GO Address: ${p2pInfo.groupOwnerAddress.hostAddress}")// Logcat
                            Toast.makeText(this@MainActivity, if (p2pInfo.isGroupOwner) "You are Group Owner" else "Connected to Peer", Toast.LENGTH_SHORT).show()
                            uiScope.launch {
                                binding.tvLogs.append("\n🔗 Connected to P2P Group as $role.")
                                binding.scrollViewLogs.post { binding.scrollViewLogs.fullScroll(ScrollView.FOCUS_DOWN) }
                            }
                        } else {
                            log("Disconnected from P2P group or group not formed properly.") // Logcat
                            val wasConnected = currentGroupInfo != null
                            currentGroupInfo = null
                            if(wasConnected) {
                                Toast.makeText(this@MainActivity, "Disconnected", Toast.LENGTH_SHORT).show()
                                uiScope.launch {
                                    binding.tvLogs.append("\n🔌 Disconnected from P2P Group.")
                                    binding.scrollViewLogs.post { binding.scrollViewLogs.fullScroll(ScrollView.FOCUS_DOWN) }
                                }
                            }
                            if (isP2pEnabled && checkRequiredPermissions()) {
                                log("Disconnected. Ensuring dynamic discovery is active.") // Logcat
                                startDynamicPeerDiscovery() // Attempt to re-discover/re-connect
                            }
                        }
                    }
                    WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                        thisDeviceDetails = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                        thisDeviceDetails?.let {
                            log("This device details changed: ${it.deviceName} (${it.deviceAddress}) - Status: ${getDeviceStatus(it.status)}")// Logcat
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        log("BroadcastReceiver registered.") // Logcat
    }

    private fun handleP2pDisabled() {
        Toast.makeText(this, "Wi-Fi P2P is disabled.", Toast.LENGTH_LONG).show()
        currentGroupInfo = null
        knownAvailablePeers.clear()
    }

    private fun discoverPeersAndTriggerOpportunisticAction() {
        log("Manual peer discovery initiated...") // Logcat
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                log("Manual Discovery: discoverPeers success.") // Logcat
                Toast.makeText(this@MainActivity, "Discovering...", Toast.LENGTH_SHORT).show()
                // UI log moved to btnDiscoverPeers click listener
                ioScope.launch {
                    delay(2000) // Allow some time for peers to respond to discovery
                    if (isActive && isP2pEnabled) requestPeersForOpportunisticAction()
                }
            }
            override fun onFailure(reason: Int) {
                log("Manual Discovery: discoverPeers failed: ${getFailureReason(reason)}") // Logcat
                Toast.makeText(this@MainActivity, "Discovery failed.", Toast.LENGTH_SHORT).show()
                uiScope.launch {
                    binding.tvLogs.append("\n⚠️ Peer Discovery Failed: ${getFailureReason(reason)}")
                    binding.scrollViewLogs.post { binding.scrollViewLogs.fullScroll(ScrollView.FOCUS_DOWN) }
                }
            }
        })
    }

    private fun startDynamicPeerDiscovery() {
        if (!isP2pEnabled || !checkRequiredPermissions()) {
            log("Cannot start dynamic discovery: P2P disabled or permissions missing.") // Logcat
            return
        }
        if (discoveryJob?.isActive == true) {
            log("Dynamic peer discovery is already running.") // Logcat
            return
        }
        log("Starting dynamic peer discovery coroutine (Interval: ${peerDiscoveryIntervalMs}ms).") // Logcat
        uiScope.launch {
            binding.tvLogs.append("\n🔄 Starting automatic peer discovery...")
            binding.scrollViewLogs.post { binding.scrollViewLogs.fullScroll(ScrollView.FOCUS_DOWN) }
        }
        discoveryJob = ioScope.launch {
            while (isActive && isP2pEnabled) {
                if (checkRequiredPermissions()) {
                    log("Dynamic Discovery Tick: Calling discoverPeers()") // Logcat
                    manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            log("Dynamic Discovery Tick: discoverPeers success.") // Logcat
                            ioScope.launch {
                                delay(2500) // Time for peers to populate after discovery call
                                if (isActive && isP2pEnabled) requestPeersForOpportunisticAction()
                            }
                        }
                        override fun onFailure(reason: Int) {
                            log("Dynamic Discovery Tick: discoverPeers failed: ${getFailureReason(reason)}") // Logcat
                        }
                    })
                } else {
                    log("Dynamic Discovery Tick: Permissions not granted. Stopping discovery.") // Logcat
                    stopDynamicPeerDiscovery()
                    break
                }
                delay(peerDiscoveryIntervalMs)
            }
            log("Dynamic peer discovery coroutine ended (isActive=$isActive, isP2pEnabled=$isP2pEnabled).") // Logcat
            uiScope.launch {
                binding.tvLogs.append("\n🛑 Automatic peer discovery stopped.")
                binding.scrollViewLogs.post { binding.scrollViewLogs.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }
    }

    private fun stopDynamicPeerDiscovery() {
        if (discoveryJob?.isActive == true) {
            log("Stopping dynamic peer discovery.") // Logcat
            discoveryJob?.cancel()
        }
    }

    private fun requestPeersForOpportunisticAction() {
        if (!isP2pEnabled || !checkRequiredPermissions()) {
            log("Opportunistic Action: P2P disabled or no permissions.") // Logcat
            return
        }
        if (currentGroupInfo != null) {
            log("Opportunistic Action: Already in a group (${if(currentGroupInfo!!.isGroupOwner) "GO" else "Client"}). Skipping connection attempt.") // Logcat
            return // No need to try connecting if already in a group
        }

        manager.requestPeers(channel) { peers ->
            val availablePeers = peers.deviceList.filter {
                (it.status == WifiP2pDevice.AVAILABLE || it.status == WifiP2pDevice.INVITED) && it.deviceAddress != thisDeviceDetails?.deviceAddress
            }
            log("Opportunistic Action: Found ${availablePeers.size} available distinct peers.") // Logcat
            updateKnownPeers(availablePeers) // Update list regardless

            if (availablePeers.isNotEmpty() && currentGroupInfo == null) { // Double check currentGroupInfo
                tryOpportunisticConnection(availablePeers)
            } else if (currentGroupInfo == null) {
                log("Opportunistic Action: No available peers to connect to or already connecting.") // Logcat
            }
        }
    }

    private fun updateKnownPeers(peerList: List<WifiP2pDevice>) {
        knownAvailablePeers.clear()
        peerList.forEach { knownAvailablePeers[it.deviceAddress] = it }
    }

    private fun tryOpportunisticConnection(availablePeers: List<WifiP2pDevice>) {
        if (currentGroupInfo != null || availablePeers.isEmpty()) {
            return // Should be already checked by caller, but good safeguard
        }
        // Try to connect to a limited number of peers to avoid spamming connection requests
        val peersToAttempt = availablePeers.shuffled().take(maxPeersToConnectOpportunistically)
        peersToAttempt.forEach { peer ->
            log("Opportunistically trying to connect to ${peer.deviceName} (${peer.deviceAddress})") // Logcat
            uiScope.launch {
                binding.tvLogs.append("\n🤝 Attempting to connect to ${peer.deviceName.take(15)}...")
                binding.scrollViewLogs.post { binding.scrollViewLogs.fullScroll(ScrollView.FOCUS_DOWN) }
            }
            val config = WifiP2pConfig().apply {
                deviceAddress = peer.deviceAddress
                groupOwnerIntent = myDesiredGroupOwnerIntent // Influences GO negotiation
            }
            manager.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    log("Opportunistic connection request to ${peer.deviceName} sent. Waiting for broadcast...") // Logcat
                    // UI update will happen via WIFI_P2P_CONNECTION_CHANGED_ACTION
                }
                override fun onFailure(reason: Int) {
                    log("Opportunistic connection request to ${peer.deviceName} failed: ${getFailureReason(reason)}") // Logcat
                    uiScope.launch {
                        binding.tvLogs.append("\n⚠️ Connection to ${peer.deviceName.take(15)} failed: ${getFailureReason(reason)}")
                        binding.scrollViewLogs.post { binding.scrollViewLogs.fullScroll(ScrollView.FOCUS_DOWN) }
                    }
                }
            })
        }
    }

    private fun initiateSOS() {
        if (!checkRequiredPermissions()) {
            Toast.makeText(this, "Permissions required to send SOS.", Toast.LENGTH_SHORT).show()
            requestRequiredPermissions()
            return
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            log("Location permission not granted for SOS.") // Logcat
            uiScope.launch {
                binding.tvLogs.append("\n🚫 Location permission needed for SOS.")
                binding.scrollViewLogs.post { binding.scrollViewLogs.fullScroll(ScrollView.FOCUS_DOWN) }
            }
            return
        }
        fusedLocationProviderClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location: Location? ->
                if (location != null) {
                    val newEventId = "${deviceId}_${System.currentTimeMillis()}"
                    val request = SosRequest(
                        eventId = newEventId, originatorDeviceId = deviceId,
                        timestamp = System.currentTimeMillis(), latitude = location.latitude,
                        longitude = location.longitude, accuracy = location.accuracy,
                        locationTimestamp = location.time, alertText = "Emergency! Needs assistance." // Customizable
                    )
                    log("Originating new SOS: ID=${request.eventId}, Device=${request.originatorDeviceId}, Loc=(${request.latitude},${request.longitude}), Text='${request.alertText}'") // Logcat
                    uiScope.launch {
                        binding.tvLogs.append("\n\n✨ New SOS Initiated ✨\n  Event: ${request.eventId.takeLast(6)}\n  Location: ${String.format("%.4f, %.4f", request.latitude, request.longitude)}\n  Message: '${request.alertText}'")
                        binding.scrollViewLogs.post { binding.scrollViewLogs.fullScroll(ScrollView.FOCUS_DOWN) }
                    }
                    activeSosEvents[request.eventId] = request

                    if (hasInternetAccess(this)) {
                        log("Attempting direct backend send for SOS: ${request.eventId}") // Logcat
                        uiScope.launch {
                            binding.tvLogs.append("\n📡 Internet detected. Attempting to notify master server...")
                            binding.scrollViewLogs.post { binding.scrollViewLogs.fullScroll(ScrollView.FOCUS_DOWN) }
                        }
                        sendToBackend(request) { success ->
                            if (success) {
                                // Logcat only for backend success, UI update via local confirmation processing
                                log("SOS ${request.eventId} sent directly to backend by originator.")
                                val confirmation = SosConfirmation(
                                    eventId = request.eventId, gatewayDeviceId = deviceId,
                                    gatewayTimestamp = System.currentTimeMillis()
                                )
                                // Process locally which will update UI and then broadcast
                                uiScope.launch { processReceivedSosConfirmation(confirmation, true) }
                                log("Broadcasting SOS Confirmation (after backend success): ID=${confirmation.eventId}") // Logcat
                                broadcastMessageToPeers(confirmation)
                            } else {
                                log("Direct backend send failed. Broadcasting SOS Request: ${request.eventId} via P2P.") // Logcat
                                uiScope.launch {
                                    binding.tvLogs.append("\n⚠️ Master server unreachable. Broadcasting SOS to nearby peers...")
                                    binding.scrollViewLogs.post { binding.scrollViewLogs.fullScroll(ScrollView.FOCUS_DOWN) }
                                }
                                broadcastMessageToPeers(request)
                            }
                        }
                    } else {
                        log("No internet. Broadcasting SOS Request: ${request.eventId} via P2P.") // Logcat
                        uiScope.launch {
                            binding.tvLogs.append("\n📶 No Internet. Broadcasting SOS to nearby peers...")
                            binding.scrollViewLogs.post { binding.scrollViewLogs.fullScroll(ScrollView.FOCUS_DOWN) }
                        }
                        broadcastMessageToPeers(request)
                    }
                } else {
                    log("Failed to get location for SOS."); Toast.makeText(this, "Could not get location.", Toast.LENGTH_SHORT).show() // Logcat
                    uiScope.launch {
                        binding.tvLogs.append("\n📍 Could not get location for SOS.")
                        binding.scrollViewLogs.post { binding.scrollViewLogs.fullScroll(ScrollView.FOCUS_DOWN) }
                    }
                }
            }.addOnFailureListener { e ->
                log("Location fetching error: ${e.message}"); Toast.makeText(this, "Location error.", Toast.LENGTH_SHORT).show() // Logcat
                uiScope.launch {
                    binding.tvLogs.append("\n📍 Location error: ${e.localizedMessage}")
                    binding.scrollViewLogs.post { binding.scrollViewLogs.fullScroll(ScrollView.FOCUS_DOWN) }
                }
            }
    }

    private fun processReceivedSosRequest(request: SosRequest, fromAddress: String? = null) {
        val sourceInfo = fromAddress ?: "P2P Network"
        log("Processing received SOS Request: ID=${request.eventId}, OrigDevice=${request.originatorDeviceId}, Hops=${request.hops}, Loc=(${request.latitude},${request.longitude}), Acc=${request.accuracy}m, LocTime=${request.locationTimestamp}, Text='${request.alertText}'. From: $sourceInfo") // Logcat

        if (confirmedSosEvents.containsKey(request.eventId)) {
            log("Ignoring SOS Request for already confirmed event: ${request.eventId}") // Logcat
            return
        }
        val existingActive = activeSosEvents[request.eventId]
        if (existingActive != null && existingActive.hops <= request.hops) {
            log("Duplicate or less efficient active SOS Request for event: ${request.eventId}. Ignoring.") // Logcat
            return
        }
        log("New/better SOS Request for event ${request.eventId}. Adding/Updating active events.") // Logcat
        activeSosEvents[request.eventId] = request

        uiScope.launch {
            val uiMessage = "\n\n🆘 ALERT RECEIVED (from $sourceInfo) 🆘\n" +
                    "  Event: ${request.eventId.takeLast(6)}\n" +
                    "  By: ${request.originatorDeviceId.takeLast(8)}\n" +
                    "  Loc: ${String.format("%.4f, %.4f", request.latitude, request.longitude)} (Accuracy: ${request.accuracy}m)\n" +
                    "  Message: '${request.alertText}'\n" +
                    "  Hops: ${request.hops}"
            binding.tvLogs.append(uiMessage)
            binding.scrollViewLogs.post { binding.scrollViewLogs.fullScroll(ScrollView.FOCUS_DOWN) }
        }

        if (hasInternetAccess(this)) {
            log("Attempting backend send for received SOS: ${request.eventId}") // Logcat
            uiScope.launch {
                binding.tvLogs.append("\n📡 Internet detected. Relaying alert (${request.eventId.takeLast(6)}) to master server...")
                binding.scrollViewLogs.post { binding.scrollViewLogs.fullScroll(ScrollView.FOCUS_DOWN) }
            }
            sendToBackend(request) { success ->
                if (success) {
                    log("SOS ${request.eventId} relayed to backend by this gateway ($deviceId).") // Logcat
                    val confirmation = SosConfirmation(
                        eventId = request.eventId, gatewayDeviceId = deviceId,
                        gatewayTimestamp = System.currentTimeMillis()
                    )
                    uiScope.launch { processReceivedSosConfirmation(confirmation, true) } // Process locally
                    log("Broadcasting SOS Confirmation (after relay backend success): ID=${confirmation.eventId}") // Logcat
                    broadcastMessageToPeers(confirmation)
                } else {
                    log("Backend send failed for ${request.eventId}. Relaying original request via P2P.") // Logcat
                    uiScope.launch {
                        binding.tvLogs.append("\n⚠️ Master server unreachable. Relaying SOS (${request.eventId.takeLast(6)}) to other nearby peers...")
                        binding.scrollViewLogs.post { binding.scrollViewLogs.fullScroll(ScrollView.FOCUS_DOWN) }
                    }
                    relaySosRequest(request)
                }
            }
        } else {
            log("No internet. Relaying SOS Request: ${request.eventId} via P2P.") // Logcat
            uiScope.launch {
                binding.tvLogs.append("\n📶 No Internet. Relaying SOS (${request.eventId.takeLast(6)}) to other nearby peers...")
                binding.scrollViewLogs.post { binding.scrollViewLogs.fullScroll(ScrollView.FOCUS_DOWN) }
            }
            relaySosRequest(request)
        }
    }

    private fun relaySosRequest(request: SosRequest) {
        if (request.originatorDeviceId == deviceId && request.hops > 0) {
            log("Originator ($deviceId) not re-relaying its own message ${request.eventId} that has already been hopped (hops=${request.hops}).") // Logcat
            return
        }
        val relayedRequest = request.copy(hops = request.hops + 1)
        log("Relaying SOS Request: ID=${relayedRequest.eventId}, OrigDevice=${relayedRequest.originatorDeviceId}, Hops=${relayedRequest.hops}, Loc=(${relayedRequest.latitude},${relayedRequest.longitude}), Text='${relayedRequest.alertText}'") // Logcat
        uiScope.launch {
            binding.tvLogs.append("\n↪️ Relaying SOS (${relayedRequest.eventId.takeLast(6)}) to peers (Hops: ${relayedRequest.hops})...")
            binding.scrollViewLogs.post { binding.scrollViewLogs.fullScroll(ScrollView.FOCUS_DOWN) }
        }
        broadcastMessageToPeers(relayedRequest)
    }

    private fun processReceivedSosConfirmation(confirmation: SosConfirmation, isLocallyGenerated: Boolean = false) {
        log("Processing SOS Confirmation: ID=${confirmation.eventId}, GatewayDevice=${confirmation.gatewayDeviceId}, GatewayTime=${confirmation.gatewayTimestamp}, LocallyGenerated=$isLocallyGenerated") // Logcat

        if (confirmedSosEvents.containsKey(confirmation.eventId) && !isLocallyGenerated) {
            log("Duplicate SOS Confirmation for event: ${confirmation.eventId}. Ignoring.") // Logcat
            return
        }
        confirmedSosEvents[confirmation.eventId] = confirmation
        val removedActive = activeSosEvents.remove(confirmation.eventId)

        if (removedActive != null) {
            log("Event ${confirmation.eventId} is now confirmed. Ceasing relay of original request.") // Logcat
        } else if (!isLocallyGenerated){
            log("Event ${confirmation.eventId} confirmed (was not in active list).") // Logcat
        }

        uiScope.launch {
            val sourceText = if (isLocallyGenerated && confirmation.gatewayDeviceId == deviceId) "(This device notified Master Server)"
            else if (confirmation.gatewayDeviceId == deviceId) "(This device relayed to Master Server)"
            else "(from Peer ${confirmation.gatewayDeviceId.takeLast(8)})"

            val uiMessage = "\n\n👍 ALERT CONFIRMED ${sourceText} 👍\n" +
                    "  Event: ${confirmation.eventId.takeLast(6)}\n" +
                    "  Gateway: ${confirmation.gatewayDeviceId.takeLast(8)}"
            binding.tvLogs.append(uiMessage)
            binding.scrollViewLogs.post { binding.scrollViewLogs.fullScroll(ScrollView.FOCUS_DOWN) }
        }

        // Only re-broadcast if it wasn't just locally generated for the very first time OR if it was already active
        if (!isLocallyGenerated || (isLocallyGenerated && removedActive != null)) {
            log("Re-broadcasting SOS Confirmation: ID=${confirmation.eventId}, Gateway=${confirmation.gatewayDeviceId} via P2P.") // Logcat
            broadcastMessageToPeers(confirmation)
        } else if (isLocallyGenerated && removedActive == null) {
            // This case means it was locally generated, and it was NOT in activeSosEvents.
            // This implies the initial broadcast from initiateSOS or processReceivedSosRequest (after backend success) already handled it.
            log("Initial local confirmation for ${confirmation.eventId}. Broadcast of this confirmation already handled by originating function.")
        }
    }

    private fun sendToBackend(request: SosRequest, callback: (Boolean) -> Unit) {
        log("Attempting to send SOS ID ${request.eventId} to backend. Details: ${gson.toJson(request)}") // Logcat
        ioScope.launch {
            try {
                delay(1500 + Random.nextLong(2000))
                val success = Random.nextInt(10) > 2
                log("Backend send ${if (success) "SUCCESS" else "FAILED"} for ${request.eventId}") // Logcat
                withContext(Dispatchers.Main) { callback(success) }
            } catch (e: Exception) {
                log("Error during simulated backend send for ${request.eventId}: ${e.message}") // Logcat
                withContext(Dispatchers.Main) { callback(false) }
            }
        }
    }

    private fun hasInternetAccess(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun broadcastMessageToPeers(message: MeshMessage) {
        val jsonMessage: String = try {
            gson.toJson(message)
        } catch (e: Exception) {
            log("Error serializing message for event ${message.eventId}: ${e.message}") // Logcat
            return
        }
        log("Attempting to broadcast ${message.messageType} for event ${message.eventId} via P2P.") // Logcat

        ioScope.launch {
            if (currentGroupInfo != null) {
                if (currentGroupInfo!!.isGroupOwner) {
                    log("GO: Broadcasting ${message.messageType} for event ${message.eventId} to clients (Conceptual). Details: $jsonMessage") // Logcat
                    // TODO: Implement actual sending to all connected client Sockets.
                    uiScope.launch {
                        binding.tvLogs.append("\n📢 GO: Sending ${message.messageType.replace("_"," ")} (${message.eventId.takeLast(6)}) to clients...")
                        binding.scrollViewLogs.post { binding.scrollViewLogs.fullScroll(ScrollView.FOCUS_DOWN) }
                    }
                } else { // P2P Client
                    currentGroupInfo!!.groupOwnerAddress?.let { goAddress ->
                        log("Client: Sending ${message.messageType} for event ${message.eventId} to GO at $goAddress. Details: $jsonMessage") // Logcat
                        uiScope.launch {
                            binding.tvLogs.append("\n📤 Client: Sending ${message.messageType.replace("_"," ")} (${message.eventId.takeLast(6)}) to Group Owner...")
                            binding.scrollViewLogs.post { binding.scrollViewLogs.fullScroll(ScrollView.FOCUS_DOWN) }
                        }
                        sendJsonToPeer(jsonMessage, goAddress, PORT)
                    } ?: log("Client: GO Address is null, cannot send ${message.messageType} for event ${message.eventId}.") // Logcat
                }
            } else { // Standalone
                log("Standalone: Attempting P2P broadcast for ${message.messageType}, event ${message.eventId}. Known available peers: ${knownAvailablePeers.size}") // Logcat
                if (knownAvailablePeers.isNotEmpty()) {
                    knownAvailablePeers.values.shuffled().take(maxPeersToConnectOpportunistically).forEach { peer ->
                        log("Standalone: Attempting connection to ${peer.deviceName} to send ${message.messageType} for event ${message.eventId}. Details: $jsonMessage") // Logcat
                        // UI log for attempting connection is in tryOpportunisticConnection
                        connectAndSendToDevice(peer, jsonMessage)
                    }
                } else {
                    log("Standalone: No known available peers for ${message.messageType}, event ${message.eventId}. Triggering discovery.") // Logcat
                    uiScope.launch {
                        binding.tvLogs.append("\n🤷 No peers found for direct send. Discovering...")
                        binding.scrollViewLogs.post { binding.scrollViewLogs.fullScroll(ScrollView.FOCUS_DOWN) }
                    }
                    if (isP2pEnabled && checkRequiredPermissions()) {
                        discoverPeersAndTriggerOpportunisticAction()
                    }
                }
            }
        }
    }

    private fun connectAndSendToDevice(device: WifiP2pDevice, jsonMessage: String) {
        log("Requesting connection to ${device.deviceName} for sending message (event: ${try{gson.fromJson(jsonMessage, BaseMessageWrapper::class.java).eventId}catch(e:Exception){"unknown"}}).") // Logcat
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            groupOwnerIntent = 0 // When initiating for sending, prefer to be client
        }
        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                log("Connection request to ${device.deviceName} sent. Message for event ${try{gson.fromJson(jsonMessage, BaseMessageWrapper::class.java).eventId}catch(e:Exception){"unknown"}} will be sent via established roles once connected.") // Logcat
                // UI update will happen via WIFI_P2P_CONNECTION_CHANGED_ACTION and subsequent message send
            }
            override fun onFailure(reason: Int) {
                log("Connection initiation failed to ${device.deviceName}: ${getFailureReason(reason)}") // Logcat
                uiScope.launch {
                    binding.tvLogs.append("\n⚠️ Connection to ${device.deviceName.take(15)} for send failed: ${getFailureReason(reason)}")
                    binding.scrollViewLogs.post { binding.scrollViewLogs.fullScroll(ScrollView.FOCUS_DOWN) }
                }
            }
        })
    }

    private fun sendJsonToPeer(jsonMessage: String, targetAddress: InetAddress, targetPort: Int) {
        val eventIdForLog = try { gson.fromJson(jsonMessage, BaseMessageWrapper::class.java).eventId } catch (e: Exception) { "unknown" }
        ioScope.launch {
            try {
                Socket(targetAddress, targetPort).use { socket ->
                    PrintWriter(socket.getOutputStream(), true).use { out ->
                        out.println(jsonMessage)
                        log("JSON for event $eventIdForLog dispatched to $targetAddress:$targetPort") // Logcat
                        // Implicit UI update from caller (broadcastMessageToPeers)
                    }
                }
            } catch (e: ConnectException) {
                log("ConnectException sending event $eventIdForLog to $targetAddress:$targetPort : ${e.message}") // Logcat
                uiScope.launch {
                    binding.tvLogs.append("\n❌ Failed to send to $targetAddress (Event ${eventIdForLog.takeLast(6)}): Connection issue.")
                    binding.scrollViewLogs.post { binding.scrollViewLogs.fullScroll(ScrollView.FOCUS_DOWN) }
                }
            } catch (e: IOException) {
                log("IOException sending event $eventIdForLog to $targetAddress:$targetPort : ${e.message}") // Logcat
                uiScope.launch {
                    binding.tvLogs.append("\n❌ Failed to send to $targetAddress (Event ${eventIdForLog.takeLast(6)}): IO Error.")
                    binding.scrollViewLogs.post { binding.scrollViewLogs.fullScroll(ScrollView.FOCUS_DOWN) }
                }
            } catch (e: Exception) {
                log("Error sending JSON for event $eventIdForLog to $targetAddress:$targetPort : ${e.message}") // Logcat
                uiScope.launch {
                    binding.tvLogs.append("\n❌ Error sending to $targetAddress (Event ${eventIdForLog.takeLast(6)}).")
                    binding.scrollViewLogs.post { binding.scrollViewLogs.fullScroll(ScrollView.FOCUS_DOWN) }
                }
            }
        }
    }

    private fun startServer() {
        ioScope.launch {
            try {
                serverSocket?.close()
                serverSocket = ServerSocket(PORT)
                log("Server started. Listening on port $PORT") // Logcat
                uiScope.launch {
                    binding.tvLogs.append("\n▶️ P2P Message Receiver Started (Port $PORT).")
                    binding.scrollViewLogs.post { binding.scrollViewLogs.fullScroll(ScrollView.FOCUS_DOWN) }
                }

                while (isActive && serverSocket?.isClosed == false) {
                    try {
                        val clientSocket = serverSocket!!.accept() // Blocking call
                        val clientIp = clientSocket.inetAddress?.hostAddress ?: "unknown client"
                        log("Client connected: $clientIp") // Logcat
                        uiScope.launch {
                            binding.tvLogs.append("\n📲 Peer connected: $clientIp")
                            binding.scrollViewLogs.post { binding.scrollViewLogs.fullScroll(ScrollView.FOCUS_DOWN) }
                        }

                        ioScope.launch { // Handle each client in its own coroutine
                            try {
                                BufferedReader(InputStreamReader(clientSocket.getInputStream())).use { reader ->
                                    val receivedJson = reader.readLine()
                                    if (receivedJson != null) {
                                        log("Received raw JSON from $clientIp: $receivedJson") // Logcat
                                        try {
                                            val baseMsg = gson.fromJson(receivedJson, BaseMessageWrapper::class.java)
                                            uiScope.launch { // Process on Main thread for UI and state consistency
                                                when (baseMsg.messageType) {
                                                    MESSAGE_TYPE_SOS_REQUEST -> {
                                                        val request = gson.fromJson(receivedJson, SosRequest::class.java)
                                                        processReceivedSosRequest(request, clientIp)
                                                    }
                                                    MESSAGE_TYPE_SOS_CONFIRMATION -> {
                                                        val confirmation = gson.fromJson(receivedJson, SosConfirmation::class.java)
                                                        processReceivedSosConfirmation(confirmation) // isLocallyGenerated defaults to false
                                                    }
                                                    else -> {
                                                        log("Unknown message type received from $clientIp: ${baseMsg.messageType}") // Logcat
                                                        binding.tvLogs.append("\n❓ Received unknown message type from $clientIp.")
                                                        binding.scrollViewLogs.post { binding.scrollViewLogs.fullScroll(ScrollView.FOCUS_DOWN) }
                                                    }
                                                }
                                            }
                                        } catch (e: JsonSyntaxException) {
                                            log("JSON Parsing Error from $clientIp for message '$receivedJson': ${e.message}") // Logcat
                                            uiScope.launch {
                                                binding.tvLogs.append("\n⚠️ Error parsing message from $clientIp.")
                                                binding.scrollViewLogs.post { binding.scrollViewLogs.fullScroll(ScrollView.FOCUS_DOWN) }
                                            }
                                        } catch (e: Exception) {
                                            log("Error processing message from $clientIp for message '$receivedJson': ${e.message}") // Logcat
                                            uiScope.launch {
                                                binding.tvLogs.append("\n💥 Error processing message from $clientIp.")
                                                binding.scrollViewLogs.post { binding.scrollViewLogs.fullScroll(ScrollView.FOCUS_DOWN) }
                                            }
                                        }
                                    } else {
                                        log("Received null (EOF) from $clientIp. Connection closed by peer.") // Logcat
                                        uiScope.launch {
                                            binding.tvLogs.append("\n👋 Peer $clientIp disconnected.")
                                            binding.scrollViewLogs.post { binding.scrollViewLogs.fullScroll(ScrollView.FOCUS_DOWN) }
                                        }
                                    }
                                }
                            } catch (e: IOException) { // Catch reader specific IO errors
                                if (isActive) log("IOException with client $clientIp: ${e.message}") // Logcat
                            } catch (e: Exception) { // Catch any other exceptions during client handling
                                if (isActive) log("Exception with client $clientIp: ${e.message}") // Logcat
                            } finally {
                                try { clientSocket.close() } catch (e: IOException) { /* ignore closing error */ }
                                log("Client socket closed for $clientIp") // Logcat
                            }
                        }
                    } catch (e: SocketException) { // Catch serverSocket.accept() specific errors
                        if (!isActive || serverSocket?.isClosed == true) {
                            log("Server socket closed or coroutine stopping. Exiting accept loop.") // Logcat
                            break // Exit loop if server socket is intentionally closed or scope is cancelled
                        }
                        if(isActive) log("SocketException in server accept: ${e.message}") // Logcat
                    } catch (e: IOException) { // Catch other server-level IO errors
                        if (isActive) log("IOException in server accept: ${e.message}") // Logcat
                    }
                }
            } catch (e: BindException) {
                log("Server Bind Error (Port $PORT likely in use): ${e.message}") // Logcat
                uiScope.launch { Toast.makeText(this@MainActivity, "Server Error: Port $PORT in use.", Toast.LENGTH_LONG).show() }
                uiScope.launch {
                    binding.tvLogs.append("\n‼️ SERVER ERROR: Port $PORT is already in use!")
                    binding.scrollViewLogs.post { binding.scrollViewLogs.fullScroll(ScrollView.FOCUS_DOWN) }
                }
            } catch (e: Exception) { // Catch any other critical server setup errors
                if (isActive) log("Critical Server Error: ${e.message}") // Logcat
                uiScope.launch {
                    binding.tvLogs.append("\n‼️ CRITICAL SERVER ERROR!")
                    binding.scrollViewLogs.post { binding.scrollViewLogs.fullScroll(ScrollView.FOCUS_DOWN) }
                }
            } finally {
                log("Server listening loop finished.") // Logcat
                serverSocket?.close()
                uiScope.launch {
                    binding.tvLogs.append("\n⏹️ P2P Message Receiver Stopped.")
                    binding.scrollViewLogs.post { binding.scrollViewLogs.fullScroll(ScrollView.FOCUS_DOWN) }
                }
            }
        }
    }

    private fun log(text: String) {
        // This function now ONLY logs to Logcat. UI updates are handled directly in uiScope.launch blocks.
        Log.d(TAG, "[$deviceId] $text")
    }

    private fun getDeviceStatus(deviceStatus: Int): String { return when (deviceStatus) {
        WifiP2pDevice.AVAILABLE -> "Available"
        WifiP2pDevice.INVITED -> "Invited"
        WifiP2pDevice.CONNECTED -> "Connected"
        WifiP2pDevice.FAILED -> "Failed"
        WifiP2pDevice.UNAVAILABLE -> "Unavailable"
        else -> "Unknown"
    }}
    private fun getFailureReason(reasonCode: Int): String { return when (reasonCode) {
        WifiP2pManager.ERROR -> "General Error"
        WifiP2pManager.P2P_UNSUPPORTED -> "P2P Unsupported"
        WifiP2pManager.BUSY -> "Framework Busy"
        WifiP2pManager.NO_SERVICE_REQUESTS -> "No Service Requests"
        else -> "Unknown Error ($reasonCode)"
    }}

    override fun onResume() {
        super.onResume()
        log("onResume: Checking P2P state and starting dynamic discovery if conditions met.") // Logcat
        if (isP2pEnabled && checkRequiredPermissions() && discoveryJob?.isActive != true) {
            startDynamicPeerDiscovery()
        }
    }

    override fun onPause() {
        super.onPause()
        log("onPause: Activity is pausing.") // Logcat
    }

    override fun onDestroy() {
        super.onDestroy()
        log("onDestroy: Cleaning up...") // Logcat
        uiScope.launch {
            binding.tvLogs.append("\n\nApp Closing. Cleaning up...")
            binding.scrollViewLogs.post { binding.scrollViewLogs.fullScroll(ScrollView.FOCUS_DOWN) }
        }
        stopDynamicPeerDiscovery()
        mainActivityJob.cancel() // Cancels all coroutines in uiScope and ioScope

        try {
            unregisterReceiver(receiver)
            log("BroadcastReceiver unregistered.") // Logcat
        } catch (e: IllegalArgumentException) {
            log("Receiver already unregistered or never registered: ${e.message}") // Logcat
        } catch (e: Exception) {
            log("Error unregistering receiver: ${e.message}") // Logcat
        }

        if (this::manager.isInitialized && this::channel.isInitialized) {
            manager.stopPeerDiscovery(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { log("Peer discovery explicitly stopped on destroy.")} // Logcat
                override fun onFailure(reason: Int) { log("Failed to stop peer discovery on destroy: ${getFailureReason(reason)}") } // Corrected
            })
            manager.cancelConnect(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { log("Ongoing connection attempt cancelled on destroy.")} // Logcat
                override fun onFailure(reason: Int) {log("Failed to cancel connection attempt on destroy: ${getFailureReason(reason)}")} // Logcat
            })
            currentGroupInfo?.let {
                manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { log("P2P group removed on destroy.") } // Logcat
                    override fun onFailure(reason: Int) { log("Failed to remove P2P group on destroy: ${getFailureReason(reason)}") } // Logcat
                })
            }
        }

        try {
            serverSocket?.close()
            log("ServerSocket closed on destroy.") // Logcat
        } catch (e: IOException) {
            log("IOException closing server socket on destroy: ${e.message}") // Logcat
        }
        log("onDestroy cleanup finished.") // Logcat
    }
}
