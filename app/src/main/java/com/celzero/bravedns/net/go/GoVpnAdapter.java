/*
Copyright 2020 RethinkDNS developers

Copyright 2019 Jigsaw Operations LLC

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package com.celzero.bravedns.net.go;

import android.content.Context;
import android.content.res.Resources;
import android.net.VpnService;
import android.os.AsyncTask;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.celzero.bravedns.R;
import com.celzero.bravedns.data.AppMode;
import com.celzero.bravedns.database.DNSCryptEndpointRepository;
import com.celzero.bravedns.database.DNSProxyEndpoint;
import com.celzero.bravedns.database.DNSProxyEndpointRepository;
import com.celzero.bravedns.database.DoHEndpoint;
import com.celzero.bravedns.database.DoHEndpointRepository;
import com.celzero.bravedns.database.ProxyEndpoint;
import com.celzero.bravedns.service.BraveVPNService;
import com.celzero.bravedns.service.PersistentState;
import com.celzero.bravedns.service.VpnController;
import com.celzero.bravedns.ui.HomeScreenActivity;
import com.celzero.bravedns.util.Constants;
import com.celzero.bravedns.util.Utilities;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Locale;

import dnsx.BraveDNS;
import dnsx.Dnsx;
import doh.Transport;
import intra.Tunnel;
import protect.Blocker;
import protect.Protector;
import settings.Settings;
import tun2socks.Tun2socks;

import static com.celzero.bravedns.ui.HomeScreenFragment.DNS_MODE;
import static com.celzero.bravedns.ui.HomeScreenFragment.FIREWALL_MODE;
import static com.celzero.bravedns.util.Constants.LOG_TAG;

//import tunnel.IntraTunnel;

/**
 * This is a VpnAdapter that captures all traffic and routes it through a go-tun2socks instance with
 * custom logic for Intra.
 */
public class GoVpnAdapter {
    // This value must match the hardcoded MTU in outline-go-tun2socks.
    // TODO: Make outline-go-tun2socks's MTU configurable.
    private static final int VPN_INTERFACE_MTU = 1500;
    private static final int DNS_DEFAULT_PORT = 53;

    // IPv4 VPN constants
    private static final String IPV4_TEMPLATE = "10.111.222.%d";
    private static final int IPV4_PREFIX_LENGTH = 24;

    private long firewallMode = -1;

    // The VPN service and tun2socks must agree on the layout of the network.  By convention, we
    // assign the following values to the final byte of an address within a subnet.
    private enum LanIp {
        GATEWAY(1), ROUTER(2), DNS(3);

        // Value of the final byte, to be substituted into the template.
        private final int value;

        LanIp(int value) {
            this.value = value;
        }

        String make(String template) {
            return String.format(Locale.ROOT, template, value);
        }
    }

    public static final String FAKE_DNS_IP = LanIp.DNS.make(IPV4_TEMPLATE);

    // Service context in which the VPN is running.
    private final BraveVPNService vpnService;

    // TUN device representing the VPN.
    private ParcelFileDescriptor tunFd;

    // The Intra session object from go-tun2socks.  Initially null.
    private Tunnel tunnel;

    private GoIntraListener listener;

    private final AppMode appMode;
    @NonNull private final DNSProxyEndpointRepository dnsProxyEndpointRepository;
    @NonNull private final DNSCryptEndpointRepository dnsCryptEndpointRepository;
    @NonNull private final DoHEndpointRepository doHEndpointRepository;
    @NonNull private final PersistentState persistentState;

    public static GoVpnAdapter establish(@NonNull BraveVPNService vpnService,
                                         @NonNull AppMode appMode,
                                         @NonNull DNSProxyEndpointRepository dnsProxyEndpointRepository,
                                         @NonNull DNSCryptEndpointRepository dnsCryptEndpointRepository,
                                         @NonNull DoHEndpointRepository doHEndpointRepository,
                                         @NonNull PersistentState persistentState) {
        ParcelFileDescriptor tunFd = establishVpn(vpnService);

        if (tunFd == null) {
            return null;
        }
        return new GoVpnAdapter(vpnService, tunFd, appMode, dnsProxyEndpointRepository, dnsCryptEndpointRepository, doHEndpointRepository, persistentState);
    }

