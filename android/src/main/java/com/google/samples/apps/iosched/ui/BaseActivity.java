/*
 * Copyright 2014 Google Inc. All rights reserved.
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

package com.google.samples.apps.iosched.ui;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SyncStatusObserver;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.support.v4.app.NavUtils;
import android.support.v4.app.TaskStackBuilder;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.ListView;

import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.samples.apps.iosched.R;
import com.google.samples.apps.iosched.injection.LoginAndAuthProvider;
import com.google.samples.apps.iosched.injection.MessagingRegistrationProvider;
import com.google.samples.apps.iosched.login.LoginAndAuth;
import com.google.samples.apps.iosched.login.LoginAndAuthListener;
import com.google.samples.apps.iosched.login.LoginStateListener;
import com.google.samples.apps.iosched.messaging.MessagingRegistration;
import com.google.samples.apps.iosched.navigation.AppNavigationViewAsDrawerImpl;
import com.google.samples.apps.iosched.navigation.NavigationModel.NavigationItemEnum;
import com.google.samples.apps.iosched.provider.ScheduleContract;
import com.google.samples.apps.iosched.service.DataBootstrapService;
import com.google.samples.apps.iosched.settings.SettingsUtils;
import com.google.samples.apps.iosched.sync.SyncHelper;
import com.google.samples.apps.iosched.ui.widget.HeaderView;
import com.google.samples.apps.iosched.ui.widget.HeaderViewImpl;
import com.google.samples.apps.iosched.ui.widget.MultiSwipeRefreshLayout;
import com.google.samples.apps.iosched.util.AccountUtils;
import com.google.samples.apps.iosched.util.ImageLoader;
import com.google.samples.apps.iosched.util.LUtils;
import com.google.samples.apps.iosched.util.RecentTasksStyler;
import com.google.samples.apps.iosched.welcome.WelcomeActivity;

import static com.google.samples.apps.iosched.util.LogUtils.LOGD;
import static com.google.samples.apps.iosched.util.LogUtils.LOGE;
import static com.google.samples.apps.iosched.util.LogUtils.LOGW;
import static com.google.samples.apps.iosched.util.LogUtils.makeLogTag;

/**
 * A base activity that handles common functionality in the app. This includes the navigation
 * drawer, login and authentication, Action Bar tweaks, amongst others.
 */
