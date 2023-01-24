/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.wifi;

import static android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET;
import static android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED;

import static com.android.server.wifi.entitlement.CarrierSpecificServiceEntitlement.FAILURE_REASON_NAME;
import static com.android.server.wifi.entitlement.CarrierSpecificServiceEntitlement.REASON_HTTPS_CONNECTION_FAILURE;
import static com.android.server.wifi.entitlement.CarrierSpecificServiceEntitlement.REASON_TRANSIENT_FAILURE;

import android.annotation.NonNull;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiContext;
import android.net.wifi.WifiStringResourceWrapper;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.telephony.SubscriptionManager;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.SparseLongArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wifi.entitlement.CarrierSpecificServiceEntitlement;
import com.android.server.wifi.entitlement.PseudonymInfo;

import java.net.MalformedURLException;
import java.time.Duration;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * Manages the OOB and in-band pseudonyms
 */
public final class WifiPseudonymManager {
    private static final String TAG = "WifiPseudonymManager";
    public static final String CONFIG_SERVER_URL =
            "config_wifiOobPseudonymEntitlementServerUrl";
    @VisibleForTesting
    static final long TEN_SECONDS_IN_MILLIS = Duration.ofSeconds(10).toMillis();
    @VisibleForTesting
    private static final long SEVEN_DAYS_IN_MILLIS = Duration.ofDays(7).toMillis();
    @VisibleForTesting
    static final long[] RETRY_INTERVALS_FOR_SERVER_ERROR = {
            Duration.ofMinutes(5).toMillis(),
            Duration.ofMinutes(15).toMillis(),
            Duration.ofMinutes(30).toMillis(),
            Duration.ofMinutes(60).toMillis(),
            Duration.ofMinutes(120).toMillis()};
    @VisibleForTesting
    static final long[] RETRY_INTERVALS_FOR_CONNECTION_ERROR = {
            Duration.ofSeconds(30).toMillis(),
            Duration.ofMinutes(1).toMillis(),
            Duration.ofHours(1).toMillis(),
            Duration.ofHours(3).toMillis(),
            Duration.ofHours(9).toMillis()};
    private final WifiContext mWifiContext;
    private final WifiInjector mWifiInjector;
    private final Clock mClock;
    private final Handler mWifiHandler;

    private boolean mVerboseLogEnabled = false;

    /**
     * Cached Map of <carrier ID, PseudonymInfo>.
     */
    private final SparseArray<PseudonymInfo> mPseudonymInfoArray = new SparseArray<>();

    /*
     * Two cached map of <carrier ID, retry times>.
     */
    private final SparseIntArray mRetryTimesArrayForServerError = new SparseIntArray();
    private final SparseIntArray mRetryTimesArrayForConnectionError = new SparseIntArray();

    /*
     * Cached map of <carrier ID, last failure time stamp>.
     */
    @VisibleForTesting
    final SparseLongArray mLastFailureTimestampArray = new SparseLongArray();

    /*
     * This set contains all the carrier IDs which we should retrieve OOB pseudonym for when the
     * data network becomes available.
     */
    private final Set<Integer> mPendingToRetrieveSet = new ArraySet<>();