    private GoVpnAdapter(BraveVPNService vpnService, ParcelFileDescriptor tunFd,
                         AppMode appMode,
                         @NotNull DNSProxyEndpointRepository dnsProxyEndpointRepository,
                         @NotNull DNSCryptEndpointRepository dnsCryptEndpointRepository,
                         @NotNull DoHEndpointRepository doHEndpointRepository,
                         @NotNull PersistentState persistentState) {
        this.vpnService = vpnService;
        this.tunFd = tunFd;
        this.appMode = appMode;

        this.dnsProxyEndpointRepository = dnsProxyEndpointRepository;
        this.dnsCryptEndpointRepository = dnsCryptEndpointRepository;
        this.doHEndpointRepository = doHEndpointRepository;
        this.persistentState = persistentState;
    }

    public synchronized void start(Long dnsMode, Long blockMode, Long proxyMode) {
        connectTunnel(dnsMode, blockMode, proxyMode);
    }

    private void connectTunnel(Long iDnsMode, Long iBlockMode, Long iProxyMode) {
        if (tunnel != null) {
            return;
        }
        // VPN parameters
        final String fakeDns = FAKE_DNS_IP + ":" + DNS_DEFAULT_PORT;

        // Strip leading "/" from ip:port string.
        listener = new GoIntraListener(vpnService);
        //TODO : The below statement is incorrect, adding the dohURL as const for testing

        try {


            String dohURL = "https://free.bravedns.com/dns-query";
            try{
                if(appMode.getDOHDetails() != null) {
                    dohURL = appMode.getDOHDetails().getDohURL();
                }
            }catch(Exception e){
                Log.w(LOG_TAG,"GoVPNAdapter appMode.getDOHDetails() is null:" +e.getMessage() ,e);
            }

            Log.i(LOG_TAG,"GoVPNAdapter DoHURL - "+dohURL);
            Transport transport = makeDohTransport(dohURL);

            firewallMode = iBlockMode;

            Log.i(LOG_TAG,"GoVPNAdapter Connect tunnel with url "+dohURL);
            tunnel = Tun2socks.connectIntraTunnel(tunFd.getFd(), fakeDns,
                transport, getProtector(), getBlocker(), listener);

            if(iDnsMode == Settings.DNSModeIP || iDnsMode == Settings.DNSModePort || iDnsMode == Settings.DNSModeNone) {
                //To set bravedns mode- two modes
                //Mode local blocklist
                //Mode Remote blocklist
                Boolean isLocalSet = setBraveDNSLocalMode();
                if (!isLocalSet && dohURL.contains(Constants.BRAVE_BASIC_URL)) {
                    Log.i(LOG_TAG, "GoVPNAdapter Set stamp for remote url :" + dohURL);
                    setBraveDNSRemoteMode(dohURL);
                }
            }

            if (HomeScreenActivity.GlobalVariable.INSTANCE.getDEBUG()) {
                Tun2socks.enableDebugLog();
            }
            int proxyMode = (int) appMode.getProxyMode();
            if (HomeScreenActivity.GlobalVariable.INSTANCE.getDEBUG())
                Log.d(LOG_TAG, "GoVPNAdapter Connected to tunnel with DNSMODE - " + iDnsMode + ", blockMode-" + iBlockMode + ", proxyMode-" + proxyMode);
            if (iDnsMode != Settings.DNSModeCryptIP && iDnsMode != Settings.DNSModeCryptPort) {
                if(proxyMode == Constants.ORBOT_SOCKS){
                    tunnel.setTunMode(iDnsMode, iBlockMode, Settings.ProxyModeSOCKS5);
                }else {
                    tunnel.setTunMode(iDnsMode, iBlockMode, proxyMode);
                }
                checkForCryptRemoval();
                if (iDnsMode == Settings.DNSModeProxyIP || iDnsMode == Settings.DNSModeProxyPort) {
                    Log.i(LOG_TAG, "GoVPNAdapter dnsMode mode - " + iDnsMode);
                    setDNSProxy();
                }

                if (proxyMode == Settings.ProxyModeSOCKS5 || proxyMode == Constants.ORBOT_SOCKS) {
                    setSocks5TunnelMode();
                }
            } else {
                setCryptMode();
            }

            Log.i(LOG_TAG,"GoVPNAdapter dnsMode mode - "+iDnsMode);


        } catch (Exception e) {
            Log.e(LOG_TAG, "GoVPNAdapter: "+e.getMessage(), e);
            VpnController.Companion.getInstance().onConnectionStateChanged(vpnService, BraveVPNService.State.FAILING);
        }
    }