public abstract class BaseActivity extends AppCompatActivity implements
        LoginAndAuthListener,
        SharedPreferences.OnSharedPreferenceChangeListener,
        MultiSwipeRefreshLayout.CanChildScrollUpCallback, LoginStateListener,
        AppNavigationViewAsDrawerImpl.NavigationDrawerStateListener,
        HeaderView {

    private static final String TAG = makeLogTag(BaseActivity.class);

    private static final int SELECT_GOOGLE_ACCOUNT_RESULT = 9999;

    // the LoginAndAuthHelper handles signing in to Google Play Services and OAuth
    private LoginAndAuth mLoginAndAuthProvider;

    // Navigation drawer
    private AppNavigationViewAsDrawerImpl mAppNavigationViewAsDrawer;

    // Header view
    private HeaderViewImpl mHeaderViewImpl;

    // Helper methods for L APIs
    private LUtils mLUtils;

    private static final int MAIN_CONTENT_FADEIN_DURATION = 250;

    // SwipeRefreshLayout allows the user to swipe the screen down to trigger a manual refresh
    private SwipeRefreshLayout mSwipeRefreshLayout;

    // Registration with GCM for notifications
    private MessagingRegistration mMessagingRegistration;

    // handle to our sync observer (that notifies us about changes in our sync state)
    private Object mSyncObserverHandle;

    private boolean mManualSyncRequest;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        RecentTasksStyler.styleRecentTasksEntry(this);

        // Check if the EULA has been accepted; if not, show it.
        if (WelcomeActivity.shouldDisplay(this)) {
            Intent intent = new Intent(this, WelcomeActivity.class);
            startActivity(intent);
            finish();
            return;
        }

        mMessagingRegistration = MessagingRegistrationProvider.provideMessagingRegistration(this);

        if (savedInstanceState == null) {
            mMessagingRegistration.registerDevice();
        }

        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
        sp.registerOnSharedPreferenceChangeListener(this);

        ActionBar ab = getSupportActionBar();
        if (ab != null) {
            ab.setDisplayHomeAsUpEnabled(true);
        }

        mLUtils = LUtils.getInstance(this);
        mHeaderViewImpl = new HeaderViewImpl(this, mLUtils);
    }

    private void trySetupSwipeRefresh() {
        mSwipeRefreshLayout = (SwipeRefreshLayout) findViewById(R.id.swipe_refresh_layout);
        if (mSwipeRefreshLayout != null) {
            mSwipeRefreshLayout.setColorSchemeResources(
                    R.color.flat_button_text);
            mSwipeRefreshLayout.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
                @Override
                public void onRefresh() {
                    requestDataRefresh();
                }
            });

            if (mSwipeRefreshLayout instanceof MultiSwipeRefreshLayout) {
                MultiSwipeRefreshLayout mswrl = (MultiSwipeRefreshLayout) mSwipeRefreshLayout;
                mswrl.setCanChildScrollUpCallback(this);
            }
        }
    }

    @Override
    public void setProgressBarTopWhenActionBarShown(int progressBarTopWhenActionBarShown) {
        mHeaderViewImpl.setProgressBarTopWhenActionBarShown(progressBarTopWhenActionBarShown);
        updateSwipeRefreshProgressBarTop();
    }

    private void updateSwipeRefreshProgressBarTop() {
        if (mSwipeRefreshLayout == null) {
            return;
        }

        int progressBarStartMargin = getResources().getDimensionPixelSize(
                R.dimen.swipe_refresh_progress_bar_start_margin);
        int progressBarEndMargin = getResources().getDimensionPixelSize(
                R.dimen.swipe_refresh_progress_bar_end_margin);
        int top =
                mHeaderViewImpl.isActionBarShown() ?
                        mHeaderViewImpl.getProgressBarTopWhenActionBarShown() :
                        0;
        mSwipeRefreshLayout.setProgressViewOffset(false,
                top + progressBarStartMargin, top + progressBarEndMargin);
    }

    /**
     * Returns the navigation drawer item that corresponds to this Activity. Subclasses of
     * BaseActivity override this to indicate what nav drawer item corresponds to them Return
     * NAVDRAWER_ITEM_INVALID to mean that this Activity should not have a Nav Drawer.
     */
    protected NavigationItemEnum getSelfNavDrawerItem() {
        return NavigationItemEnum.INVALID;
    }

    @Override
    public void setContentView(int layoutResID) {
        super.setContentView(layoutResID);
        getActionBarToolbar();
    }

    @Override
    public void onNavDrawerStateChanged(boolean isOpen, boolean isAnimating) {
        if (mHeaderViewImpl.isAutoHideEnabled() && isOpen) {
            mHeaderViewImpl.autoShowOrHideActionBar(true);
        }
    }

    @Override
    public void onNavDrawerSlide(float offset) {
    }

    @Override
    public void onBackPressed() {
        if (mAppNavigationViewAsDrawer.isNavDrawerOpen()) {
            mAppNavigationViewAsDrawer.closeNavDrawer();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (key != null && key.equals(SettingsUtils.PREF_ATTENDEE_AT_VENUE)) {
            LOGD(TAG, "Attendee at venue preference changed, repopulating nav drawer and menu.");
            mAppNavigationViewAsDrawer.updateNavigationItems();
            invalidateOptionsMenu();
        }
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        mAppNavigationViewAsDrawer = new AppNavigationViewAsDrawerImpl(new ImageLoader(this), this);
        mAppNavigationViewAsDrawer.activityReady(this, this, getSelfNavDrawerItem());
        mHeaderViewImpl.setNavigationView(mAppNavigationViewAsDrawer);

        if (getSelfNavDrawerItem() != NavigationItemEnum.INVALID) {
            mHeaderViewImpl.setToolbarForNavigation();
        }

        trySetupSwipeRefresh();
        updateSwipeRefreshProgressBarTop();

        View mainContent = findViewById(R.id.main_content);
        if (mainContent != null) {
            mainContent.setAlpha(0);
            mainContent.animate().alpha(1).setDuration(MAIN_CONTENT_FADEIN_DURATION);
        } else {
            LOGW(TAG, "No view with ID main_content to fade in.");
        }
    }

    @Override
    public void onAccountChangeRequested() {
        // override if you want to be notified when another account has been selected account has
        // changed
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        switch (id) {
            case R.id.menu_refresh:
                requestDataRefresh();
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    protected void requestDataRefresh() {
        Account activeAccount = AccountUtils.getActiveAccount(this);
        ContentResolver contentResolver = getContentResolver();
        if (contentResolver.isSyncActive(activeAccount, ScheduleContract.CONTENT_AUTHORITY)) {
            LOGD(TAG, "Ignoring manual sync request because a sync is already in progress.");
            return;
        }
        mManualSyncRequest = true;
        LOGD(TAG, "Requesting manual data refresh.");
        SyncHelper.requestManualSync(activeAccount);
    }

    /**
     * This utility method handles Up navigation intents by searching for a parent activity and
     * navigating there if defined. When using this for an activity make sure to define both the
     * native parentActivity as well as the AppCompat one when supporting API levels less than 16.
     * when the activity has a single parent activity. If the activity doesn't have a single parent
     * activity then don't define one and this method will use back button functionality. If "Up"
     * functionality is still desired for activities without parents then use {@code
     * syntheticParentActivity} to define one dynamically.
     * <p/>
     * Note: Up navigation intents are represented by a back arrow in the top left of the Toolbar in
     * Material Design guidelines.
     *
     * @param currentActivity         Activity in use when navigate Up action occurred.
     * @param syntheticParentActivity Parent activity to use when one is not already configured.
     */
    public static void navigateUpOrBack(Activity currentActivity,
            Class<? extends Activity> syntheticParentActivity) {
        // Retrieve parent activity from AndroidManifest.
        Intent intent = NavUtils.getParentActivityIntent(currentActivity);

        // Synthesize the parent activity when a natural one doesn't exist.
        if (intent == null && syntheticParentActivity != null) {
            try {
                intent = NavUtils.getParentActivityIntent(currentActivity, syntheticParentActivity);
            } catch (PackageManager.NameNotFoundException e) {
                e.printStackTrace();
            }
        }

        if (intent == null) {
            // No parent defined in manifest. This indicates the activity may be used by
            // in multiple flows throughout the app and doesn't have a strict parent. In
            // this case the navigation up button should act in the same manner as the
            // back button. This will result in users being forwarded back to other
            // applications if currentActivity was invoked from another application.
            currentActivity.onBackPressed();
        } else {
            if (NavUtils.shouldUpRecreateTask(currentActivity, intent)) {
                // Need to synthesize a backstack since currentActivity was probably invoked by a
                // different app. The preserves the "Up" functionality within the app according to
                // the activity hierarchy defined in AndroidManifest.xml via parentActivity
                // attributes.
                TaskStackBuilder builder = TaskStackBuilder.create(currentActivity);
                builder.addNextIntentWithParentStack(intent);
                builder.startActivities();
            } else {
                // Navigate normally to the manifest defined "Up" activity.
                NavUtils.navigateUpTo(currentActivity, intent);
            }
        }
    }

    @Override
    public void onSignInOrCreateAccount() {
        //Get list of accounts on device.
        AccountManager am = AccountManager.get(BaseActivity.this);
        Account[] accountArray = am.getAccountsByType(GoogleAuthUtil.GOOGLE_ACCOUNT_TYPE);
        if (accountArray.length == 0) {
            //Send the user to the "Add Account" page.
            Intent intent = new Intent(Settings.ACTION_ADD_ACCOUNT);
            intent.putExtra(Settings.EXTRA_ACCOUNT_TYPES,
                    new String[]{GoogleAuthUtil.GOOGLE_ACCOUNT_TYPE});
            startActivity(intent);
        } else {
            //Try to log the user in with the first account on the device.
            startLoginProcess();
            mAppNavigationViewAsDrawer.closeNavDrawer();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Perform one-time bootstrap setup, if needed
        DataBootstrapService.startDataBootstrapIfNecessary(this);

        // Check to ensure a Google Account is active for the app. Placing the check here ensures
        // it is run again in the case where a Google Account wasn't present on the device and a
        // picker had to be started.
        if (!AccountUtils.enforceActiveGoogleAccount(this, SELECT_GOOGLE_ACCOUNT_RESULT)) {
            LOGD(TAG, "EnforceActiveGoogleAccount returned false");
            return;
        }

        // Watch for sync state changes
        mSyncStatusObserver.onStatusChanged(0);
        final int mask = ContentResolver.SYNC_OBSERVER_TYPE_PENDING |
                ContentResolver.SYNC_OBSERVER_TYPE_ACTIVE;
        mSyncObserverHandle = ContentResolver.addStatusChangeListener(mask, mSyncStatusObserver);

        startLoginProcess();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mSyncObserverHandle != null) {
            ContentResolver.removeStatusChangeListener(mSyncObserverHandle);
            mSyncObserverHandle = null;
        }
    }

    /**
     * Converts an intent into a {@link Bundle} suitable for use as fragment arguments.
     */
    public static Bundle intentToFragmentArguments(Intent intent) {
        Bundle arguments = new Bundle();
        if (intent == null) {
            return arguments;
        }

        final Uri data = intent.getData();
        if (data != null) {
            arguments.putParcelable("_uri", data);
        }

        final Bundle extras = intent.getExtras();
        if (extras != null) {
            arguments.putAll(intent.getExtras());
        }

        return arguments;
    }

    @Override
    public void onStartLoginProcessRequested() {
        startLoginProcess();
    }

    private void startLoginProcess() {
        LOGD(TAG, "Starting login process.");
        if (!AccountUtils.hasActiveAccount(this)) {
            LOGD(TAG, "No active account, attempting to pick a default.");
            String defaultAccount = AccountUtils.getActiveAccountName(this);
            if (defaultAccount == null) {
                LOGE(TAG, "Failed to pick default account (no accounts). Failing.");
                //complainMustHaveGoogleAccount();
                return;
            }
            LOGD(TAG, "Default to: " + defaultAccount);
            AccountUtils.setActiveAccount(this, defaultAccount);
        }

        if (!AccountUtils.hasActiveAccount(this)) {
            LOGD(TAG, "Can't proceed with login -- no account chosen.");
            return;
        } else {
            LOGD(TAG, "Chosen account: " + AccountUtils.getActiveAccountName(this));
        }

        String accountName = AccountUtils.getActiveAccountName(this);
        LOGD(TAG, "Chosen account: " + AccountUtils.getActiveAccountName(this));

        if (mLoginAndAuthProvider != null && mLoginAndAuthProvider.getAccountName()
                                                                  .equals(accountName)) {
            LOGD(TAG, "Helper already set up; simply starting it.");
            mLoginAndAuthProvider.start();
            return;
        }

        LOGD(TAG, "Starting login process with account " + accountName);

        if (mLoginAndAuthProvider != null) {
            LOGD(TAG, "Tearing down old Helper, was " + mLoginAndAuthProvider.getAccountName());
            if (mLoginAndAuthProvider.isStarted()) {
                LOGD(TAG, "Unregister device from GCM");
                mMessagingRegistration.unregisterDevice();
                LOGD(TAG, "Stopping old Helper");
                mLoginAndAuthProvider.stop();
            }
            mLoginAndAuthProvider = null;
        }

        LOGD(TAG, "Creating and starting new Helper with account: " + accountName);
        mLoginAndAuthProvider =
                LoginAndAuthProvider.provideLoginAndAuth(this, this, accountName);
        mLoginAndAuthProvider.start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        // Handle the select {@code startActivityForResult} from
        // {@code enforceActiveGoogleAccount()} when a Google Account wasn't present on the device.
        if (requestCode == SELECT_GOOGLE_ACCOUNT_RESULT) {
            if (resultCode == RESULT_OK) {
                // Set selected GoogleAccount as active.
                String accountName = data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
                AccountUtils.setActiveAccount(this, accountName);
                onAuthSuccess(accountName, true);
            } else {
                LOGW(TAG, "A Google Account is required to use this application.");
                // This application requires a Google Account to be selected.
                finish();
            }
            return;
        }

        if (mLoginAndAuthProvider == null || !mLoginAndAuthProvider.onActivityResult(requestCode,
                resultCode, data)) {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onStop() {
        LOGD(TAG, "onStop");
        super.onStop();
        if (mLoginAndAuthProvider != null) {
            mLoginAndAuthProvider.stop();
        }
    }

    @Override
    public void onPlusInfoLoaded(String accountName) {
        mAppNavigationViewAsDrawer.updateNavigationItems();
    }

    /**
     * Called when authentication succeeds. This may either happen because the user just
     * authenticated for the first time (and went through the sign in flow), or because it's a
     * returning user.
     *
     * @param accountName        name of the account that just authenticated successfully.
     * @param newlyAuthenticated If true, this user just authenticated for the first time. If false,
     *                           it's a returning user.
     */
    @Override
    public void onAuthSuccess(String accountName, boolean newlyAuthenticated) {
        Account account = new Account(accountName, GoogleAuthUtil.GOOGLE_ACCOUNT_TYPE);
        LOGD(TAG, "onAuthSuccess, account " + accountName + ", newlyAuthenticated="
                + newlyAuthenticated);

        refreshAccountDependantData();

        if (newlyAuthenticated) {
            LOGD(TAG, "Enabling auto sync on content provider for account " + accountName);
            SyncHelper.updateSyncInterval(this, account);
            SyncHelper.requestManualSync(account);
        }

        mAppNavigationViewAsDrawer.updateNavigationItems();
        mMessagingRegistration.registerDevice();
    }

    @Override
    public void onAuthFailure(String accountName) {
        LOGD(TAG, "Auth failed for account " + accountName);
        refreshAccountDependantData();
        mAppNavigationViewAsDrawer.updateNavigationItems();
    }

    protected void refreshAccountDependantData() {
        // Force local data refresh for data that depends on the logged user:
        LOGD(TAG, "Refreshing User Data");
        getContentResolver().notifyChange(ScheduleContract.MySchedule.CONTENT_URI, null, false);
        getContentResolver().notifyChange(ScheduleContract.MyViewedVideos.CONTENT_URI, null, false);
        getContentResolver().notifyChange(
                ScheduleContract.MyFeedbackSubmitted.CONTENT_URI, null, false);
    }

    protected void retryAuth() {
        mLoginAndAuthProvider.retryAuthByUserRequest();
    }

    @Override
    public Toolbar getActionBarToolbar() {
        if (mHeaderViewImpl != null) {
            return mHeaderViewImpl.getActionBarToolbar();
        } else {
            return null;
        }
    }

    @Override
    public void enableActionBarAutoHide(final ListView listView) {
        mHeaderViewImpl.enableActionBarAutoHide(listView);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (mMessagingRegistration != null) {
            mMessagingRegistration.destroy();
        }

        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
        sp.unregisterOnSharedPreferenceChangeListener(this);
    }

    private SyncStatusObserver mSyncStatusObserver = new SyncStatusObserver() {
        @Override
        public void onStatusChanged(int which) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    String accountName = AccountUtils.getActiveAccountName(BaseActivity.this);
                    if (TextUtils.isEmpty(accountName)) {
                        onRefreshingStateChanged(false);
                        mManualSyncRequest = false;
                        return;
                    }

                    Account account = new Account(accountName, GoogleAuthUtil.GOOGLE_ACCOUNT_TYPE);
                    boolean syncActive = ContentResolver.isSyncActive(
                            account, ScheduleContract.CONTENT_AUTHORITY);
                    boolean syncPending = ContentResolver.isSyncPending(
                            account, ScheduleContract.CONTENT_AUTHORITY);
                    if (!syncActive && !syncPending) {
                        mManualSyncRequest = false;
                    }
                    onRefreshingStateChanged(syncActive || mManualSyncRequest);
                }
            });
        }
    };

    protected void onRefreshingStateChanged(boolean refreshing) {
        if (mSwipeRefreshLayout != null) {
            mSwipeRefreshLayout.setRefreshing(refreshing);
        }
    }

    @Override
    public void registerHideableHeaderView(View hideableHeaderView) {
        if (mHeaderViewImpl != null) {
            mHeaderViewImpl.registerHideableHeaderView(hideableHeaderView);
        }
    }

    @Override
    public void deregisterHideableHeaderView(View hideableHeaderView) {
        if (mHeaderViewImpl != null) {
            mHeaderViewImpl.deregisterHideableHeaderView(hideableHeaderView);
        }
    }

    public LUtils getLUtils() {
        return mLUtils;
    }

    @Override
    public boolean canSwipeRefreshChildScrollUp() {
        return false;
    }

    /**
     * Configure this Activity as a floating window, with the given {@code width}, {@code height}
     * and {@code alpha}, and dimming the background with the given {@code dim} value.
     */
    protected void setupFloatingWindow(int width, int height, int alpha, float dim) {
        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.width = getResources().getDimensionPixelSize(width);
        params.height = getResources().getDimensionPixelSize(height);
        params.alpha = alpha;
        params.dimAmount = dim;
        params.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND;
        getWindow().setAttributes(params);
    }

    /**
     * Returns true if the theme sets the {@code R.attr.isFloatingWindow} flag to true.
     */
    protected boolean shouldBeFloatingWindow() {
        Resources.Theme theme = getTheme();
        TypedValue floatingWindowFlag = new TypedValue();

        // Check isFloatingWindow flag is defined in theme.
        if (theme == null || !theme
                .resolveAttribute(R.attr.isFloatingWindow, floatingWindowFlag, true)) {
            return false;
        }

        return (floatingWindowFlag.data != 0);
    }

}
