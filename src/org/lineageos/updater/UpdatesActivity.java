/*
 * Copyright (C) 2017 The LineageOS Project
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
package org.lineageos.updater;

import android.animation.ObjectAnimator;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.icu.text.DateFormat;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemProperties;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.cardview.widget.CardView;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;

import org.json.JSONException;
import org.lineageos.updater.controller.UpdaterController;
import org.lineageos.updater.controller.UpdaterService;
import org.lineageos.updater.download.DownloadClient;
import org.lineageos.updater.misc.Constants;
import org.lineageos.updater.misc.StringGenerator;
import org.lineageos.updater.misc.Utils;
import org.lineageos.updater.model.UpdateInfo;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class UpdatesActivity extends UpdatesListActivity {

    private static final String TAG = "UpdatesActivity";
    private UpdaterService mUpdaterService;
    private BroadcastReceiver mBroadcastReceiver;

    private static UpdatesListAdapter mAdapter;

    private FloatingActionButton mRefreshIconButton;
    private ObjectAnimator mRefreshAnimation;
    private TextView mUpdateText;

    private CardView mNoUpdatesCardView;
    private TextView mCurrentBuildVersion;
    private TextView mCurrentBuildDate;
    private TextView mDeviceInfo;

    private static Map<String, String> mirrors;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_updates);

        RecyclerView recyclerView = (RecyclerView) findViewById(R.id.recycler_view);
        mAdapter = new UpdatesListAdapter(this, this);
        recyclerView.setAdapter(mAdapter);
        RecyclerView.LayoutManager layoutManager = new LinearLayoutManager(this);
        recyclerView.setLayoutManager(layoutManager);
        RecyclerView.ItemAnimator animator = recyclerView.getItemAnimator();
        if (animator instanceof SimpleItemAnimator) {
            ((SimpleItemAnimator) animator).setSupportsChangeAnimations(false);
        }

        mRefreshIconButton = findViewById(R.id.refresh);
        mUpdateText = findViewById(R.id.updates_message);

        mBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (UpdaterController.ACTION_UPDATE_STATUS.equals(intent.getAction())) {
                    String downloadId = intent.getStringExtra(UpdaterController.EXTRA_DOWNLOAD_ID);
                    handleDownloadStatusChange(downloadId);
                    mAdapter.notifyDataSetChanged();
                } else if (UpdaterController.ACTION_DOWNLOAD_PROGRESS.equals(intent.getAction()) ||
                        UpdaterController.ACTION_INSTALL_PROGRESS.equals(intent.getAction())) {
                    String downloadId = intent.getStringExtra(UpdaterController.EXTRA_DOWNLOAD_ID);
                    mAdapter.notifyItemChanged(downloadId);
                } else if (UpdaterController.ACTION_UPDATE_REMOVED.equals(intent.getAction())) {
                    String downloadId = intent.getStringExtra(UpdaterController.EXTRA_DOWNLOAD_ID);
                    mAdapter.removeItem(downloadId);
                    downloadUpdatesList(false);
                }
            }
        };

        mRefreshIconButton.setOnClickListener(v -> {
            downloadUpdatesList(true);
        });

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayShowTitleEnabled(false);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        mRefreshAnimation = ObjectAnimator.ofFloat(mRefreshIconButton, "rotation", 0f, 360f);
        mRefreshAnimation.setDuration(1000);
        mRefreshAnimation.setRepeatMode(ObjectAnimator.RESTART);
        mRefreshAnimation.setRepeatCount(ObjectAnimator.INFINITE);

        mNoUpdatesCardView = findViewById(R.id.no_updates_cardview);

        mCurrentBuildVersion = findViewById(R.id.current_build_version);
        mCurrentBuildDate = findViewById(R.id.current_build_date);
        mDeviceInfo = findViewById(R.id.device_info);

        mCurrentBuildVersion.setText(String.format(getString(R.string.arrowos_display_version),
                SystemProperties.get(Constants.PROP_BUILD_VERSION),
                SystemProperties.get(Constants.PROP_ZIP_TYPE)));

        mCurrentBuildDate.setText(StringGenerator.getDateLocalizedUTC(this,
                DateFormat.LONG, SystemProperties.getLong(Constants.PROP_BUILD_DATE, 0)));

        mDeviceInfo.setText(SystemProperties.get(Constants.PROP_DEVICE));
    }

    @Override
    public void onStart() {
        super.onStart();
        Intent intent = new Intent(this, UpdaterService.class);
        startService(intent);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(UpdaterController.ACTION_UPDATE_STATUS);
        intentFilter.addAction(UpdaterController.ACTION_DOWNLOAD_PROGRESS);
        intentFilter.addAction(UpdaterController.ACTION_INSTALL_PROGRESS);
        intentFilter.addAction(UpdaterController.ACTION_UPDATE_REMOVED);
        LocalBroadcastManager.getInstance(this).registerReceiver(mBroadcastReceiver, intentFilter);
    }

    @Override
    public void onStop() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mBroadcastReceiver);
        if (mUpdaterService != null) {
            unbindService(mConnection);
        }
        super.onStop();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_toolbar, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_preferences: {
                showPreferencesDialog();
                return true;
            }
            case R.id.menu_show_changelog: {
                Intent openUrl = new Intent(Intent.ACTION_VIEW,
                        Uri.parse(Utils.getChangelogURL(this)));
                startActivity(openUrl);
                return true;
            }
            case R.id.menu_mirrors: {
                showMirrorPreferencesDialog();
                return true;
            }
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    private ServiceConnection mConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName className,
                IBinder service) {
            UpdaterService.LocalBinder binder = (UpdaterService.LocalBinder) service;
            mUpdaterService = binder.getService();
            mAdapter.setUpdaterController(mUpdaterService.getUpdaterController());
            getUpdatesList();
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mAdapter.setUpdaterController(null);
            mUpdaterService = null;
            mAdapter.notifyDataSetChanged();
        }
    };

    private void loadUpdatesList(File jsonFile, boolean manualRefresh)
            throws IOException, JSONException {
        Log.d(TAG, "Adding remote updates");
        UpdaterController controller = mUpdaterService.getUpdaterController();
        boolean newUpdates = false;

        List<UpdateInfo> updates = Utils.parseJson(jsonFile, true);
        List<String> updatesOnline = new ArrayList<>();
        for (UpdateInfo update : updates) {
                newUpdates |= controller.addUpdate(update);
                controller.addUpdate(update);
                updatesOnline.add(update.getDownloadId());
                mUpdateText.setText(R.string.snack_updates_found);
        }
        controller.setUpdatesAvailableOnline(updatesOnline, true);

        if (manualRefresh) {
            showSnackbar(
                    newUpdates ? R.string.snack_updates_found : R.string.snack_no_updates_found,
                    Snackbar.LENGTH_SHORT);
        }

        List<String> updateIds = new ArrayList<>();
        List<UpdateInfo> sortedUpdates = controller.getUpdates();
        if (sortedUpdates.isEmpty()) {
            findViewById(R.id.recycler_view).setVisibility(View.GONE);
        } else {
            findViewById(R.id.recycler_view).setVisibility(View.VISIBLE);
            sortedUpdates.sort((u1, u2) -> Long.compare(u2.getTimestamp(), u1.getTimestamp()));
            for (UpdateInfo update : sortedUpdates) {
                updateIds.add(update.getDownloadId());
            }
            mAdapter.setData(updateIds);
            mAdapter.notifyDataSetChanged();
        }
    }

    private void getUpdatesList() {
        File jsonFile = Utils.getCachedUpdateList(this);
        if (jsonFile.exists()) {
            try {
                loadUpdatesList(jsonFile, false);
                Log.d(TAG, "Cached list parsed");
            } catch (IOException | JSONException e) {
                Log.e(TAG, "Error while parsing json list", e);
            }
        } else {
            downloadUpdatesList(false);
        }
    }

    private void processNewJson(File json, File jsonNew, boolean manualRefresh) {
        try {
            loadUpdatesList(jsonNew, manualRefresh);
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
            long millis = System.currentTimeMillis();
            preferences.edit().putLong(Constants.PREF_LAST_UPDATE_CHECK, millis).apply();
            if (json.exists() && Utils.isUpdateCheckEnabled(this) &&
                    Utils.checkForNewUpdates(json, jsonNew)) {
                UpdatesCheckReceiver.updateRepeatingUpdatesCheck(this);
            }
            // In case we set a one-shot check because of a previous failure
            UpdatesCheckReceiver.cancelUpdatesCheck(this);
            jsonNew.renameTo(json);
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Could not read json", e);
            showSnackbar(R.string.snack_updates_check_failed, Snackbar.LENGTH_LONG);
        }
    }

    private void downloadUpdatesList(final boolean manualRefresh) {
        final File jsonFile = Utils.getCachedUpdateList(this);
        final File jsonFileTmp = new File(jsonFile.getAbsolutePath() + UUID.randomUUID());
        String url = Utils.getServerURL(this);
        Log.d(TAG, "Checking " + url);

        DownloadClient.DownloadCallback callback = new DownloadClient.DownloadCallback() {
            @Override
            public void onFailure(final boolean cancelled) {
                Log.e(TAG, "Could not download updates list");
                runOnUiThread(() -> {
                    if (!cancelled) {
                        showSnackbar(R.string.snack_updates_server_down, Snackbar.LENGTH_LONG);
                    }

                    mRefreshIconButton.setEnabled(true);
                    mRefreshIconButton.setClickable(true);
                    mRefreshIconButton.setBackgroundTintList(getColorStateList(R.color.theme_accent));
                    mRefreshAnimation.end();
                });
            }

            @Override
            public void onResponse(int statusCode, String url,
                    DownloadClient.Headers headers) {
            }

            @Override
            public void onSuccess(File destination) {
                runOnUiThread(() -> {
                    Log.d(TAG, "List downloaded");
                    processNewJson(jsonFile, jsonFileTmp, manualRefresh);
                    mRefreshIconButton.setEnabled(true);
                    mRefreshIconButton.setClickable(true);
                    mRefreshIconButton.setBackgroundTintList(getColorStateList(R.color.theme_accent));
                    mRefreshAnimation.end();
                });
            }
        };

        final DownloadClient downloadClient;
        try {
            downloadClient = new DownloadClient.Builder()
                    .setUrl(url)
                    .setDestination(jsonFileTmp)
                    .setDownloadCallback(callback)
                    .build();
        } catch (IOException exception) {
            Log.e(TAG, "Could not build download client");
            showSnackbar(R.string.snack_updates_download_client, Snackbar.LENGTH_LONG);
            return;
        }

        mRefreshIconButton.setEnabled(false);
        mRefreshIconButton.setClickable(false);
        mRefreshIconButton.setBackgroundTintList(getColorStateList(R.color.button_clicked));
        mRefreshAnimation.start();
        downloadClient.start();
    }

    private void handleDownloadStatusChange(String downloadId) {
        UpdateInfo update = mUpdaterService.getUpdaterController().getUpdate(downloadId);
        switch (update.getStatus()) {
            case PAUSED_ERROR:
                showSnackbar(R.string.snack_download_failed, Snackbar.LENGTH_LONG);
                break;
            case VERIFICATION_FAILED:
                showSnackbar(R.string.snack_download_verification_failed, Snackbar.LENGTH_LONG);
                break;
            case VERIFIED:
                showSnackbar(R.string.snack_download_verified, Snackbar.LENGTH_LONG);
                break;
        }
    }

    @Override
    public void showSnackbar(int stringId, int duration) {
        Snackbar snackbar = Snackbar.make(findViewById(R.id.toolbar), stringId, duration);
        snackbar.setAnchorView(R.id.refresh);
        snackbar.show();
    }

    public void showSnackbarString(String string, int duration) {
        Snackbar snackbar = Snackbar.make(findViewById(R.id.toolbar), string, duration);
        snackbar.setAnchorView(R.id.refresh);
        snackbar.show();
    }

    private void showPreferencesDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.preferences_dialog, null);
        Spinner autoCheckInterval =
                view.findViewById(R.id.preferences_auto_updates_check_interval);
        Switch autoDelete = view.findViewById(R.id.preferences_auto_delete_updates);
        Switch dataWarning = view.findViewById(R.id.preferences_mobile_data_warning);
        Switch abPerfMode = view.findViewById(R.id.preferences_ab_perf_mode);

        if (!Utils.isABDevice()) {
            abPerfMode.setVisibility(View.GONE);
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        autoCheckInterval.setSelection(Utils.getUpdateCheckSetting(this));
        autoDelete.setChecked(prefs.getBoolean(Constants.PREF_AUTO_DELETE_UPDATES, false));
        dataWarning.setChecked(prefs.getBoolean(Constants.PREF_MOBILE_DATA_WARNING, true));
        abPerfMode.setChecked(prefs.getBoolean(Constants.PREF_AB_PERF_MODE, false));

        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_preferences)
                .setView(view)
                .setOnDismissListener(dialogInterface -> {
                    prefs.edit()
                            .putInt(Constants.PREF_AUTO_UPDATES_CHECK_INTERVAL,
                                    autoCheckInterval.getSelectedItemPosition())
                            .putBoolean(Constants.PREF_AUTO_DELETE_UPDATES,
                                    autoDelete.isChecked())
                            .putBoolean(Constants.PREF_MOBILE_DATA_WARNING,
                                    dataWarning.isChecked())
                            .putBoolean(Constants.PREF_AB_PERF_MODE,
                                    abPerfMode.isChecked())
                            .apply();

                    if (Utils.isUpdateCheckEnabled(this)) {
                        UpdatesCheckReceiver.scheduleRepeatingUpdatesCheck(this);
                    } else {
                        UpdatesCheckReceiver.cancelRepeatingUpdatesCheck(this);
                        UpdatesCheckReceiver.cancelUpdatesCheck(this);
                    }

                    if (Utils.isABDevice()) {
                        boolean enableABPerfMode = abPerfMode.isChecked();
                        mUpdaterService.getUpdaterController().setPerformanceMode(enableABPerfMode);
                    }
                })
                .show();
    }

    public static void prepareMirrorsData (UpdateInfo updateInfo, UpdatesActivity mUpdatesActivity) {

        new AsyncTask<UpdateInfo, Void, Map<String, String>>() {
            @Override
            protected void onPreExecute() {
                super.onPreExecute();

                if (Utils.getRankSortSetting(mUpdatesActivity))
                    mUpdatesActivity.showSnackbar(R.string.snack_ranking_mirrors, Snackbar.LENGTH_INDEFINITE);
                else
                    mUpdatesActivity.showSnackbar(R.string.snack_fetching_mirrors, Snackbar.LENGTH_INDEFINITE);
            }

            @Override
            protected Map<String, String> doInBackground(UpdateInfo... update) {

                Boolean rankSort = Utils.getRankSortSetting(mUpdatesActivity);
                mirrors = new LinkedHashMap<>();

                try {
                    Thread mirrorsData = new Thread(() -> mirrors = UpdaterController.arrowMirrors(update[0], rankSort));
                    mirrorsData.start();
                    mirrorsData.join();
                } catch (InterruptedException e) {
                    Log.d(TAG, "Mirrors data thread interrupted");
                }

                return mirrors;
            }

            @Override
            protected void onPostExecute(Map<String, String> mirrors) {
                super.onPostExecute(mirrors);

                if (!mirrors.isEmpty()) {
                    mUpdatesActivity.showSnackbar(R.string.snack_fetched_mirrors, Snackbar.LENGTH_SHORT);
                    showMirrorsDialog(mirrors, mUpdatesActivity, updateInfo);
                } else {
                    mUpdatesActivity.showSnackbar(R.string.snack_failed_mirrors, Snackbar.LENGTH_LONG);
                }
            }
        }.execute(updateInfo);
    }

    private static void showMirrorsDialog(Map<String, String> mirrorsList, UpdatesActivity mUpdatesActivity, UpdateInfo updateInfo) {
        final MirrorsDbHelper mirrorsDbHelper = MirrorsDbHelper.getInstance(mUpdatesActivity);
        String[] mirrors = mirrorsList.keySet().toArray(new String[0]);
        String[] mirrors_pings = new String[mirrors.length];
        String downloadId = updateInfo.getDownloadId();
        String prevMirrorName = mirrorsDbHelper.getMirrorName(updateInfo.getDownloadId());
        Boolean isRankSort = Utils.getRankSortSetting(mUpdatesActivity);
        int setMirrorPos = 0;

        for (int i=0; i<mirrors.length; i++) {
            if (mirrors[i].equals(prevMirrorName)) {
                setMirrorPos = i;
                break;
            }
        }

        // If failed to find the previous mirror force set to the first available one
        if (setMirrorPos == 0) {
            mirrorsDbHelper.setMirrorName(mirrors[0], downloadId);
            UpdaterController.setMirror(updateInfo, mUpdatesActivity, mirrors[0]);
            mAdapter.notifyItemChanged(downloadId);
        }

        if (isRankSort) {
            int mirrorName =0;
            for (Map.Entry<Double, String> pingVals : UpdaterController.sorted_ranked_mirrors.entrySet()) {
                mirrors_pings[mirrorName] = mirrors[mirrorName] + "  (" + pingVals.getKey() + ")";
                mirrorName++;
            }
        }

        new AlertDialog.Builder(mUpdatesActivity)
                .setTitle(R.string.sf_dialog_title)
                .setSingleChoiceItems((isRankSort) ? mirrors_pings : mirrors, setMirrorPos, (dialogInterface, i) -> {

                    mirrorsDbHelper.setMirrorName(mirrors[i], downloadId);
                    UpdaterController.setMirror(updateInfo, mUpdatesActivity, mirrors[i]);
                    mAdapter.notifyItemChanged(downloadId);

                    Log.d(TAG, "Selected Mirror!" + mirrors[i]);

                }).create()
                .show();
    }

    private void showMirrorPreferencesDialog () {
        View view = LayoutInflater.from(this).inflate(R.layout.sf_mirror_preferences, null);
        Switch rank_sort = view.findViewById(R.id.rank_and_sort_mirrors);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        rank_sort.setChecked(prefs.getBoolean(Constants.PREF_RANK_SORT, false));

        new AlertDialog.Builder(this)
                .setTitle(R.string.sf_mirror_preferences)
                .setView(view)
                .setOnDismissListener(dialogInterface -> prefs.edit()
                        .putBoolean(Constants.PREF_RANK_SORT, rank_sort.isChecked())
                        .apply())
                .show();
    }
}