    public void checkForCryptRemoval(){
        if(HomeScreenActivity.GlobalVariable.INSTANCE.getDEBUG())
            Log.d(LOG_TAG,"Close crypt call initiated");
        try{
            if (tunnel.getDNSCryptProxy() != null) {
                tunnel.stopDNSCryptProxy();
                if(HomeScreenActivity.GlobalVariable.INSTANCE.getDEBUG())
                            Log.d(LOG_TAG,"GoVPNAdapter Completed - stopDNSCryptProxy");
            }
        }catch (Exception e){
            Log.e(LOG_TAG,"GoVPNAdapter Exception while removing crypt proxy: "+e.getMessage(), e);
        }

    }

    public void setCryptMode() {
        if (HomeScreenActivity.GlobalVariable.INSTANCE.getAppMode() == null) {
            HomeScreenActivity.GlobalVariable.INSTANCE.setAppMode(appMode);
        }
        String servers = HomeScreenActivity.GlobalVariable.INSTANCE.getAppMode().getDNSCryptServers();
        String routes = HomeScreenActivity.GlobalVariable.INSTANCE.getAppMode().getDNSCryptRelays();
        String serversIndex = HomeScreenActivity.GlobalVariable.INSTANCE.getAppMode().getDNSCryptServerToRemove();
        try {
            if(tunnel.getDNSCryptProxy() == null){
                String response;
                Log.i(LOG_TAG, "GoVPNAdapter startDNSCryptProxy: Before startCrypt" + servers + "--"+ routes) ;
                response = tunnel.startDNSCryptProxy(servers, routes, listener);
                Log.i(LOG_TAG, "GoVPNAdapter startDNSCryptProxy: " + servers + "--"+ routes +" - Response: "+response) ;
            }else{
                long serverCount= 0L, relayCount = 0L , rel = 0L;
                String serversToRemove = Utilities.Companion.prepareServersToRemove(tunnel.getDNSCryptProxy().liveServers(),serversIndex);
                if(!serversToRemove.isEmpty())
                    serverCount = tunnel.getDNSCryptProxy().removeServers(serversToRemove);
                if(!HomeScreenActivity.GlobalVariable.INSTANCE.getCryptRelayToRemove().isEmpty()){
                    rel =  tunnel.getDNSCryptProxy().removeRoutes(HomeScreenActivity.GlobalVariable.INSTANCE.getCryptRelayToRemove());
                    HomeScreenActivity.GlobalVariable.INSTANCE.setCryptRelayToRemove("");
                }
                if(!routes.isEmpty())
                    relayCount=  tunnel.getDNSCryptProxy().removeRoutes(routes);
                tunnel.getDNSCryptProxy().addServers(servers);
                if(!routes.isEmpty())
                    tunnel.getDNSCryptProxy().addRoutes(routes);

                Log.i(LOG_TAG,"GoVPNAdapter DNSCrypt routes: "+routes+", removed relay count:"+rel +relayCount);
                Log.i(LOG_TAG,"GoVPNAdapter DNSCrypt add servers: "+servers+" removed count:"+serverCount );
            }
            if(servers.length() > 0) {
                RefreshOperation runningTask = new RefreshOperation();
                runningTask.execute();
            }
            Log.i(LOG_TAG, "GoVPNAdapter setCryptMode - Connected to tunnel with DNSMODE - " + Settings.DNSModeCryptPort + " - blockMode-" + Settings.BlockModeFilter);
        } catch (Exception ex) {
            Log.e(LOG_TAG, "GoVPNAdapter celzero connect-tunnel: dns crypt", ex);
            if(servers.length() > 0) {
                RefreshOperation runningTask = new RefreshOperation();
                runningTask.execute();
            }
        }
    }

