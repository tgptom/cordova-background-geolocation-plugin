package com.marianhello.bgloc;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.Context;
import android.os.Bundle;

/**
 * Created by finch on 19/07/16.
 */
public class ResourceResolver {

    private static final String RESOURCE_PREFIX = "plugin_bgloc_";
    private static final String ACCOUNT_NAME_RESOURCE = RESOURCE_PREFIX + "account_name";
    private static final String ACCOUNT_TYPE_RESOURCE = RESOURCE_PREFIX + "account_type";
    private static final String AUTHORITY_TYPE_RESOURCE = RESOURCE_PREFIX + "content_authority";

    private Context mContext;

    protected ResourceResolver() {}

    private ResourceResolver(Context context) {
        mContext = context;
    }

    private Context getApplicationContext() {
        return mContext.getApplicationContext();
    }

    public int getAppResource(String name, String type) {
        Context appContext = getApplicationContext();
        return appContext.getResources().getIdentifier(name, type, appContext.getPackageName());
    }

    public Integer getDrawable(String resourceName) {
        return getAppResource(resourceName, "drawable");
    }

    public String getString(String name) {
        return getApplicationContext().getString(getAppResource(name, "string"));
    }

    private String getManifestValue(String name) {
        Context appContext = getApplicationContext();

        try {
            ApplicationInfo applicationInfo = appContext.getPackageManager()
                    .getApplicationInfo(appContext.getPackageName(), PackageManager.GET_META_DATA);
            Bundle metaData = applicationInfo.metaData;

            if (metaData == null || !metaData.containsKey(name)) {
                return null;
            }

            Object value = metaData.get(name);

            if (value instanceof Integer) {
                return appContext.getString((Integer) value);
            }

            if (value != null) {
                return value.toString();
            }
        } catch (PackageManager.NameNotFoundException ignored) {}

        return null;
    }

    private String getConfigValue(String name) {
        String value = getManifestValue(name);
        return value != null ? value : getString(name);
    }

    public String getAccountName() {
        return getConfigValue(ACCOUNT_NAME_RESOURCE);
    }

    public String getAccountType() {
        return getConfigValue(ACCOUNT_TYPE_RESOURCE);
    }

    public String getAuthority() {
        return getConfigValue(AUTHORITY_TYPE_RESOURCE);
    }

    public static ResourceResolver newInstance(Context context) {
        return new ResourceResolver(context);
    }
}
