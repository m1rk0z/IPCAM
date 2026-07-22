package com.example.ipcam;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;

/**
 * Advertises this device as an IPCAM source on the local network via NSD (mDNS),
 * so companion apps (e.g. VideoDelay) can discover it automatically without
 * manual IP entry or QR scanning.
 */
public class NsdAdvertiser {
    private static final String TAG = "NsdAdvertiser";
    public static final String SERVICE_TYPE = "_ipcam._tcp.";

    private final NsdManager nsdManager;
    private NsdManager.RegistrationListener registrationListener;

    public NsdAdvertiser(Context context) {
        nsdManager = (NsdManager) context.getApplicationContext().getSystemService(Context.NSD_SERVICE);
    }

    /**
     * Registers (or re-registers, replacing any previous advertisement) the discovery
     * record. Safe to call repeatedly, e.g. whenever the RTSP port or camera name changes.
     */
    public synchronized void register(String cameraName, int port, String rtspPath) {
        unregister();

        if (nsdManager == null) {
            Log.w(TAG, "NsdManager unavailable, skipping discovery advertisement");
            return;
        }

        NsdServiceInfo serviceInfo = new NsdServiceInfo();
        serviceInfo.setServiceName(cameraName);
        serviceInfo.setServiceType(SERVICE_TYPE);
        serviceInfo.setPort(port);
        serviceInfo.setAttribute("path", rtspPath);
        serviceInfo.setAttribute("name", cameraName);

        registrationListener = new NsdManager.RegistrationListener() {
            @Override
            public void onServiceRegistered(NsdServiceInfo info) {
                Log.d(TAG, "NSD service registered as: " + info.getServiceName());
            }

            @Override
            public void onRegistrationFailed(NsdServiceInfo info, int errorCode) {
                Log.e(TAG, "NSD registration failed, error: " + errorCode);
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo info) {
                Log.d(TAG, "NSD service unregistered");
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo info, int errorCode) {
                Log.w(TAG, "NSD unregistration failed, error: " + errorCode);
            }
        };

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener);
        } catch (Exception e) {
            Log.e(TAG, "Error registering NSD service", e);
            registrationListener = null;
        }
    }

    public synchronized void unregister() {
        if (registrationListener == null || nsdManager == null) return;
        try {
            nsdManager.unregisterService(registrationListener);
        } catch (Exception e) {
            Log.w(TAG, "Error unregistering NSD service", e);
        } finally {
            registrationListener = null;
        }
    }
}