    private void setDNSProxy(){
        try{
            DNSProxyEndpoint dnsProxy = dnsProxyEndpointRepository.getConnectedProxy();
            if(dnsProxy != null) {
                if (HomeScreenActivity.GlobalVariable.INSTANCE.getDEBUG())
                    Log.d(LOG_TAG, "GoVPNAdapter setDNSProxy mode set - " + dnsProxy.getProxyIP() + "," + dnsProxy.getProxyPort() + "");
                tunnel.startDNSProxy(dnsProxy.getProxyIP(), dnsProxy.getProxyPort() + "");
            }
        }catch (Exception e){
            Log.e(LOG_TAG, "GoVPNAdapter celzero connect-tunnel: dns proxy"+e.getMessage(), e);
        }
    }

    /**
     * TODO - Move these code to common place and set the tunnel mode and
     * other parameters. Return the tunnel to the adapter.
     */
    private void setProxyMode(String userName, String password , String ipAddress, int port){
        try{
           tunnel.startProxy(userName, password, ipAddress, port+"");
           Log.i(LOG_TAG,"GoVPNAdapter Proxy mode set - "+userName + ipAddress + port+"");
        }catch(Exception e){
            Log.e(LOG_TAG,"GoVPNAdapter celzero-connect-tunnel: proxy",e);
        }
    }


    private final class RefreshOperation extends AsyncTask<Void, Void, String> {

        @Override
        protected String doInBackground(Void... params) {
            try {
                if (tunnel.getDNSCryptProxy() != null) {
                    String liveServers = tunnel.getDNSCryptProxy().refresh();
                    dnsCryptEndpointRepository.updateConnectionStatus(liveServers);
                    if (HomeScreenActivity.GlobalVariable.INSTANCE.getDEBUG())
                        Log.d(LOG_TAG, "GoVPNAdapter Refresh LiveServers -- " + liveServers);

                    // FIXME: 08-01-2021 - Change the redundant usage of same code in the different places.
                    if(liveServers.isEmpty()){
                        Log.i(LOG_TAG,"GoVPNAdapter No live servers - falling back to default DoH mode");
                        tunnel.stopDNSCryptProxy();
                        dnsCryptEndpointRepository.updateFailingConnections();
                        DoHEndpoint doHEndpoint = doHEndpointRepository.updateConnectionDefault();
                        persistentState.setDnsType(Constants.DNS_TYPE_DNS_CHANGE);
                        if(doHEndpoint != null)
                            persistentState.setConnectionModeChange(doHEndpoint.getDohURL());
                        persistentState.setConnectedDNS(doHEndpoint.getDohName());
                        AppMode appMode = HomeScreenActivity.GlobalVariable.INSTANCE.getAppMode();
                        if (appMode != null) {
                            appMode.setDNSMode(Settings.DNSModePort);
                        }
                        Handler handler = new Handler(vpnService.getMainLooper());
                        handler.post(new Runnable() {
                            public void run() {
                                Utilities.Companion.showToastInMidLayout(vpnService, "Error connecting to DNSCrypt server.", Toast.LENGTH_SHORT);
                            }
                        });
                    }else{
                        if(persistentState.getDnsType() == Constants.DNS_TYPE_CRYPT_CHANGE) {
                            int proxyMode = (int) appMode.getProxyMode();
                            tunnel.setTunMode(Settings.DNSModeCryptPort, firewallMode, appMode.getProxyMode());
                            if (proxyMode == Settings.ProxyModeSOCKS5 || proxyMode == Constants.ORBOT_SOCKS) {
                                setSocks5TunnelMode();
                            }
                            Log.i(LOG_TAG, "GoVPNAdapter celzero connect crypt else - tunnel mode set with mode -" + Settings.DNSModeCryptPort + firewallMode + proxyMode);
                        }
                    }
                }else{
                    if(persistentState.getDnsType() == Constants.DNS_TYPE_CRYPT_CHANGE) {
                        dnsCryptEndpointRepository.updateFailingConnections();
                        DoHEndpoint doHEndpoint = doHEndpointRepository.updateConnectionDefault();
                        persistentState.setDnsType(Constants.DNS_TYPE_DNS_CHANGE);
                        if(doHEndpoint != null)
                            persistentState.setConnectionModeChange(doHEndpoint.getDohURL());
                        appMode.setDNSMode(Settings.DNSModePort);
                        Handler handler = new Handler(vpnService.getMainLooper());
                        handler.post(new Runnable() {
                            public void run() {
                                Utilities.Companion.showToastInMidLayout(vpnService, "Error connecting to DNSCrypt server.", Toast.LENGTH_SHORT);
                            }
                        });
                    }
                }
            } catch (Exception e) {
                if(persistentState.getDnsType() == Constants.DNS_TYPE_CRYPT_CHANGE) {
                    Log.e(LOG_TAG, "GoVPNAdapter celzero connect-tunnel: dns crypt", e);
                    dnsCryptEndpointRepository.updateFailingConnections();
                    DoHEndpoint doHEndpoint = doHEndpointRepository.updateConnectionDefault();
                    persistentState.setDnsType(Constants.DNS_TYPE_DNS_CHANGE);
                    if(doHEndpoint != null)
                        persistentState.setConnectionModeChange(doHEndpoint.getDohURL());
                    if (appMode != null) {
                        appMode.setDNSMode(Settings.DNSModePort);
                    }
                    Handler handler = new Handler(vpnService.getMainLooper());
                    handler.post(new Runnable() {
                        public void run() {
                            Utilities.Companion.showToastInMidLayout(vpnService, "Error connecting to DNSCrypt server.", Toast.LENGTH_SHORT);
                        }
                    });
                    Log.d(LOG_TAG, "GoVPNAdapter celzero connect crypt exception handling - update dns crypt and remove the servers");
                }
            }
            return "Executed";
        }

