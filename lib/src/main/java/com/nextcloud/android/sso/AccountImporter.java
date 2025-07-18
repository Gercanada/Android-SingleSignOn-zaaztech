/*
 * Nextcloud Android SingleSignOn Library
 *
 * SPDX-FileCopyrightText: 2018-2024 Nextcloud GmbH and Nextcloud contributors
 * SPDX-FileCopyrightText: 2021-2023 Stefan Niedermann <info@niedermann.it>
 * SPDX-FileCopyrightText: 2018-2019 Tobias Kaminsky <tobias@kaminsky.me>
 * SPDX-FileCopyrightText: 2017-2019 David Luhmer <david-dev@live.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.android.sso;

import static android.app.Activity.RESULT_CANCELED;
import static android.app.Activity.RESULT_OK;
import static com.nextcloud.android.sso.Constants.NEXTCLOUD_FILES_ACCOUNT;
import static com.nextcloud.android.sso.Constants.NEXTCLOUD_SSO;
import static com.nextcloud.android.sso.Constants.NEXTCLOUD_SSO_EXCEPTION;
import static com.nextcloud.android.sso.Constants.SSO_SHARED_PREFERENCE;

import android.Manifest;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.nextcloud.android.sso.exceptions.AccountImportCancelledException;
import com.nextcloud.android.sso.exceptions.AndroidGetAccountsPermissionNotGranted;
import com.nextcloud.android.sso.exceptions.NextcloudFilesAppAccountNotFoundException;
import com.nextcloud.android.sso.exceptions.NextcloudFilesAppAccountPermissionNotGrantedException;
import com.nextcloud.android.sso.exceptions.NextcloudFilesAppNotInstalledException;
import com.nextcloud.android.sso.exceptions.NextcloudFilesAppNotSupportedException;
import com.nextcloud.android.sso.exceptions.SSOException;
import com.nextcloud.android.sso.exceptions.UnknownErrorException;
import com.nextcloud.android.sso.model.SingleSignOnAccount;
import com.nextcloud.android.sso.ui.UiExceptionManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import io.reactivex.annotations.NonNull;
import io.reactivex.annotations.Nullable;

public class AccountImporter {

    private static final String TAG = AccountImporter.class.getCanonicalName();
    private static final String PREF_ACCOUNT_STRING = "PREF_ACCOUNT_STRING";
    private static final String AUTH_TOKEN_SSO = "SSO";

    public static final int CHOOSE_ACCOUNT_SSO = 4242;
    public static final int REQUEST_AUTH_TOKEN_SSO = 4243;
    public static final int REQUEST_GET_ACCOUNTS_PERMISSION = 4244;

    private static SharedPreferences SHARED_PREFERENCES;

    public static boolean accountsToImportAvailable(Context context) {
        return findAccounts(context).size() > 0;
    }

    public static void pickNewAccount(Activity activity) throws NextcloudFilesAppNotInstalledException,
        AndroidGetAccountsPermissionNotGranted {
        Log.d(TAG, "pickNewAccount called");
        checkAndroidAccountPermissions(activity);

        Log.d(TAG, "Calling appInstalledOrNot...");
        if (appInstalledOrNot(activity)) {
            Log.d(TAG, "App is installed, creating account chooser intent");
            String[] accountTypes = FilesAppTypeRegistry.getInstance().getAccountTypes();
            Log.d(TAG, "Account types: " + java.util.Arrays.toString(accountTypes));
            Intent intent = AccountManager.newChooseAccountIntent(null, null, accountTypes,
                true, null, AUTH_TOKEN_SSO, null, null);
            activity.startActivityForResult(intent, CHOOSE_ACCOUNT_SSO);
        } else {
            Log.d(TAG, "App is NOT installed, throwing NextcloudFilesAppNotInstalledException");
            throw new NextcloudFilesAppNotInstalledException(activity);
        }
    }

    public static void pickNewAccount(Fragment fragment) throws NextcloudFilesAppNotInstalledException,
        AndroidGetAccountsPermissionNotGranted {
        checkAndroidAccountPermissions(fragment.getContext());

        if (appInstalledOrNot(fragment.requireContext())) {
            Intent intent = AccountManager.newChooseAccountIntent(null, null, FilesAppTypeRegistry.getInstance().getAccountTypes(),
                true, null, AUTH_TOKEN_SSO, null, null);
            fragment.startActivityForResult(intent, CHOOSE_ACCOUNT_SSO);
        } else {
            throw new NextcloudFilesAppNotInstalledException(fragment.requireContext());
        }
    }

    public static void requestAndroidAccountPermissionsAndPickAccount(Activity activity) {
        ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.GET_ACCOUNTS},
            REQUEST_GET_ACCOUNTS_PERMISSION);
    }

    private static void checkAndroidAccountPermissions(Context context) throws AndroidGetAccountsPermissionNotGranted {
        // https://developer.android.com/reference/android/accounts/AccountManager#getAccountsByType(java.lang.String)
        // Caller targeting API level below Build.VERSION_CODES.O that have not been granted the
        // Manifest.permission.GET_ACCOUNTS permission, will only see those accounts managed by
        // AbstractAccountAuthenticators whose signature matches the client.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // Do something for lollipop and above versions
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.GET_ACCOUNTS) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Permission not granted yet!");
                throw new AndroidGetAccountsPermissionNotGranted(context);
            } else {
                Log.d(TAG, "Permission granted!");
            }
        }
    }

    private static boolean appInstalledOrNot(Context context) {
        boolean returnValue = false;
        PackageManager pm = context.getPackageManager();

        Log.d(TAG, "Checking for installed apps...");

        // Debug: List all packages to see if we can see zaaztech apps
        try {
            List<android.content.pm.PackageInfo> packages = pm.getInstalledPackages(0);
            Log.d(TAG, "Total packages installed: " + packages.size());
            for (android.content.pm.PackageInfo pkg : packages) {
                if (pkg.packageName.contains("zaaztech")) {
                    Log.d(TAG, "Found zaaztech package: " + pkg.packageName);
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "Error listing packages: " + e.getMessage());
        }

        for (final var appType : FilesAppTypeRegistry.getInstance().getTypes()) {
            Log.d(TAG, "Checking package: " + appType.packageId() + " (accountType: " + appType.accountType() + ", stage: " + appType.stage() + ")");
            try {
                pm.getPackageInfo(appType.packageId(), PackageManager.GET_ACTIVITIES);
                Log.d(TAG, "Found installed app: " + appType.packageId());
                returnValue = true;
                break;
            } catch (PackageManager.NameNotFoundException e) {
                Log.d(TAG, "Package not found: " + appType.packageId() + " - " + e.getMessage());
                // Try with different flags
                try {
                    pm.getPackageInfo(appType.packageId(), 0);
                    Log.d(TAG, "Found installed app with flag 0: " + appType.packageId());
                    returnValue = true;
                    break;
                } catch (PackageManager.NameNotFoundException e2) {
                    Log.d(TAG, "Package not found with flag 0: " + appType.packageId() + " - " + e2.getMessage());
                    // Try with MATCH_UNINSTALLED_PACKAGES
                    try {
                        pm.getPackageInfo(appType.packageId(), PackageManager.MATCH_UNINSTALLED_PACKAGES);
                        Log.d(TAG, "Found installed app with MATCH_UNINSTALLED_PACKAGES: " + appType.packageId());
                        returnValue = true;
                        break;
                    } catch (PackageManager.NameNotFoundException e3) {
                        Log.d(TAG, "Package not found with MATCH_UNINSTALLED_PACKAGES: " + appType.packageId() + " - " + e3.getMessage());
                    }
                }
            }
        }

        Log.d(TAG, "appInstalledOrNot result: " + returnValue);
        return returnValue;
    }

    // Find all currently installed nextcloud accounts on the phone
    public static List<Account> findAccounts(final Context context) {
        final AccountManager accMgr = AccountManager.get(context);
        final Account[] accounts = accMgr.getAccounts();

        List<Account> accountsAvailable = new ArrayList<>();
        for (final Account account : accounts) {
            for (String accountType : FilesAppTypeRegistry.getInstance().getAccountTypes()) {
                if (accountType.equals(account.type)) {
                    accountsAvailable.add(account);
                }
            }
        }
        return accountsAvailable;
    }


    public static Account getAccountForName(Context context, String name) {
        for (Account account : findAccounts(context)) {
            if (account.name.equals(name)) {
                return account;
            }
        }
        return null;
    }

    public static void clearAllAuthTokens(Context context) {
        SharedPreferences mPrefs = getSharedPreferences(context);
        for (String key : mPrefs.getAll().keySet()) {
            if (key.startsWith(PREF_ACCOUNT_STRING)) {
                mPrefs.edit().remove(key).apply();
            }
        }
    }

    public static SingleSignOnAccount getSingleSignOnAccount(Context context, final String accountName)
        throws NextcloudFilesAppAccountNotFoundException {
        SharedPreferences mPrefs = getSharedPreferences(context);
        String prefKey = getPrefKeyForAccount(accountName);

        if (mPrefs.contains(prefKey)) {
            try {
                return SingleSignOnAccount.fromString(mPrefs.getString(prefKey, null));
            } catch (ClassNotFoundException | IOException e) {
                Log.e(TAG, "[getSingleSignOnAccount]", e);
            }
        }
        throw new NextcloudFilesAppAccountNotFoundException(context, accountName);
    }

    public static SingleSignOnAccount extractSingleSignOnAccountFromResponse(Intent intent, Context context) {
        Bundle future = intent.getBundleExtra(NEXTCLOUD_SSO);

        String accountName = future.getString(AccountManager.KEY_ACCOUNT_NAME);
        String userId = future.getString(Constants.SSO_USER_ID);
        if (userId == null) {
            // backwards compatibility
            userId = future.getString("username");
        }
        String token = future.getString(Constants.SSO_TOKEN);
        String serverUrl = future.getString(Constants.SSO_SERVER_URL);
        String type = future.getString("accountType");

        SharedPreferences mPrefs = getSharedPreferences(context);
        String prefKey = getPrefKeyForAccount(accountName);
        SingleSignOnAccount ssoAccount = new SingleSignOnAccount(accountName, userId, token, serverUrl, type);
        try {
            mPrefs.edit().putString(prefKey, SingleSignOnAccount.toString(ssoAccount)).apply();
        } catch (IOException e) {
            Log.e(TAG, "SSO failed", e);
        }
        return ssoAccount;
    }


    public interface IAccountAccessGranted {
        void accountAccessGranted(SingleSignOnAccount singleSignOnAccount);
    }

    public static void onActivityResult(int requestCode, int resultCode, Intent data, Activity activity,
                                        IAccountAccessGranted callback) throws AccountImportCancelledException {
        onActivityResult(requestCode, resultCode, data, activity, null, callback);
    }

    public static void onActivityResult(int requestCode, int resultCode, Intent data, Fragment fragment,
                                        IAccountAccessGranted callback) throws AccountImportCancelledException {
        onActivityResult(requestCode, resultCode, data, null, fragment, callback);
    }

    private static void onActivityResult(int requestCode, int resultCode, Intent data, Activity activity,
                                         Fragment fragment, IAccountAccessGranted callback)
        throws AccountImportCancelledException {
        Context context = (activity != null) ? activity : fragment.getContext();

        if (resultCode == RESULT_OK) {
            switch (requestCode) {
                case CHOOSE_ACCOUNT_SSO:
                    try {
                        if (activity != null) {
                            requestAuthToken(activity, data);
                        } else {
                            requestAuthToken(fragment, data);
                        }
                    } catch (NextcloudFilesAppNotSupportedException |
                             NextcloudFilesAppAccountPermissionNotGrantedException e) {
                        UiExceptionManager.showDialogForException(context, e);
                    }
                    break;
                case REQUEST_AUTH_TOKEN_SSO:
                    SingleSignOnAccount singleSignOnAccount = extractSingleSignOnAccountFromResponse(data, context);
                    callback.accountAccessGranted(singleSignOnAccount);
                    break;
                case REQUEST_GET_ACCOUNTS_PERMISSION:
                    try {
                        if (activity != null) {
                            pickNewAccount(activity);
                        } else {
                            pickNewAccount(fragment);
                        }
                    } catch (NextcloudFilesAppNotInstalledException |
                             AndroidGetAccountsPermissionNotGranted e) {
                        UiExceptionManager.showDialogForException(context, e);
                    }
                    break;
                default:
                    break;
            }
        } else if (resultCode == RESULT_CANCELED) {
            switch (requestCode) {
                case CHOOSE_ACCOUNT_SSO:
                    // nothing to do here
                    throw new AccountImportCancelledException(context);
                case REQUEST_AUTH_TOKEN_SSO:
                    try {
                        handleFailedAuthRequest(context, data);
                    } catch (SSOException e) {
                        UiExceptionManager.showDialogForException(context, e);
                    } catch (Exception e) {
                        Toast.makeText(context, e.getMessage(), Toast.LENGTH_LONG).show();
                        //e.printStackTrace();
                        Log.e(TAG, e.getMessage());
                    }
                    break;
                case REQUEST_GET_ACCOUNTS_PERMISSION:
                    UiExceptionManager.showDialogForException(context, new AndroidGetAccountsPermissionNotGranted(context));
                    break;
                default:
                    break;
            }
        }
    }

    public static void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults, Activity activity) {
        onRequestPermissionsResult(requestCode, permissions, grantResults, activity, null);
    }

    public static void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults, Fragment fragment) {
        onRequestPermissionsResult(requestCode, permissions, grantResults, null, fragment);
    }

    private static void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults, Activity activity, Fragment fragment) {
        Context context = (activity != null) ? activity : fragment.getContext();

        switch (requestCode) {
            case REQUEST_GET_ACCOUNTS_PERMISSION:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Permissions have been granted.. start pick account dialog
                    try {
                        if (activity != null) {
                            pickNewAccount(activity);
                        } else {
                            pickNewAccount(fragment);
                        }
                    } catch (NextcloudFilesAppNotInstalledException |
                             AndroidGetAccountsPermissionNotGranted e) {
                        UiExceptionManager.showDialogForException(context, e);
                    }
                } else {
                    // user declined the permission request..
                    UiExceptionManager.showDialogForException(context, new AndroidGetAccountsPermissionNotGranted(context));
                }
                break;
            default:
                break;
        }

    }

    public static void handleFailedAuthRequest(@NonNull Context context, @Nullable Intent data) throws SSOException {
        if (data != null) {
            String exception = data.getStringExtra(NEXTCLOUD_SSO_EXCEPTION);
            throw SSOException.parseNextcloudCustomException(context, new Exception(exception));
        } else {
            Log.e(TAG, "handleFailedAuthRequest failed - data is null");
            throw new UnknownErrorException("Authentication request failed - no details available");
        }
    }

    public static void authenticateSingleSignAccount(Fragment fragment, SingleSignOnAccount account) throws NextcloudFilesAppNotSupportedException, NextcloudFilesAppAccountPermissionNotGrantedException {
        Intent intent = new Intent();
        intent.putExtra(AccountManager.KEY_ACCOUNT_NAME, account.name);
        requestAuthToken(fragment, intent);
    }

    public static void authenticateSingleSignAccount(Activity activity, SingleSignOnAccount account) throws NextcloudFilesAppNotSupportedException, NextcloudFilesAppAccountPermissionNotGrantedException {
        Intent intent = new Intent();
        intent.putExtra(AccountManager.KEY_ACCOUNT_NAME, account.name);
        requestAuthToken(activity, intent);
    }

    public static void requestAuthToken(Fragment fragment, Intent intent) throws NextcloudFilesAppNotSupportedException, NextcloudFilesAppAccountPermissionNotGrantedException {
        Intent authIntent = buildRequestAuthTokenIntent(fragment.getContext(), intent);
        try {
            fragment.startActivityForResult(authIntent, REQUEST_AUTH_TOKEN_SSO);
        } catch (ActivityNotFoundException e) {
            throw new NextcloudFilesAppNotSupportedException(fragment.requireContext());
        }
    }

    public static void requestAuthToken(Activity activity, Intent intent) throws NextcloudFilesAppNotSupportedException, NextcloudFilesAppAccountPermissionNotGrantedException {
        Intent authIntent = buildRequestAuthTokenIntent(activity, intent);
        try {
            activity.startActivityForResult(authIntent, REQUEST_AUTH_TOKEN_SSO);
        } catch (ActivityNotFoundException e) {
            throw new NextcloudFilesAppNotSupportedException(activity);
        }
    }

    private static Intent buildRequestAuthTokenIntent(Context context, Intent intent) throws NextcloudFilesAppAccountPermissionNotGrantedException {
        String accountName = intent.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
        Account account = AccountImporter.getAccountForName(context, accountName);
        if (account == null) {
            throw new NextcloudFilesAppAccountPermissionNotGrantedException(context);
        }

        String componentName = FilesAppTypeRegistry.getInstance().findByAccountType(account.type).packageId();

        Intent authIntent = new Intent();
        authIntent.setComponent(new ComponentName(componentName,
            "com.owncloud.android.ui.activity.SsoGrantPermissionActivity"));
        authIntent.putExtra(NEXTCLOUD_FILES_ACCOUNT, account);
        return authIntent;
    }

    public static SharedPreferences getSharedPreferences(Context context) {
        if (SHARED_PREFERENCES != null) {
            return SHARED_PREFERENCES;
        } else {
            return context.getSharedPreferences(SSO_SHARED_PREFERENCE, Context.MODE_PRIVATE);
        }
    }

    protected static String getPrefKeyForAccount(String accountName) {
        return PREF_ACCOUNT_STRING + accountName;
    }


    /**
     * Allows developers to set the shared preferences that the account information should be stored in.
     * This is helpful when writing unit tests
     */
    public static void setSharedPreferences(SharedPreferences sharedPreferences) {
        AccountImporter.SHARED_PREFERENCES = sharedPreferences;
    }
}