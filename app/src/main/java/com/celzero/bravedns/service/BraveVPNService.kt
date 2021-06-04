/*
 * Copyright 2019 Jigsaw Operations LLC
 * Copyright 2020 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.service

import android.app.*
import android.content.*
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.pm.PackageManager
import android.net.Network
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import android.os.SystemClock.elapsedRealtime
import android.service.quicksettings.TileService.requestListeningState
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.annotation.GuardedBy
import androidx.annotation.RequiresApi
import androidx.annotation.WorkerThread
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.celzero.bravedns.R
import com.celzero.bravedns.automaton.FirewallManager
import com.celzero.bravedns.automaton.FirewallRules
import com.celzero.bravedns.data.AppMode
import com.celzero.bravedns.data.ConnectionRules
import com.celzero.bravedns.data.IPDetails
import com.celzero.bravedns.database.AppInfoRepository
import com.celzero.bravedns.database.DNSProxyEndpointRepository
import com.celzero.bravedns.database.ProxyEndpointRepository
import com.celzero.bravedns.net.doh.Transaction
import com.celzero.bravedns.net.go.GoVpnAdapter
import com.celzero.bravedns.net.manager.ConnectionTracer
import com.celzero.bravedns.receiver.BraveAutoStartReceiver
import com.celzero.bravedns.receiver.BraveScreenStateReceiver
import com.celzero.bravedns.receiver.NotificationActionReceiver
import com.celzero.bravedns.ui.ConnTrackerBottomSheetFragment.Companion.UNIVERSAL_RULES_UID
import com.celzero.bravedns.ui.HomeScreenActivity
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.backgroundAllowedUID
import com.celzero.bravedns.util.*
import com.celzero.bravedns.util.Constants.Companion.INVALID_UID
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG
import com.celzero.bravedns.util.Constants.Companion.MISSING_UID
import com.celzero.bravedns.util.Utilities.Companion.getThemeAccent
import kotlinx.coroutines.*
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject
import protect.Blocker
import protect.Protector
import settings.Settings
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.collections.HashMap
import kotlin.random.Random


class BraveVPNService : VpnService(), ConnectionMonitor.NetworkListener, Protector, Blocker, OnSharedPreferenceChangeListener {

    @GuardedBy("vpnController") private var connectionMonitor: ConnectionMonitor? = null
    @GuardedBy("vpnController") private var vpnAdapter: GoVpnAdapter? = null

    companion object {
        private const val DNS_REQUEST_PORT = 53

        var firewallRules = FirewallRules.getInstance()
        private const val FILE_LOG_TAG = "RethinkVPNService"
        const val SERVICE_ID = 1 // Only has to be unique within this app.

        var braveScreenStateReceiver = BraveScreenStateReceiver()
        var braveAutoStartReceiver = BraveAutoStartReceiver()
        private const val MAIN_CHANNEL_ID = "vpn"
        private const val WARNING_CHANNEL_ID = "warning"
        val vpnController: VpnController? = VpnController.getInstance()

        //var isScreenLocked: Boolean = false

        var appWhiteList: MutableMap<Int, Boolean> = HashMap()
        //var blockUDPTraffic: Boolean = false

        // notification request codes
        private const val NOTIF_ACTION_MODE_STOP = 100
        private const val NOTIF_ACTION_MODE_DNS_ONLY = 101
        private const val NOTIF_ACTION_MODE_DNS_FIREWALL = 102

        private const val NOTIF_LOAD_RULES_FAIL = "warning"
        private const val NOTIF_ID_LOAD_RULES_FAIL = 103
    }

    private var isLockDownPrevious: Boolean = false

    private val vpnScope = MainScope()
    private var isFirewallRulesLoaded: Boolean = false

    private lateinit var connTracer: ConnectionTracer

    private val rand: Random = Random

    private val appInfoRepository by inject<AppInfoRepository>()
    private val socks5ProxyEndpointRepository by inject<ProxyEndpointRepository>()
    private val dnsProxyEndpointRepository by inject<DNSProxyEndpointRepository>()
    private val appMode by inject<AppMode>()
    private val ipTracker by inject<IPTracker>()
    private val dnsLogTracker by inject<DNSLogTracker>()
    private val persistentState by inject<PersistentState>()
    private val tracker by inject<QueryTracker>()

    private var isAccessibilityServiceRunning: Boolean = false
    private var accessibilityHearbeatTimestamp: Long = 0L

    enum class State {
        NEW, WORKING, FAILING
    }

    override fun block(protocol: Int, uid: Long, sourceAddress: String, destAddress: String): Boolean {
        val isBlocked: Boolean
        val first = sourceAddress.split(":")
        val second = destAddress.split(":")
        isBlocked = checkConnectionBlocked(uid.toInt(), protocol, first[0], first[first.size - 1].toInt(), second[0], second[second.size - 1].toInt())
        checkLockDown()
        return isBlocked
    }

    /**
     * Check if the connection is blocked by any of the rules
     * RULE1 - Firewall App
     * RULE2 - IP blocked from connecting to Internet
     * RULE3 - Universal firewall - Block connections when screen is off
     * RULE4 - Universal firewall - Block connections when apps are in background
     * RULE5 - Universal firewall - Block when source app is unknown
     * RULE6 - Universal firewall - Block all UDP connections
     */
    private fun checkConnectionBlocked(_uid: Int, protocol: Int, sourceIp: String, sourcePort: Int, destIp: String, destPort: Int): Boolean {

        if (!isFirewallRulesLoaded) {
            Log.i(LOG_TAG, "$FILE_LOG_TAG loading rules... waiting")
            waitForFirewallRules()
            if (isFirewallRulesLoaded) {
                notifyLoadFailure()
            }
        }

        val ipDetails: IPDetails?
        var isBlocked: Boolean
        var uid = _uid
        try {
            if ((destIp == GoVpnAdapter.FAKE_DNS_IP && destPort == DNS_REQUEST_PORT)) {
                return false
            }

            if (VERSION.SDK_INT >= VERSION_CODES.Q) {
                uid = connTracer.getUidQ(protocol, sourceIp, sourcePort, destIp, destPort)
            }

            ipDetails = IPDetails(uid, sourceIp, sourcePort, destIp, destPort, System.currentTimeMillis(), false, "", protocol)

            if (appWhiteList.containsKey(uid)) {
                ipDetails.isBlocked = false
                ipDetails.blockedByRule = BlockedRuleNames.RULE7.ruleName
                connTrack(ipDetails)
                Log.i(LOG_TAG, "$FILE_LOG_TAG appWhiteList: $uid")
                return false
            }

            if (persistentState.blockUnknownConnections && destIp != GoVpnAdapter.FAKE_DNS_IP) {
                if (!Utilities.isValidUid(uid)) {
                    ipDetails.isBlocked = true
                    ipDetails.blockedByRule = BlockedRuleNames.RULE5.ruleName
                    connTrack(ipDetails)
                    delayBeforeResponse()
                    return true
                }
            }

            if (protocol == 17 && persistentState.udpBlockedSettings) {
                ipDetails.isBlocked = true
                ipDetails.blockedByRule = BlockedRuleNames.RULE6.ruleName
                connTrack(ipDetails)
                delayBeforeResponse()
                if (DEBUG) Log.d(LOG_TAG, "$FILE_LOG_TAG blockUDPTraffic: $uid, $destPort")
                return true
            }

            //Check whether the screen lock is enabled and act based on it
            if (DEBUG) Log.d(LOG_TAG, "$FILE_LOG_TAG : isScreenLockEnabled: ${persistentState.isScreenOff}")
            if (persistentState.isScreenOff) {
                //Don't include DNS and private DNS request in the list
                // FIXME: 04-12-2020 Removed the app range check for testing.
                if (Utilities.isValidUid(uid)) {
                    ipDetails.isBlocked = true
                    ipDetails.blockedByRule = BlockedRuleNames.RULE3.ruleName
                    connTrack(ipDetails)
                    if (DEBUG) Log.d(LOG_TAG, "$FILE_LOG_TAG isScreenLocked: $uid, $destPort")
                    delayBeforeResponse()
                    return true
                }

            } else if (blockBackgroundData()) { //Check whether the background is enabled and act based on it
                // FIXME: 04-12-2020 Removed the app range check for testing.
                if (Utilities.isValidUid(uid)) {
                    var isBGBlock = true
                    if (DEBUG) Log.d(LOG_TAG, "$FILE_LOG_TAG Background blocked $uid, $destIp, before sleep: $uid, $destIp, $isBGBlock, ${System.currentTimeMillis()}")

                    //Introducing exponential calculation for the wait time.
                    //initially the thread sleep was done with constant time. for eg., thread
                    //will sleep for 2 secs and will check if the app is in foreground or not.
                    //To avoid the constant time wait introduced the exponential logic below.
                    val bgBlockWaitMs = TimeUnit.SECONDS.toMillis(20)
                    var remainingWaitMs = TimeUnit.SECONDS.toMillis(10)
                    var attempt = 0

                    while (remainingWaitMs > 0) {
                        if (backgroundAllowedUID.containsKey(uid)) {
                            if (DEBUG) Log.d(LOG_TAG, "$FILE_LOG_TAG Background not blocked $uid, $destIp, AccessibilityEvent: app in foreground: $uid")
                            isBGBlock = false
                            break
                        } else {
                            if (DEBUG) Log.d(LOG_TAG, "$FILE_LOG_TAG Background blocked $uid, $destIp, AccessibilityEvent: UID: $uid is not available in the foreground, trying again")
                        }
                        remainingWaitMs = exponentialBackoff(remainingWaitMs, attempt)
                        attempt += 1
                    }

                    //When the app is not in foreground.
                    if (isBGBlock) {
                        if (DEBUG) Log.d(LOG_TAG, "$FILE_LOG_TAG Background blocked $uid, $destIp, after sleep: $uid, $destIp, $isBGBlock, ${System.currentTimeMillis()}")
                        Thread.sleep((bgBlockWaitMs + remainingWaitMs))
                        ipDetails.isBlocked = true
                        ipDetails.blockedByRule = BlockedRuleNames.RULE4.ruleName
                        connTrack(ipDetails)
                        Log.i(LOG_TAG, "$FILE_LOG_TAG Background blocked $uid after sleep of: 30 secs")
                        return isBGBlock
                    }
                }
            }

            //Check whether any rules are set block the IP/App.
            var blockedBy = ""
            if (uid != INVALID_UID && uid != MISSING_UID) {
                isBlocked = isUidBlocked(uid)
                if (isBlocked) {
                    blockedBy = BlockedRuleNames.RULE1.ruleName
                    if (persistentState.killAppOnFirewall) {
                        killFirewalledApplication(uid)
                    }
                }
            } else {
                isBlocked = false
            }

            if (!isBlocked) {
                val connectionRules = ConnectionRules(destIp, destPort, Protocol.getProtocolName(protocol).name)
                isBlocked = firewallRules.checkRules(UNIVERSAL_RULES_UID, connectionRules)
                if (isBlocked) blockedBy = BlockedRuleNames.RULE2.ruleName
            }
            ipDetails.isBlocked = isBlocked
            ipDetails.blockedByRule = blockedBy
            connTrack(ipDetails)

        } catch (iex: Exception) {
            //Returning the blocked as true for all the block call from GO with exceptions.
            //So the apps which are hit in exceptions will have the response sent as blocked(true)
            //after a delayed amount of time.
            /*
             * TODO- Check for the possibility of adding the exceptions on alerts(either in-memory
             * or as persistent alert)
             */
            isBlocked = true
            Log.e(LOG_TAG, iex.message, iex)
        }
        if (isBlocked) {
            if (DEBUG) Log.d(LOG_TAG, "$FILE_LOG_TAG Response for the block: $destIp %Before% $uid, $isBlocked,$sourceIp, $sourcePort, $destIp, $destPort ")
            delayBeforeResponse()
        }
        if (DEBUG) Log.d(LOG_TAG, "$FILE_LOG_TAG Response for the block: $destIp  %After% $uid, $isBlocked,$sourceIp, $sourcePort, $destIp, $destPort")
        return isBlocked
    }

    private fun blockBackgroundData(): Boolean {
        if (!persistentState.isBackgroundEnabled) return false
        if (!accessibilityServiceRunning()) return false
        return true
    }

    private fun accessibilityServiceRunning(): Boolean {
        val now = elapsedRealtime()
        if (Math.abs(now - accessibilityHearbeatTimestamp) > Constants.ACCESSIBILITY_SERVICE_HEARTBEAT_THRESHOLD_MS) {
            accessibilityHearbeatTimestamp = now
            isAccessibilityServiceRunning = Utilities.isAccessibilityServiceEnabledEnhanced(this, BackgroundAccessibilityService::class.java)
        }
        return isAccessibilityServiceRunning
    }

    private fun notifyLoadFailure() {
        if (isLoadFailureNotificationActive()) {
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val mainActivityIntent = PendingIntent.getActivity(this, 0, Intent(this, HomeScreenActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT)
            var builder: NotificationCompat.Builder
            if (VERSION.SDK_INT >= VERSION_CODES.O) {
                val name: CharSequence = NOTIF_LOAD_RULES_FAIL
                val description = resources.getString(R.string.rules_load_failure_heading)
                val importance = NotificationManager.IMPORTANCE_HIGH
                val channel = NotificationChannel(NOTIF_LOAD_RULES_FAIL, name, importance)
                channel.description = description
                notificationManager.createNotificationChannel(channel)
                builder = NotificationCompat.Builder(this, NOTIF_LOAD_RULES_FAIL)
            } else {
                builder = NotificationCompat.Builder(this, NOTIF_LOAD_RULES_FAIL)
            }

            val contentTitle = resources.getString(R.string.rules_load_failure_heading)
            val contentText = resources.getString(R.string.rules_load_failure_desc)
            builder.setSmallIcon(R.drawable.dns_icon)
                .setContentTitle(contentTitle)
                .setContentIntent(mainActivityIntent)
                .setContentText(contentText)
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            builder.color = ContextCompat.getColor(this, getThemeAccent(this))
            val openIntent = makeVpnIntent(this, NOTIF_ID_LOAD_RULES_FAIL, Constants.RULES_FAILURE_NOTIFICATION_ACTION)
            val notificationAction: NotificationCompat.Action = NotificationCompat.Action(0, resources.getString(R.string.rules_load_failure_reload), openIntent)
            builder.addAction(notificationAction)

            // Secret notifications are not shown on the lock screen.  No need for this app to show there.
            // Only available in API >= 21
            builder = builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

            builder.build()
            notificationManager.notify(NOTIF_ID_LOAD_RULES_FAIL, builder.build())
        }
    }

    /**
     * Checks if the notification is already available in the status bar.
     */
    private fun isLoadFailureNotificationActive(): Boolean {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        // Recover a list of active notifications: ones that have been posted by the calling app.
        val barNotifications = notificationManager.activeNotifications
        for (notification in barNotifications) {
            if (notification.id == NOTIF_ID_LOAD_RULES_FAIL) {
                return true
            }
        }
        return false
    }

    private fun waitForFirewallRules() {
        // Adding the exponential calculation of wait time until we load the firewall
        // rules. The other check of the block() method will be executed once we load
        // the firewall rules.
        val bgBlockWaitMs = TimeUnit.SECONDS.toMillis(20)
        var remainingWaitMs = TimeUnit.SECONDS.toMillis(10)
        var attempt = 0
        while (remainingWaitMs > 0) {
            if (isFirewallRulesLoaded) {
                if (DEBUG) Log.d(LOG_TAG, "$FILE_LOG_TAG isLoadFirewallRulesCompleted is true")
                break
            } else {
                if (DEBUG) Log.d(LOG_TAG, "$FILE_LOG_TAG isLoadFirewallRulesCompleted is false, trying again")
            }
            remainingWaitMs = exponentialBackoff(remainingWaitMs, attempt)
            attempt += 1
        }

        //When the app is not in foreground.
        if (!isFirewallRulesLoaded) {
            Thread.sleep((bgBlockWaitMs + remainingWaitMs))
        }
    }

    private fun delayBeforeResponse() {
        Thread.sleep(Constants.DELAY_FOR_BLOCK_RESPONSE)
    }

    // ref: https://stackoverflow.com/a/363692
    val baseWaitMs = TimeUnit.MILLISECONDS.toMillis(50)

    private fun exponentialBackoff(remainingWaitMs: Long, attempt: Int): Long {
        var tempRemainingWaitMs = remainingWaitMs
        val exponent = exp(attempt)
        val randomValue = rand.nextLong(exponent - baseWaitMs + 1) + baseWaitMs
        val waitTimeMs = Math.min(randomValue, remainingWaitMs)
        tempRemainingWaitMs -= waitTimeMs
        Thread.sleep(waitTimeMs)
        return tempRemainingWaitMs
    }

    //Exponential calculation - used for app not in use feature (thread wait)
    private fun exp(pow: Int): Long {
        return if (pow == 0) {
            baseWaitMs
        } else {
            (1 shl pow) * baseWaitMs
        }
    }

    private fun killFirewalledApplication(uid: Int) {
        if (DEBUG) Log.i(LOG_TAG, "$FILE_LOG_TAG Firewalled application trying to connect - Kill app is enabled - uid - $uid")
        val activityManager: ActivityManager = this.getSystemService(Activity.ACTIVITY_SERVICE) as ActivityManager
        GlobalScope.launch(Dispatchers.IO) {
            val appUIDList = appInfoRepository.getAppListForUID(uid)
            appUIDList.forEach {
                if (DEBUG) Log.d(LOG_TAG, "$FILE_LOG_TAG app $uid / ${it.packageInfo} killed? ${it.isSystemApp}")
                if (!it.isSystemApp) {
                    activityManager.killBackgroundProcesses(it.packageInfo)
                }
            }
        }
    }

    /**
     * Records the network transaction in local database
     * The logs will be shown in network monitor screen
     */
    private fun connTrack(ipDetails: IPDetails?) {
        if (!persistentState.logsEnabled) return
        ipTracker.recordTransaction(ipDetails)
    }

    /**
     * Checks whether the connection is added in firewall rules.
     */
    private fun isUidBlocked(uid: Int): Boolean {
        return try {
            FirewallManager.checkInternetPermission(uid)
        } catch (e: Exception) {
            false
        }
    }

    override fun getResolvers(): String {
        // TODO: remove stub, unused
        throw UnsupportedOperationException("stub method")
    }

    fun isOn(): Boolean {
        return vpnAdapter != null
    }

    fun newBuilder(): Builder {
        var builder = Builder()

        if (VERSION.SDK_INT >= VERSION_CODES.Q) {
            if (DEBUG) Log.d(LOG_TAG, "$FILE_LOG_TAG isLockDownEnabled - ${this.isLockdownEnabled}, ${this.isAlwaysOn}")
            if (!this.isLockdownEnabled) {
                if (persistentState.allowByPass) {
                    Log.i(LOG_TAG, "$FILE_LOG_TAG getAllowByPass - true")
                    builder = builder.allowBypass()
                }
            }
        }

        // Part of issue fix, internet connection showing metered even if its not the case.
        // (apps lose connectivity during switch over Mobile Data from WiFi)
        // underlying networks is set to null, which prompts Android to set it to whatever is the
        // current active network. Later, ConnectionMonitor#onVpnStarted, depending on user
        // chosen preferences, sets appropriate underlying networks.
        builder.setUnderlyingNetworks(null)

        //Fix - Cloud Backups were failing thinking that the VPN connection is metered.
        //The below code will fix that.
        if (VERSION.SDK_INT >= VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        if (VERSION.SDK_INT >= VERSION_CODES.Q) {
            if (!this.isLockdownEnabled && (persistentState.orbotEnabledMode == Constants.ORBOT_MODE_HTTP || persistentState.orbotEnabledMode == Constants.ORBOT_MODE_BOTH)) {
                getHttpProxyInfo()?.let { builder.setHttpProxy(it) }
            }

            if (persistentState.httpProxyEnabled) {
                getHttpProxyInfo()?.let { builder.setHttpProxy(it) }
            }
        }

        try {
            // Workaround for any app incompatibility bugs.
            //TODO : As of now the wifi  packages are considered for blocking the entire traffic
            if (appMode.getFirewallMode() == Settings.BlockModeSink) {
                if (persistentState.excludedPackagesWifi.isEmpty()) {
                    builder.addAllowedApplication("")
                } else {
                    for (packageName in persistentState.excludedPackagesWifi) {
                        builder = builder.addAllowedApplication(packageName)
                    }
                }
            } else {
                /* The check for the apps to exclude from VPN or not.
                   In case of lockdown mode, the excluded apps wont able to connected. so
                   not including the apps in the excluded list if the lockdown mode is
                   enabled. */
                val excludedApps = persistentState.excludedAppsFromVPN
                if (VERSION.SDK_INT >= VERSION_CODES.Q) {
                    Log.i(LOG_TAG, "$FILE_LOG_TAG exclude apps - isLockdownEnabled - ${this.isLockdownEnabled}")
                    if (!this.isLockdownEnabled) {
                        excludedApps.forEach {
                            builder = builder.addDisallowedApplication(it)
                            Log.i(LOG_TAG, "$FILE_LOG_TAG Excluded package - $it")
                        }
                    }
                } else {
                    excludedApps.forEach {
                        builder = builder.addDisallowedApplication(it)
                        Log.i(LOG_TAG, "$FILE_LOG_TAG Excluded package - $it")
                    }
                }
                builder = builder.addDisallowedApplication(this.packageName)
            }
            Log.i(LOG_TAG, "$FILE_LOG_TAG Proxy mode set to Socks5 ${appMode.getProxyMode()}")
            if (appMode.getProxyMode() == Settings.ProxyModeSOCKS5) {
                //For Socks5 if there is a app selected, add that app in excluded list

                val socks5ProxyEndpoint = socks5ProxyEndpointRepository.getConnectedProxy()
                if (socks5ProxyEndpoint != null) {
                    if (!socks5ProxyEndpoint.proxyAppName.isNullOrEmpty() && socks5ProxyEndpoint.proxyAppName != "Nobody") {
                        Log.i(LOG_TAG, "$FILE_LOG_TAG Proxy mode set to Socks5 with the app - ${socks5ProxyEndpoint.proxyAppName!!}- added to excluded list")
                        builder = builder.addDisallowedApplication(socks5ProxyEndpoint.proxyAppName!!)
                    }
                } else {
                    Log.i(LOG_TAG, "$FILE_LOG_TAG Proxy mode not set to Socks5 with the app - null - added to excluded list")
                }
            }
            if (VERSION.SDK_INT >= VERSION_CODES.Q) {
                if (!this.isLockdownEnabled) {
                    if (appMode.getProxyMode() == Constants.ORBOT_SOCKS) {
                        builder = builder.addDisallowedApplication(OrbotHelper.ORBOT_PACKAGE_NAME)
                    }
                }
            }

            if (appMode.getDNSType() == 3) {
                try {
                    //For DNS proxy mode, if any app is set then exclude the application from the list
                    val dnsProxyEndpoint = dnsProxyEndpointRepository.getConnectedProxy()
                    Log.i(LOG_TAG, "$FILE_LOG_TAG DNS Proxy mode is set with the app name as ${dnsProxyEndpoint.proxyAppName!!}")
                    if (!dnsProxyEndpoint.proxyAppName.isNullOrEmpty() && dnsProxyEndpoint.proxyAppName != "Nobody") {
                        builder = builder.addDisallowedApplication(dnsProxyEndpoint.proxyAppName!!)
                    }
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Exception while excluding the proxy app from VPN", e)
                }
            }

        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(LOG_TAG, "$FILE_LOG_TAG Failed to exclude an app", e)
        }
        return builder
    }


    override fun onCreate() {
        connTracer = ConnectionTracer(this)
        vpnController!!.setBraveVpnService(this)
    }

    private fun getHttpProxyInfo(): ProxyInfo? {
        val proxyInfo: ProxyInfo?
        val host = persistentState.httpProxyHostAddress
        val port = persistentState.httpProxyPort
        Log.i(LOG_TAG, "$FILE_LOG_TAG Http proxy enabled - builder updated with $host, $port")
        if (host.isNotEmpty() && port != 0) {
            proxyInfo = ProxyInfo.buildDirectProxy(host, port)
            return proxyInfo
        }
        return null
    }

    private fun registerScreenStateReceiver() {
        if (DEBUG) Log.d(LOG_TAG, "$FILE_LOG_TAG Registering for the intent filter for ACTION_SCREEN_OFF,ON and USER_PRESENT")
        val actionFilter = IntentFilter()
        actionFilter.addAction(Intent.ACTION_SCREEN_OFF)
        actionFilter.addAction(Intent.ACTION_USER_PRESENT)
        actionFilter.addAction(Intent.ACTION_SCREEN_ON)
        registerReceiver(braveScreenStateReceiver, actionFilter)
    }

    private fun registerBootCompleteReceiver() {
        val autoStartFilter = IntentFilter()
        autoStartFilter.addAction(Intent.ACTION_BOOT_COMPLETED)
        registerReceiver(braveAutoStartReceiver, autoStartFilter)
    }

    private fun updateBuilder(context: Context): NotificationCompat.Builder {
        val mainActivityIntent = PendingIntent.getActivity(context, 0, Intent(context, HomeScreenActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT)
        var builder: NotificationCompat.Builder
        if (VERSION.SDK_INT >= VERSION_CODES.O) {
            val name: CharSequence = context.resources.getString(R.string.app_name)
            val description = context.resources.getString(R.string.notification_content)
            // LOW is the lowest importance that is allowed with startForeground in Android O.
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(MAIN_CHANNEL_ID, name, importance)
            channel.description = description
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
            builder = NotificationCompat.Builder(context, MAIN_CHANNEL_ID)
        } else {
            builder = NotificationCompat.Builder(context, MAIN_CHANNEL_ID)
        }

        val contentTitle: String = when (persistentState.getBraveMode()) {
            Constants.APP_MODE_DNS -> context.resources.getString(R.string.dns_mode_notification_title)
            Constants.APP_MODE_FIREWALL -> context.resources.getString(R.string.firewall_mode_notification_title)
            Constants.APP_MODE_DNS_FIREWALL -> context.resources.getString(R.string.hybrid_mode_notification_title)
            else -> context.resources.getString(R.string.notification_title)
        }

        builder.setSmallIcon(R.drawable.dns_icon).setContentTitle(contentTitle).setContentIntent(mainActivityIntent)

        // New action button options in the notification
        // 1. STOP action button.
        // 2. RethinkDNS modes (DNS & DNS+Firewall mode)
        // 3. No action button.
        if (DEBUG) Log.d(LOG_TAG, "$FILE_LOG_TAG Notification - ${persistentState.notificationAction}")
        builder.color = ContextCompat.getColor(this, getThemeAccent(this))
        when (persistentState.notificationAction) {
            Constants.NOTIFICATION_ACTION_STOP -> {
                val openIntent = makeVpnIntent(context, NOTIF_ACTION_MODE_STOP, Constants.STOP_VPN_NOTIFICATION_ACTION)
                val notificationAction: NotificationCompat.Action = NotificationCompat.Action(0, context.resources.getString(R.string.notification_action_stop_vpn), openIntent)
                builder.addAction(notificationAction)
            }
            Constants.NOTIFICATION_ACTION_DNS_FIREWALL -> {
                val openIntent1 = makeVpnIntent(context, NOTIF_ACTION_MODE_DNS_ONLY, Constants.DNS_VPN_NOTIFICATION_ACTION)
                val openIntent2 = makeVpnIntent(context, NOTIF_ACTION_MODE_DNS_FIREWALL, Constants.DNS_FIREWALL_VPN_NOTIFICATION_ACTION)
                val notificationAction: NotificationCompat.Action = NotificationCompat.Action(0, context.resources.getString(R.string.notification_action_dns_mode), openIntent1)
                val notificationAction2: NotificationCompat.Action = NotificationCompat.Action(0, context.resources.getString(R.string.notification_action_dns_firewall_mode), openIntent2)
                builder.addAction(notificationAction)
                builder.addAction(notificationAction2)
            }
            Constants.NOTIFICATION_ACTION_NONE -> {
                Log.i(LOG_TAG, "$FILE_LOG_TAG No notification action")
            }
        }

        // Secret notifications are not shown on the lock screen.  No need for this app to show there.
        // Only available in API >= 21
        builder = builder.setVisibility(NotificationCompat.VISIBILITY_SECRET)
        builder.build()
        return builder
    }

    private fun makeVpnIntent(context: Context, notificationID: Int, intentExtra: String): PendingIntent? {
        val intentAction = Intent(context, NotificationActionReceiver::class.java)
        intentAction.putExtra(Constants.NOTIFICATION_ACTION, intentExtra)
        return PendingIntent.getBroadcast(context, notificationID, intentAction, PendingIntent.FLAG_UPDATE_CURRENT)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        vpnScope.launch {
            loadAppFirewallRules()
        }
        appInfoRepository.getUIDForUnivWhiteList().observeForever { appInfoList ->
            appWhiteList = appInfoList.associateBy({ it }, { true }).toMutableMap()
        }
        if (persistentState.orbotEnabledMode != Constants.ORBOT_MODE_NONE) {
            get<OrbotHelper>().startOrbot(this)
        }
        if (persistentState.isBackgroundEnabled) {
            registerAccessibilityServiceState()
        }
        if (persistentState.isScreenOff) {
            registerScreenStateReceiver()
        }
        registerBootCompleteReceiver()
        if (vpnController != null) {
            synchronized(vpnController) {
                if (DEBUG) Log.d(LOG_TAG, "$FILE_LOG_TAG Registering the shared pref changes with the vpn service")
                persistentState.sharedPreferences.registerOnSharedPreferenceChangeListener(this)

                // In case if the service already running(connectionMonitor will not be null)
                // and onStartCommand is called then no need to process the below initializations
                // instead call spawnServerUpdate()
                // spawnServerUpdate() - Will overwrite the tunnel values with new values.
                if (connectionMonitor != null) {
                    spawnServerUpdate()
                    return Service.START_REDELIVER_INTENT
                }

                // Register for ConnectivityManager
                // FIXME #200 - Move it to Koin
                connectionMonitor = ConnectionMonitor(this, this)

                // Part of issue fix(#242) - StartVPN thread that for some reason is waiting
                // Avoiding the call from ConnectivityManager to invoke startVPN is removed.
                // RestartVPN is executed when the VPNService - onStartCommand is called.
                // Added as part of testing.
                restartVpn(appMode.getDNSMode(), appMode.getFirewallMode(), appMode.getProxyMode())

                // Mark this as a foreground service.  This is normally done to ensure that the service
                // survives under memory pressure.  Since this is a VPN service, it is presumably protected
                // anyway, but the foreground service mechanism allows us to set a persistent notification,
                // which helps users understand what's going on, and return to the app if they want.

                val builder = updateBuilder(this)
                Log.i(LOG_TAG, "$FILE_LOG_TAG onStart command - start as foreground service. ")
                startForeground(SERVICE_ID, builder.build())
                updateQuickSettingsTile()
                persistentState.vpnEnabled = true
                connectionMonitor?.onVpnStarted()
            }
        } else {
            Log.i(LOG_TAG, "$FILE_LOG_TAG onStart command not initiated - vpnController is null ")
        }
        return Service.START_REDELIVER_INTENT
    }

    suspend fun loadAppFirewallRules() {
        withContext(Dispatchers.IO) {
            val appDetailsFromDB = appInfoRepository.getAppInfoAsync()
            appDetailsFromDB.forEach {
                HomeScreenActivity.GlobalVariable.appList[it.packageInfo] = it
                if (!it.isInternetAllowed) {
                    HomeScreenActivity.GlobalVariable.blockedUID[it.uid] = false
                }
            }
            isFirewallRulesLoaded = true
        }
    }

    private fun registerAccessibilityServiceState() {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        am.addAccessibilityStateChangeListener { b ->
            val isServiceEnabled = Utilities.isAccessibilityServiceEnabledEnhanced(this, BackgroundAccessibilityService::class.java)
            if (!b || !isServiceEnabled) {
                persistentState.isBackgroundEnabled = false
            }
        }
    }

    private fun spawnServerUpdate() {
        if (vpnController != null) {
            synchronized(vpnController) {
                // Connection monitor can be null if onDestroy() of service
                // is called, in that case no need to call updateServerConnection()
                if (connectionMonitor != null) {
                    Thread({ updateServerConnection() }, "updateServerConnection-onStartCommand").start()
                }
            }
        }
    }

    @WorkerThread private fun updateServerConnection() {
        vpnAdapter?.updateDohUrl(appMode.getDNSMode(), appMode.getFirewallMode(), appMode.getProxyMode())
    }

    fun recordTransaction(transaction: Transaction) {
        transaction.responseCalendar = Calendar.getInstance()
        // All the transactions are recorded in the DNS logs.
        // Quantile estimation correction - Not adding the transactions with server IP
        // as null in the quantile estimator.
        if (!transaction.serverIp.isNullOrEmpty()) {
            tracker.recordTransaction(transaction)
        }
        if (persistentState.logsEnabled) {
            dnsLogTracker.recordTransaction(transaction)
        }

        if (DEBUG) Log.d(LOG_TAG, "$FILE_LOG_TAG Record Transaction: status as ${transaction.status} with blocklist ${transaction.blockList}")
        // Update the connection state.  If the transaction succeeded, then the connection is working.
        // If the transaction failed, then the connection is not working.
        // If the transaction was canceled, then we don't have any new information about the status
        // of the connection, so we don't send an update.
        // commented the code for reporting good or bad network.
        if (transaction.status === Transaction.Status.COMPLETE) {
            vpnController!!.onConnectionStateChanged(this, State.WORKING)

        } else if (transaction.status !== Transaction.Status.CANCELED) {
            vpnController!!.onConnectionStateChanged(this, State.FAILING)
        }
    }

    private fun setCryptMode() {
        vpnAdapter!!.setCryptMode()
    }

    override fun onSharedPreferenceChanged(preferences: SharedPreferences?, key: String?) {
        /*TODO Check on the Persistent State variable
            Check on updating the values for Package change and for mode change.
           As of now handled manually*/
        Log.i(LOG_TAG, "$FILE_LOG_TAG - SharedPref- $key - ${appMode.getDNSMode()}, with " + "notification action -${persistentState.notificationAction}, proxy - ${appMode.getProxyMode()}")
        when (key) {
            PersistentState.BRAVE_MODE -> {
                if (vpnAdapter == null) {
                    Log.i(LOG_TAG, "$FILE_LOG_TAG vpnAdapter is null nothing to do on firewall/dns mode changes")
                    return
                }
                restartVpn(appMode.getDNSMode(), appMode.getFirewallMode(), appMode.getProxyMode())
                val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                val builder = updateBuilder(this)
                notificationManager.notify(SERVICE_ID, builder.build())
            }
            PersistentState.DNS_TYPE -> {
                /*
                 * Handles the DNS type changes.
                 * DNS Proxy - Requires restart of the VPN.
                 * DNSCrypt - Set the tunnel with DNSCrypt mode once the live servers size is not 0.
                 * DOH - Overwrites the tunnel values with new values.
                 */
                when (appMode.getDNSType()) {
                    Constants.PREF_DNS_MODE_PROXY -> {
                        restartVpn(appMode.getDNSMode(), appMode.getFirewallMode(), appMode.getProxyMode())
                    }
                    Constants.PREF_DNS_MODE_DNSCRYPT -> {
                        setCryptMode()
                    }
                    else -> {
                        spawnServerUpdate()
                    }
                }
            }
            PersistentState.PROXY_MODE -> {
                restartVpn(appMode.getDNSMode(), appMode.getFirewallMode(), appMode.getProxyMode())
            }
            PersistentState.CONNECTION_CHANGE -> {
                spawnServerUpdate()
            }
            PersistentState.EXCLUDE_FROM_VPN -> {
                restartVpn(appMode.getDNSMode(), appMode.getFirewallMode(), appMode.getProxyMode())
            }
            PersistentState.IS_SCREEN_OFF -> {
                if (persistentState.isScreenOff) {
                    registerScreenStateReceiver()
                }
            }
            PersistentState.LOCAL_BLOCK_LIST -> {
                spawnServerUpdate()
            }
            PersistentState.BACKGROUND_MODE -> {
                if (persistentState.isBackgroundEnabled) {
                    registerAccessibilityServiceState()
                }
            }
            PersistentState.LOCAL_BLOCK_LIST_STAMP -> {
                spawnServerUpdate()
            }
            PersistentState.ALLOW_BYPASS -> {
                restartVpn(appMode.getDNSMode(), appMode.getFirewallMode(), appMode.getProxyMode())
            }
            PersistentState.HTTP_PROXY_ENABLED -> {
                restartVpn(appMode.getDNSMode(), appMode.getFirewallMode(), appMode.getProxyMode())
            }
            PersistentState.ORBOT_MODE_CHANGE -> {
                restartVpn(appMode.getDNSMode(), appMode.getFirewallMode(), appMode.getProxyMode())
            }
            PersistentState.NETWORK -> {
                connectionMonitor?.onUserPreferenceChanged()
            }
            PersistentState.NOTIFICATION_ACTION -> {
                val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                val builder = updateBuilder(this)
                notificationManager.notify(SERVICE_ID, builder.build())
            }
        }
    }

    fun signalStopService(userInitiated: Boolean) {
        if (!userInitiated) {
            val vibrationPattern = longArrayOf(1000) // Vibrate for one second.
            // Show revocation warning
            val builder: NotificationCompat.Builder
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (VERSION.SDK_INT >= VERSION_CODES.O) {
                val name: CharSequence = getString(R.string.warning_channel_name)
                val description = getString(R.string.warning_channel_description)
                val importance = NotificationManager.IMPORTANCE_HIGH
                val channel = NotificationChannel(WARNING_CHANNEL_ID, name, importance)
                channel.description = description
                channel.enableVibration(true)
                channel.vibrationPattern = vibrationPattern
                notificationManager.createNotificationChannel(channel)
                builder = NotificationCompat.Builder(this, WARNING_CHANNEL_ID)
            } else {
                builder = NotificationCompat.Builder(this, WARNING_CHANNEL_ID)
                builder.setVibrate(vibrationPattern)
            }
            val mainActivityIntent = PendingIntent.getActivity(this, 0, Intent(this, HomeScreenActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT)
            builder.setSmallIcon(R.drawable.dns_icon)
                .setContentTitle(resources.getText(R.string.warning_title))
                .setContentText(resources.getText(R.string.notification_content))
                .setContentIntent(mainActivityIntent)
                // Open the main UI if possible.
                .setAutoCancel(true)
            notificationManager.notify(0, builder.build())
        }
        stopVpnAdapter()
        stopSelf()

        Log.i(LOG_TAG, "$FILE_LOG_TAG Stop Foreground")
        updateQuickSettingsTile()
    }

    private fun updateQuickSettingsTile() {
        if (VERSION.SDK_INT >= VERSION_CODES.N) {
            requestListeningState(this, ComponentName(this, BraveTileService::class.java))
        }
    }

    private fun stopVpnAdapter() {
        if (persistentState.orbotMode != Constants.ORBOT_MODE_NONE) get<OrbotHelper>().unregisterReceiver(this)
        if (vpnController != null) {
            synchronized(vpnController) {
                if (vpnAdapter != null) {
                    vpnAdapter!!.close()
                    vpnAdapter = null
                    vpnController.stop(this)
                    vpnController.getState().activationRequested = false
                    persistentState.vpnEnabled = false
                    vpnController.onConnectionStateChanged(this, null)
                    Log.e(LOG_TAG, "$FILE_LOG_TAG Stop Called - stopVpnAdapter closed all states")
                }
            }
        } else {
            Log.e(LOG_TAG, "$FILE_LOG_TAG Stop Called with VPN Controller as null")
        }
    }

    private fun restartVpn(dnsModeL: Long, firewallModeL: Long, proxyMode: Long) {
        Log.i(LOG_TAG, "$FILE_LOG_TAG is vpnController null? ${vpnController == null}")
        if (vpnController == null) return
        synchronized(vpnController) {
            Thread({
                // Attempt seamless hand off as described in the docs for VpnService.Builder.establish().
                val oldAdapter: GoVpnAdapter? = vpnAdapter
                vpnAdapter = makeVpnAdapter()
                oldAdapter?.close()
                Log.i(LOG_TAG, "$FILE_LOG_TAG restartVpn? ${vpnAdapter == null}")
                if (vpnAdapter != null) {
                    vpnAdapter!!.start(dnsModeL, firewallModeL, proxyMode)
                } else {
                    signalStopService(false)
                }
            }, "restartvpn-onCommand").start()
        }
    }

    private fun makeVpnAdapter(): GoVpnAdapter? {
        return GoVpnAdapter.establish(this, get(), get(), get(), get(), get())
    }

    // TODO: #294 - Figure out a way to show users that the device is offline instead of status as failing.
    override fun onNetworkDisconnected() {
        Log.i(LOG_TAG, "$FILE_LOG_TAG #onNetworkDisconnected: Underlying networks set to null, controller-state set to failing")
        setUnderlyingNetworks(null)
        vpnController!!.onConnectionStateChanged(this, State.FAILING)
    }

    override fun onNetworkConnected(networks: LinkedHashSet<Network>?) {
        Log.i(LOG_TAG, "$FILE_LOG_TAG connecting to networks: $networks")
        setUnderlyingNetworks(networks?.toTypedArray())
    }

    private fun checkLockDown() {
        if (VERSION.SDK_INT < VERSION_CODES.Q) return
        if (DEBUG) Log.d(LOG_TAG, "BraveVPNService isLockDownEnabled - ${isLockdownEnabled}, prev value -$isLockDownPrevious")
        if (isLockdownEnabled != isLockDownPrevious) {
            updateLockdown()
        }
    }

    @RequiresApi(VERSION_CODES.Q) private fun updateLockdown() {
        isLockDownPrevious = isLockdownEnabled
        // Introducing the lockdown mode and Orbot - proxy mode for the Orbot one touch
        // configuration. When the lockdown mode is enabled, the exclusion of Orbot will
        // be avoided which will result in no internet connectivity.
        // FIXME #200 This is temp change, the changes are need to be moved out of capabilities once the
        // appMode variable is removed.
        if (isLockdownEnabled && appMode.getProxyMode() == Constants.ORBOT_SOCKS) {
            if (DEBUG) Log.d(LOG_TAG, "isLockDownEnabled - True, ORBOT is socks5 - restart with proxy mode none")
            restartVpn(appMode.getDNSMode(), appMode.getFirewallMode(), Settings.ProxyModeNone)
        } else {
            if (DEBUG) Log.d(LOG_TAG, "isLockDownEnabled - False, ORBOT is ${appMode.getProxyMode()} - restart with set proxy mode")
            restartVpn(appMode.getDNSMode(), appMode.getFirewallMode(), appMode.getProxyMode())
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(braveScreenStateReceiver)
            get<OrbotHelper>().unregisterReceiver(this)
        } catch (e: java.lang.Exception) {
            Log.w(LOG_TAG, "$FILE_LOG_TAG Unregister receiver error: ${e.message}", e)
        }

        synchronized(vpnController!!) {
            Log.w(LOG_TAG, "$FILE_LOG_TAG Destroying DNS VPN service")
            persistentState.sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
            connectionMonitor?.removeCallBack()
            vpnController.setBraveVpnService(null)
            stopForeground(true)
            if (vpnAdapter != null) {
                signalStopService(false)
            }
        }
        try {
            unregisterReceiver(braveAutoStartReceiver)
        } catch (e: java.lang.Exception) {
            Log.w(LOG_TAG, "$FILE_LOG_TAG Unregister receiver error: ${e.message}", e)
        }
    }

    override fun onRevoke() {
        stopSelf()
        persistentState.vpnEnabled = false
    }

}