        @Override
        protected void onPostExecute(String result) {

        }
    }

    public void setSocks5TunnelMode() {
        if(appMode != null) {
            ProxyEndpoint socks5;
            int proxyMode = (int) appMode.getProxyMode();
            if (proxyMode == Constants.ORBOT_SOCKS) {
                socks5 = appMode.getOrbotProxyDetails();
            } else {
                socks5 = appMode.getSocks5ProxyDetails();
            }
            if (socks5 != null) {
                setProxyMode(socks5.getUserName(), socks5.getPassword(), socks5.getProxyIP(), socks5.getProxyPort());
            }
            if (HomeScreenActivity.GlobalVariable.INSTANCE.getDEBUG())
                Log.d(LOG_TAG, "GoVPNAdapter Socks5 mode set - " + socks5.getProxyIP() + "," + socks5.getProxyPort());
        }
    }

    private static ParcelFileDescriptor establishVpn(BraveVPNService vpnService) {
        try {
            VpnService.Builder builder = vpnService.newBuilder()
                    .setSession("RethinkDNS")
                    .setMtu(VPN_INTERFACE_MTU)
                    .addAddress(LanIp.GATEWAY.make(IPV4_TEMPLATE), IPV4_PREFIX_LENGTH);
            if(HomeScreenActivity.GlobalVariable.INSTANCE.getBraveMode() == DNS_MODE){
                builder.addRoute(LanIp.DNS.make(IPV4_TEMPLATE),32);
                builder.addDnsServer(LanIp.DNS.make(IPV4_TEMPLATE));
            }else if(HomeScreenActivity.GlobalVariable.INSTANCE.getBraveMode() == FIREWALL_MODE){
                builder.addRoute("0.0.0.0",0);
            }else{
                builder.addDnsServer(LanIp.DNS.make(IPV4_TEMPLATE));
                builder.addRoute("0.0.0.0",0);
            }

            return builder.establish();
        } catch (Exception e) {
            Log.e(LOG_TAG, e.getMessage(), e);
            return null;
        }
    }

    private @Nullable
    Protector getProtector() {
        if (VERSION.SDK_INT >= VERSION_CODES.LOLLIPOP) {
            // We don't need socket protection in these versions because the call to
            // "addDisallowedApplication" effectively protects all sockets in this app.
            return null;
        }
        return vpnService;
    }

    Blocker getBlocker() {
        return vpnService;
    }