    private final ConnectivityManager.NetworkCallback mNetworkCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onCapabilitiesChanged(@NonNull Network network,
                        @NonNull NetworkCapabilities networkCapabilities) {
                    if (networkCapabilities.hasCapability(NET_CAPABILITY_INTERNET)
                            && networkCapabilities.hasCapability(NET_CAPABILITY_VALIDATED)) {
                        retrieveAllNeededOobPseudonym();
                        ConnectivityManager cm = mWifiContext.getSystemService(
                                ConnectivityManager.class);
                        if (cm != null) {
                            cm.unregisterNetworkCallback(mNetworkCallback);
                        }
                    }
                }
            };

    @VisibleForTesting
    final CarrierSpecificServiceEntitlement.Callback mRetrieveCallback =
            new RetrieveCallback();

    WifiPseudonymManager(@NonNull WifiContext wifiContext, @NonNull WifiInjector wifiInjector,
            @NonNull Clock clock, @NonNull Looper wifiLooper) {
        mWifiContext = wifiContext;
        mWifiInjector = wifiInjector;
        mClock = clock;
        // Create a new handler to have a dedicated message queue.
        mWifiHandler = new Handler(wifiLooper);
    }

    /**
     * Gets the valid PseudonymInfo for given carrier ID
     *
     * @param carrierId carrier id for target carrier.
     * @return Optional of the matched PseudonymInfo.
     */
    public Optional<PseudonymInfo> getValidPseudonymInfo(int carrierId) {
        Optional<PseudonymInfo> optionalPseudonymInfo = getPseudonymInfo(carrierId);
        if (optionalPseudonymInfo.isEmpty()) {
            return Optional.empty();
        }

        PseudonymInfo pseudonymInfo = optionalPseudonymInfo.get();
        if (pseudonymInfo.hasExpired()) {
            return Optional.empty();
        }

        WifiCarrierInfoManager wifiCarrierInfoManager = mWifiInjector.getWifiCarrierInfoManager();
        WifiCarrierInfoManager.SimInfo simInfo =
                wifiCarrierInfoManager.getSimInfo(
                        wifiCarrierInfoManager.getMatchingSubId(carrierId));
        String imsi = simInfo == null ? null : simInfo.imsi;
        if (imsi == null) {
            Log.e(TAG, "Matched IMSI is null for carrierId " + carrierId);
            return Optional.empty();
        }
        if (!imsi.equalsIgnoreCase(pseudonymInfo.getImsi())) {
            Log.e(TAG, "IMSI doesn't match for carrierId " + carrierId);
            return Optional.empty();
        }
        return optionalPseudonymInfo;
    }

    private Optional<PseudonymInfo> getPseudonymInfo(int carrierId) {
        PseudonymInfo pseudonymInfo;
        pseudonymInfo = mPseudonymInfoArray.get(carrierId);
        vlogd("getPseudonymInfo(" + carrierId + ") = " + pseudonymInfo);
        return Optional.ofNullable(pseudonymInfo);
    }

    /**
     * Retrieves the OOB pseudonym as a safe check if there isn't any valid pseudonym available,
     * and it has passed 7 days since the last retrieval failure.
     *
     * If there was some problem in the service entitlement server, all the retries to retrieve the
     * pseudonym had failed. Then the carrier fixes the service entitlement server's issue. But
     * the device will never connect to this carrier's WiFi until the user reboot the device or swap
     * the sim. With this safe check, our device will retry to retrieve the OOB pseudonym every 7
     * days if the last retrieval has failed and the device is in this carrier's WiFi coverage.
     */
    public void retrievePseudonymOnFailureTimeoutExpired(
            @NonNull WifiConfiguration wifiConfiguration) {
        if (wifiConfiguration.enterpriseConfig == null
                || !wifiConfiguration.enterpriseConfig.isAuthenticationSimBased()) {
            return;
        }
        retrievePseudonymOnFailureTimeoutExpired(wifiConfiguration.carrierId);
    }

    /**
     * Retrieves the OOP pseudonym as a safe check if there isn't any valid pseudonym available,
     * and it has passed 7 days since the last retrieval failure.
     * @param carrierId The caller must be a SIM based wifi configuration or passpoint.
     */
    public void retrievePseudonymOnFailureTimeoutExpired(int carrierId) {
        if (!mWifiInjector.getWifiCarrierInfoManager().isOobPseudonymFeatureEnabled(carrierId)) {
            return;
        }
        Optional<PseudonymInfo> optionalPseudonymInfo = getValidPseudonymInfo(carrierId);
        if (optionalPseudonymInfo.isPresent()) {
            return;
        }
        long timeStamp = mLastFailureTimestampArray.get(carrierId);
        if ((timeStamp > 0)
                && (mClock.getWallClockMillis() - timeStamp >= SEVEN_DAYS_IN_MILLIS)) {
            scheduleToRetrieveDelayed(carrierId, 0);
        }
    }

    /**
     * Update the input WifiConfiguration's anonymous identity.
     *
     * @param wifiConfiguration WifiConfiguration which will be updated.
     * @return true if there is a valid pseudonym to update the WifiConfiguration, otherwise false.
     */
    public void updateWifiConfiguration(@NonNull WifiConfiguration wifiConfiguration) {
        if (wifiConfiguration.enterpriseConfig == null
                || !wifiConfiguration.enterpriseConfig.isAuthenticationSimBased()) {
            return;
        }
        if (!mWifiInjector.getWifiCarrierInfoManager()
                .isOobPseudonymFeatureEnabled(wifiConfiguration.carrierId)) {
            return;
        }
        WifiCarrierInfoManager wifiCarrierInfoManager = mWifiInjector.getWifiCarrierInfoManager();
        Optional<PseudonymInfo> optionalPseudonymInfo =
                getValidPseudonymInfo(wifiConfiguration.carrierId);
        if (optionalPseudonymInfo.isEmpty()) {
            Log.w(TAG, "pseudonym is not available, the wifi configuration: "
                    + wifiConfiguration.getKey() + " can not be updated.");
            return;
        }

        String pseudonym = optionalPseudonymInfo.get().getPseudonym();
        String expectedIdentity =
                wifiCarrierInfoManager.decoratePseudonymWith3GppRealm(wifiConfiguration,
                        pseudonym);
        String existingIdentity = wifiConfiguration.enterpriseConfig.getAnonymousIdentity();
        if (TextUtils.equals(expectedIdentity, existingIdentity)) {
            return;
        }

        wifiConfiguration.enterpriseConfig.setAnonymousIdentity(expectedIdentity);
        vlogd("update pseudonym: " + maskPseudonym(pseudonym)
                + " for wifi config: " + wifiConfiguration.getKey());
        mWifiInjector.getWifiConfigManager()
                .addOrUpdateNetwork(wifiConfiguration, Process.WIFI_UID);
        if (wifiConfiguration.isPasspoint()) {
            mWifiInjector.getPasspointManager().setAnonymousIdentity(wifiConfiguration);
        } else if (wifiConfiguration.fromWifiNetworkSuggestion) {
            mWifiInjector.getWifiNetworkSuggestionsManager()
                    .setAnonymousIdentity(wifiConfiguration);
        }
    }

    /**
     * Set in-band pseudonym with the existing PseudonymInfo's TTL. When an in-band pseudonym is
     * received, there should already be an existing pseudonym(in-band or OOB).
     *
     * @param carrierId carrier id for target carrier.
     * @param pseudonym Pseudonym to set for the target carrier.
     */
    public void setInBandPseudonym(int carrierId, @NonNull String pseudonym) {
        vlogd("setInBandPseudonym(" + carrierId + ", " +  maskPseudonym(pseudonym) + ")");
        Optional<PseudonymInfo> current = getPseudonymInfo(carrierId);
        if (current.isPresent()) {
            setPseudonymAndScheduleRefresh(carrierId,
                    new PseudonymInfo(pseudonym, current.get().getImsi(),
                    current.get().getTtlInMillis()));
        } else {
            Log.wtf(TAG, "setInBandPseudonym() is called without an existing pseudonym!");
        }
    }

    /*
     * Sets pseudonym(OOB or in-band) into mPseudonymInfoArray and schedule to refresh it after it
     * expires.
     */
    @VisibleForTesting
    void setPseudonymAndScheduleRefresh(int carrierId, @NonNull PseudonymInfo pseudonymInfo) {
        mPseudonymInfoArray.put(carrierId, pseudonymInfo);
        // Cancel all the queued messages and queue another message to refresh the pseudonym
        mWifiHandler.removeMessages(carrierId);
        scheduleToRetrieveDelayed(carrierId, pseudonymInfo.getAtrInMillis());
    }

    /**
     * Retrieves the OOB pseudonym if there is no pseudonym or the existing pseudonym has expired.
     * This method is called when the CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED is
     * received or the TTL has elapsed to refresh the OOB pseudonym.
     *
     * @param carrierId carrier id for target carrier
     */
    public void retrieveOobPseudonymIfNeeded(int carrierId) {
        vlogd("retrieveOobPseudonymIfNeeded(" + carrierId + ")");
        Optional<PseudonymInfo> optionalPseudonymInfo = getValidPseudonymInfo(carrierId);
        if (optionalPseudonymInfo.isEmpty() || optionalPseudonymInfo.get().shouldBeRefreshed()) {
            scheduleToRetrieveDelayed(carrierId, 0);
        } else {
            vlogd("Current pseudonym is still fresh. Exit.");
        }
    }

    /**
     * Retrieves the OOB pseudonym for all the existing carrierIds in mPseudonymInfoArray if needed.
     * This method is called when the network becomes available.
     */
    private void retrieveAllNeededOobPseudonym() {
        vlogd("retrieveAllNeededOobPseudonym()");
        for (int carrierId : mPendingToRetrieveSet) {
            retrieveOobPseudonymIfNeeded(carrierId);
        }
        mPendingToRetrieveSet.clear();
    }

    /**
     * Retrieves the OOB pseudonym with rate limit.
     * This method is supposed to be called after the carrier's AAA server returns authentication
     * error. It retrieves OOB pseudonym only if the existing pseudonym is old enough.
     *
     * Note: The authentication error only happens when there was already a valid pseudonym before.
     * Otherwise, this Wi-Fi configuration won't be automatically connected and no authentication
     * error will be received from AAA server.
     */
    public void retrieveOobPseudonymWithRateLimit(int carrierId) {
        vlogd("retrieveOobPseudonymWithRateLimit(" + carrierId + ")");
        Optional<PseudonymInfo> optionalPseudonymInfo = getPseudonymInfo(carrierId);
        if (optionalPseudonymInfo.isEmpty()) {
            Log.wtf(TAG, "The authentication error only happens when there was already a valid"
                    + " pseudonym before. But now there isn't any PseudonymInfo!");
            return;
        }
        if (optionalPseudonymInfo.get().isOldEnoughToRefresh()) {
            // Schedule the work uniformly in [0..10) seconds to smooth out any potential surge.
            scheduleToRetrieveDelayed(carrierId,
                    (new Random()).nextInt((int) TEN_SECONDS_IN_MILLIS));
        }
    }

    private void scheduleToRetrieveDelayed(int carrierId, long delayMillis) {
        Message msg = Message.obtain(mWifiHandler, new RetrieveRunnable(carrierId));
        msg.what = carrierId;
        mWifiHandler.sendMessageDelayed(msg, delayMillis);
        /*
         * Always suppose it fails before the retrieval really starts to prevent multiple messages
         * been queued when there is no data network available to retrieve. After retrieving, this
         * timestamp will be updated to 0(success) or failure timestamp.
         */
        mLastFailureTimestampArray.put(carrierId, mClock.getWallClockMillis());
    }

    private String getServerUrl(int subId, int carrierId) {
        WifiStringResourceWrapper wrapper = mWifiContext.getStringResourceWrapper(subId, carrierId);
        return wrapper.getString(CONFIG_SERVER_URL, "");
    }

    private String maskPseudonym(String pseudonym) {
        return (pseudonym.length() >= 7) ? (pseudonym.substring(0, 7) + "***") : pseudonym;
    }

    /**
     * Enable/disable verbose logging.
     */
    public void enableVerboseLogging(boolean verboseEnabled) {
        mVerboseLogEnabled = verboseEnabled;
    }

    private void vlogd(String msg) {
        if (!mVerboseLogEnabled) {
            return;
        }
        Log.d(TAG, msg);
    }

    @VisibleForTesting
    class RetrieveRunnable implements Runnable {
        @VisibleForTesting
        int mCarrierId;

        RetrieveRunnable(int carrierId) {
            mCarrierId = carrierId;
        }

        @Override
        public void run() {
            /*
             * There may be multiple messages for this mCarrierId in the queue. There is no need to
             * retrieve them multiple times.
             *
             * For example, carrierA's SIM is inserted into the device multiple times right before
             * carrierA's pseudonym expires, there will be multiple messages in the queue. When the
             * network becomes available, the pseudonym can't be retrieved because the carrierA's
             * server reports an error. To prevent connecting with the server several times, we
             * should cancel all the queued messages.
             */
            mWifiHandler.removeMessages(mCarrierId);

            int subId = mWifiInjector.getWifiCarrierInfoManager().getMatchingSubId(mCarrierId);
            if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                Log.e(TAG, "RetrieveRunnable: " + mCarrierId + ": subId is invalid. Exit.");
                return;
            }

            if (!isNetworkConnected()) {
                if (mPendingToRetrieveSet.isEmpty()) {
                    ConnectivityManager cm = mWifiContext.getSystemService(
                            ConnectivityManager.class);
                    if (cm != null) {
                        cm.registerDefaultNetworkCallback(mNetworkCallback, mWifiHandler);
                    }
                }
                mPendingToRetrieveSet.add(mCarrierId);
                return;
            }
            CarrierSpecificServiceEntitlement entitlement;
            try {
                entitlement = new CarrierSpecificServiceEntitlement(mWifiContext, subId,
                        getServerUrl(subId, mCarrierId));
            } catch (MalformedURLException e) {
                Log.wtf(TAG, e.toString());
                return;
            }
            entitlement.getImsiPseudonym(mCarrierId, mWifiHandler, mRetrieveCallback);
        }

        private boolean isNetworkConnected() {
            ConnectivityManager cm = mWifiContext.getSystemService(ConnectivityManager.class);
            Network activeNetwork = cm.getActiveNetwork();
            if (activeNetwork == null) {
                return false;
            }

            NetworkCapabilities nc = cm.getNetworkCapabilities(activeNetwork);
            if (nc == null) {
                return false;
            }

            /*
             * If we check only for "NET_CAPABILITY_INTERNET", we get "true" if we are connected
             * to a Wi-Fi which has no access to the internet. "NET_CAPABILITY_VALIDATED" also
             * verifies that we are online.
             */
            return nc.hasCapability(NET_CAPABILITY_INTERNET)
                    && nc.hasCapability(NET_CAPABILITY_VALIDATED);
        }
    }

    private class RetrieveCallback implements CarrierSpecificServiceEntitlement.Callback {
        @Override
        public void onSuccess(int carrierId, PseudonymInfo pseudonymInfo) {
            vlogd("RetrieveCallback: OOB pseudonym is retrieved!!! for carrierId " + carrierId
                    + ": " + pseudonymInfo);
            setPseudonymAndScheduleRefresh(carrierId, pseudonymInfo);
            mLastFailureTimestampArray.put(carrierId, 0);
            mRetryTimesArrayForConnectionError.put(carrierId, 0);
            mRetryTimesArrayForServerError.put(carrierId, 0);
        }

        @Override
        public void onFailure(int carrierId,
                @CarrierSpecificServiceEntitlement.FailureReasonCode int reasonCode,
                String description) {
            Log.e(TAG, "RetrieveCallback.onFailure(" + carrierId + ", "
                    + FAILURE_REASON_NAME[reasonCode] + ", " + description);
            mLastFailureTimestampArray.put(carrierId, mClock.getWallClockMillis());
            switch (reasonCode) {
                case REASON_HTTPS_CONNECTION_FAILURE:
                    retryForConnectionError(carrierId);
                    break;
                case REASON_TRANSIENT_FAILURE:
                    retryForServerError(carrierId);
                    break;
            }
        }

        private void retryForConnectionError(int carrierId) {
            int retryTimes = mRetryTimesArrayForConnectionError.get(carrierId, 0);
            if (retryTimes >= RETRY_INTERVALS_FOR_CONNECTION_ERROR.length) {
                vlogd("It has reached the maximum retry count "
                        + RETRY_INTERVALS_FOR_CONNECTION_ERROR.length
                        + " for connection error. Exit.");
                return;
            }
            long interval = RETRY_INTERVALS_FOR_CONNECTION_ERROR[retryTimes];
            retryTimes++;
            mRetryTimesArrayForConnectionError.put(carrierId, retryTimes);
            vlogd("retryForConnectionError: Schedule retry " + retryTimes + " in "
                    + interval + " milliseconds");
            scheduleToRetrieveDelayed(carrierId, interval);
        }

        private void retryForServerError(int carrierId) {
            int retryTimes = mRetryTimesArrayForServerError.get(carrierId, 0);
            if (retryTimes >= RETRY_INTERVALS_FOR_SERVER_ERROR.length) {
                vlogd("It has reached the maximum retry count "
                        + RETRY_INTERVALS_FOR_SERVER_ERROR.length + " for server error. Exit.");
                return;
            }
            long interval = RETRY_INTERVALS_FOR_SERVER_ERROR[retryTimes];
            retryTimes++;
            mRetryTimesArrayForServerError.put(carrierId, retryTimes);
            vlogd("retryForServerError: Schedule retry " + retryTimes + " in "
                    + interval + " milliseconds");
            scheduleToRetrieveDelayed(carrierId, interval);
        }
    }
}