    public synchronized void close() {
        if (tunnel != null) {
            tunnel.disconnect();
            Log.i(LOG_TAG,"GoVPNAdapter Tunnel disconnect");
        }
        if (tunFd != null) {
            try {
                tunFd.close();
            } catch (IOException e) {
                Log.e(LOG_TAG, e.getMessage(), e);
            }
        }
        tunFd = null;
        tunnel = null;
    }

    private doh.Transport makeDohTransport(@Nullable String url) throws Exception {
        //PersistantState persistentState  = new PersistantState();
        //VpnController vpnController = new VpnController();
        //TODO : Check the below code
        //@NonNull String realUrl = PersistentState.Companion.expandUrl(vpnService, url);
        String dohIPs = getIpString(vpnService, url);
        return Tun2socks.newDoHTransport(url, dohIPs, getProtector(), null, listener);
    }

    /**
     * Updates the DOH server URL for the VPN.  If Go-DoH is enabled, DNS queries will be handled in
     * Go, and will not use the Java DoH implementation.  If Go-DoH is not enabled, this method
     * has no effect.
     */
    public synchronized void updateDohUrl(Long dnsMode, Long blockMode, Long proxyMode) {
        // FIXME: 18-10-2020  - Check for the tunFD null code. Removed because of the connect tunnel

        // changes made in connectTunnel()
        if (tunFd == null) {
            // Adapter is closed.
            return;
        }

        // Introduced new variable isAdapterAvailable instead of tunFd check.
        /*if(!isAdapterAvailable){
            return;
        }*/
        if (tunnel == null) {
            // Attempt to re-create the tunnel.  Creation may have failed originally because the DoH
            // server could not be reached.  This will update the DoH URL as well.
            connectTunnel(dnsMode, blockMode, proxyMode);
            return;
        }

        // Overwrite the DoH Transport with a new one, even if the URL has not changed.  This function
        // is called on network changes, and it's important to switch to a fresh transport because the
        // old transport may be using sockets on a deleted interface, which may block until they time
        // out.
        String dohURL = "https://free.bravedns.com/dns-query";
        try {
            if(appMode.getDOHDetails() != null){
                dohURL = appMode.getDOHDetails().getDohURL();
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "GoVPNAdapter dohURL is null", e);
        }
        Log.d(LOG_TAG,"GoVPNAdapter DoHURL - "+dohURL);
        try {
            //For invalid URL connection request.
            //Check makeDohTransport, if it is not resolved don't close the tunnel.
            //So handling the exception in makeDohTransport and not resetting the tunnel. Below is the exception thrown from Tun2socks.aar
            //I/GoLog: Failed to read packet from TUN: read : bad file descriptor
            doh.Transport dohTransport = makeDohTransport(dohURL);

            tunnel.setDNS(dohTransport);

            if (HomeScreenActivity.GlobalVariable.INSTANCE.getDEBUG())
                Log.d(LOG_TAG, "GoVPNAdapter Before DOH URL set bravedns- " + dohURL);

            if (dnsMode == Settings.DNSModeIP || dnsMode == Settings.DNSModePort || dnsMode == Settings.DNSModeNone) {
                //To set bravedns mode- two modes
                //Mode local - Requires to set the local mode with
                //Mode Remote -
                Boolean isLocalSet = setBraveDNSLocalMode();
                if (!isLocalSet && dohURL.contains(Constants.BRAVE_BASIC_URL)) {
                    Log.i(LOG_TAG, "GoVPNAdapter Set stamp for remote url :" + dohURL);
                    setBraveDNSRemoteMode(dohURL);
                }
            }
            Log.i(LOG_TAG,"GoVPNAdapter updateDohUrl call- Modes -- DNSMODE- " + dnsMode + " - blockMode-" + blockMode + " proxyMode-" + proxyMode);

            if (HomeScreenActivity.GlobalVariable.INSTANCE.getDEBUG())
                Log.d(LOG_TAG, "GoVPNAdapter updateDohUrl - Connected to tunnel with DNSMODE- " + dnsMode + " - blockMode-" + blockMode + "proxyMode-" + proxyMode);


            if (dnsMode != Settings.DNSModeCryptIP && dnsMode != Settings.DNSModeCryptPort) {
                if (proxyMode == Constants.ORBOT_SOCKS) {
                    tunnel.setTunMode(dnsMode, blockMode, Settings.ProxyModeSOCKS5);
                } else {
                    tunnel.setTunMode(dnsMode, blockMode, proxyMode);
                }
                checkForCryptRemoval();
                if (dnsMode == Settings.DNSModeProxyIP || dnsMode == Settings.DNSModeProxyPort) {
                    Log.i(LOG_TAG, "GoVPNAdapter dnsMode mode - " + dnsMode);
                    setDNSProxy();
                }
                if (proxyMode == Settings.ProxyModeSOCKS5  || proxyMode == Constants.ORBOT_SOCKS) {
                    setSocks5TunnelMode();
                }
            } else {
                setCryptMode();
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, e.getMessage(), e);
            tunnel.disconnect();
            tunnel = null;
            VpnController.Companion.getInstance().onConnectionStateChanged(vpnService, BraveVPNService.State.FAILING);
        }
    }

    private void setBraveDNSRemoteMode(String dohURL) {
        if (dohURL.contains(Constants.BRAVE_BASIC_URL)) {
            try {
                if(persistentState.getRemoteBraveDNSDownloaded()) {
                    String path = vpnService.getFilesDir().getCanonicalPath() +"/"+ persistentState.getRemoteBlockListDownloadTime();
                    BraveDNS braveDNS = Dnsx.newBraveDNSRemote(path + Constants.FILE_TAG_NAME);
                    if (HomeScreenActivity.GlobalVariable.INSTANCE.getDEBUG())
                        Log.d(LOG_TAG, "GoVPNAdapter DOH URL set bravedns- " + dohURL + "--" + path);
                    tunnel.setBraveDNS(braveDNS);
                }
            } catch (Exception ex) {
                Log.e(LOG_TAG, "GoVPNAdapter Exception while setting bravedns for remote:" + ex.getMessage(), ex);
            }
        }
    }

    private Boolean setBraveDNSLocalMode() {
        if (persistentState.getBlockListFilesDownloaded() && persistentState.getLocalBlocklistEnabled()) {
            try {
                BraveDNS localBraveDNS = appMode.getBraveDNS();
                if(localBraveDNS != null) {
                    String stamp = persistentState.getLocalBlockListStamp();
                    if(!stamp.isEmpty()){
                        Log.i(LOG_TAG, "GoVPNAdapter Tunnel is set with local stamp: " + stamp);
                        tunnel.setBraveDNS(localBraveDNS);
                        tunnel.getBraveDNS().setStamp(stamp);
                        Log.i(LOG_TAG, "GoVPNAdapter Tunnel is set with local stamp (GetStamp): " + tunnel.getBraveDNS().getStamp());
                        return true;
                    }else {
                        Log.i(LOG_TAG, "GoVPNAdapter Local Stamp is empty");
                    }

                }else{
                    Log.i(LOG_TAG,"GoVPNAdapter app dns mode is set to null with local stamp");
                }
            } catch (Exception ex) {
                Log.e(LOG_TAG, "GoVPNAdapter Exception while setting brave dns for local:" + ex.getMessage());
            }
        }else{
            if(tunnel.getBraveDNS() != null){
                try{
                    tunnel.setBraveDNS(null);
                    Log.i(LOG_TAG,"GoVPNAdapter app dns mode is set to null(on GO) with local stamp");
                }catch(Exception ex){
                    Log.e(LOG_TAG, "GoVPNAdapter Exception while setting null for brave dns local:" + ex.getMessage(), ex);
                }
            }
        }
        return false;
    }

    static String getIpString(Context context, String url) {
        Resources res = context.getResources();
        String[] urls = res.getStringArray(R.array.urls);
        String[] ips = res.getStringArray(R.array.ips);
        for (int i = 0; i < urls.length; ++i) {
            // TODO: Consider relaxing this equality condition to a match on just the domain.
            //Code has been modified from equals to contains to match on the domain. Need to come
            //up with some other solution to check by extracting the domain name
            if (urls[i].contains(url)) {
                return ips[i];
            }
        }
        return "";
    }
}
