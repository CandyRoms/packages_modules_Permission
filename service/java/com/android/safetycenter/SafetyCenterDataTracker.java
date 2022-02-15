/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.safetycenter;

import static android.os.Build.VERSION_CODES.TIRAMISU;

import static java.util.Collections.emptyList;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Binder;
import android.safetycenter.SafetyCenterData;
import android.safetycenter.SafetyCenterEntry;
import android.safetycenter.SafetyCenterEntryGroup;
import android.safetycenter.SafetyCenterEntryOrGroup;
import android.safetycenter.SafetyCenterIssue;
import android.safetycenter.SafetyCenterStaticEntry;
import android.safetycenter.SafetyCenterStaticEntryGroup;
import android.safetycenter.SafetyCenterStatus;
import android.safetycenter.SafetySourceData;
import android.safetycenter.SafetySourceIssue;
import android.safetycenter.SafetySourceStatus;
import android.safetycenter.config.SafetyCenterConfig;
import android.safetycenter.config.SafetySource;
import android.safetycenter.config.SafetySourcesGroup;
import android.util.Log;

import androidx.annotation.RequiresApi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A class that keeps track of all the {@link SafetySourceData} updates received by safety center,
 * and aggregates them into a {@link SafetyCenterData} object to be used by permission controller.
 *
 * <p>This class isn't thread safe. Thread safety must be handled by the caller.
 */
@RequiresApi(TIRAMISU)
final class SafetyCenterDataTracker {

    private static final String TAG = "SafetyCenterDataTracker";

    private final Map<Key, SafetySourceData> mSafetySourceDataForKey = new HashMap<>();

    @NonNull
    private final Context mContext;
    @NonNull
    private final SafetyCenterConfigReader mSafetyCenterConfigReader;

    /**
     * Creates a {@link SafetyCenterDataTracker} using the given {@link Context} and
     * {@link SafetyCenterConfigReader}.
     */
    SafetyCenterDataTracker(
            @NonNull Context context,
            @NonNull SafetyCenterConfigReader safetyCenterConfigReader) {
        mContext = context;
        mSafetyCenterConfigReader = safetyCenterConfigReader;
    }

    /**
     * Adds a {@link SafetySourceData} update for the given {@code packageName} and {@code userId},
     * and returns the updated {@link SafetyCenterData} of the {@code userId}.
     *
     * <p>Returns {@code null} if there was no update to the underlying {@link SafetyCenterData}, or
     * if the {@link SafetyCenterConfig} is not available.
     */
    @Nullable
    SafetyCenterData addSafetySourceData(
            @NonNull SafetySourceData safetySourceData,
            @NonNull String packageName,
            @UserIdInt int userId) {
        if (!configContains(safetySourceData.getId(), packageName)) {
            // TODO(b/218801292): Should this be hard error for the caller?
            return null;
        }

        Key key = Key.of(safetySourceData.getId(), packageName, userId);
        SafetySourceData existingSafetySourceData = mSafetySourceDataForKey.get(key);
        if (safetySourceData.equals(existingSafetySourceData)) {
            return null;
        }

        mSafetySourceDataForKey.put(key, safetySourceData);
        return getSafetyCenterData(userId);
    }

    /**
     * Returns the latest {@link SafetySourceData} update for the given {@code safetySourceId},
     * {@code packageName} and {@code userId}.
     *
     * <p>Returns {@code null} if there was no update.
     */
    @Nullable
    SafetySourceData getSafetySourceData(
            @NonNull String safetySourceId,
            @NonNull String packageName,
            @UserIdInt int userId) {
        if (!configContains(safetySourceId, packageName)) {
            // TODO(b/218801292): Should this be hard error for the caller?
            return null;
        }

        return mSafetySourceDataForKey.get(Key.of(safetySourceId, packageName, userId));
    }

    /** Clears all the {@link SafetySourceData} updates received so far, for all users. */
    void clear() {
        mSafetySourceDataForKey.clear();
    }

    /**
     * Returns the current {@link SafetyCenterData} for the given {@code userId}, aggregated from
     * all the {@link SafetySourceData} updates received so far.
     *
     * <p>Returns an arbitrary default value if no data has been received for the user so far, or if
     * the {@link SafetyCenterConfig} is not available.
     */
    @NonNull
    SafetyCenterData getSafetyCenterData(@UserIdInt int userId) {
        SafetyCenterConfig safetyCenterConfig = mSafetyCenterConfigReader.getSafetyCenterConfig();
        if (safetyCenterConfig == null) {
            Log.w(TAG, "SafetyCenterConfig unavailable, returning default SafetyCenterData");
            return getDefaultSafetyCenterData();
        }

        // TODO(b/218819144): Merge for all profiles.
        return getSafetyCenterData(safetyCenterConfig, userId);
    }

    // TODO(b/219702252): Create a more efficient data structure for this, and update it when the
    //  config changes.
    private boolean configContains(
            @NonNull String safetySourceId,
            @NonNull String packageName) {
        SafetyCenterConfig safetyCenterConfig = mSafetyCenterConfigReader.getSafetyCenterConfig();
        if (safetyCenterConfig == null) {
            Log.w(TAG, "SafetyCenterConfig unavailable, assuming no sources can send/get data");
            return false;
        }

        // TODO(b/217944317): Remove this allowlisting once the test API for the config is
        //  available.
        if (packageName.equals("android.safetycenter.cts")) {
            return true;
        }

        List<SafetySourcesGroup> safetySourcesGroups = safetyCenterConfig.getSafetySourcesGroups();
        for (int i = 0; i < safetySourcesGroups.size(); i++) {
            SafetySourcesGroup safetySourcesGroup = safetySourcesGroups.get(i);

            List<SafetySource> safetySources = safetySourcesGroup.getSafetySources();
            for (int j = 0; j < safetySources.size(); j++) {
                SafetySource safetySource = safetySources.get(j);

                if (safetySource.getType() == SafetySource.SAFETY_SOURCE_TYPE_STATIC) {
                    continue;
                }

                if (safetySourceId.equals(safetySource.getId()) && packageName.equals(
                        safetySource.getPackageName())) {
                    return true;
                }
            }
        }

        return false;
    }

    @NonNull
    private SafetyCenterData getSafetyCenterData(
            @NonNull SafetyCenterConfig safetyCenterConfig,
            @UserIdInt int userId) {
        int maxSafetyCenterEntryLevel = SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_UNKNOWN;
        List<SafetyCenterIssue> safetyCenterIssues = new ArrayList<>();
        List<SafetyCenterEntryOrGroup> safetyCenterEntryOrGroups = new ArrayList<>();
        List<SafetyCenterStaticEntryGroup> safetyCenterStaticEntryGroups = new ArrayList<>();

        List<SafetySourcesGroup> safetySourcesGroups = safetyCenterConfig.getSafetySourcesGroups();
        for (int i = 0; i < safetySourcesGroups.size(); i++) {
            SafetySourcesGroup safetySourcesGroup = safetySourcesGroups.get(i);

            int groupSafetyCenterEntryLevel = SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_UNKNOWN;
            switch (safetySourcesGroup.getType()) {
                case SafetySourcesGroup.SAFETY_SOURCES_GROUP_TYPE_COLLAPSIBLE: {
                    groupSafetyCenterEntryLevel = Math.max(
                            addSafetyCenterIssues(safetyCenterIssues, safetySourcesGroup, userId),
                            addSafetyCenterEntryGroup(
                                    safetyCenterEntryOrGroups, safetySourcesGroup, userId));
                    break;
                }
                case SafetySourcesGroup.SAFETY_SOURCES_GROUP_TYPE_RIGID: {
                    addSafetyCenterStaticEntryGroup(safetyCenterStaticEntryGroups,
                            safetySourcesGroup);
                    break;
                }
                case SafetySourcesGroup.SAFETY_SOURCES_GROUP_TYPE_HIDDEN: {
                    groupSafetyCenterEntryLevel = addSafetyCenterIssues(safetyCenterIssues,
                            safetySourcesGroup, userId);
                    break;
                }
            }
            // TODO(b/219700241): Should we rely on ordering for severity levels?
            maxSafetyCenterEntryLevel = Math.max(maxSafetyCenterEntryLevel,
                    groupSafetyCenterEntryLevel);
        }

        int safetyCenterOverallSeverityLevel = entryToSafetyCenterStatusOverallLevel(
                maxSafetyCenterEntryLevel);
        return new SafetyCenterData(
                new SafetyCenterStatus.Builder()
                        .setSeverityLevel(safetyCenterOverallSeverityLevel)
                        .setTitle(getSafetyCenterStatusTitle(safetyCenterOverallSeverityLevel))
                        .setSummary(getSafetyCenterStatusSummary(safetyCenterOverallSeverityLevel))
                        .build(),
                safetyCenterIssues,
                safetyCenterEntryOrGroups,
                safetyCenterStaticEntryGroups);
    }

    @SafetyCenterEntry.EntrySeverityLevel
    private int addSafetyCenterIssues(
            @NonNull List<SafetyCenterIssue> safetyCenterIssues,
            @NonNull SafetySourcesGroup safetySourcesGroup,
            @UserIdInt int userId) {
        int maxSafetyCenterEntrySeverityLevel = SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_UNKNOWN;
        List<SafetySource> safetySources = safetySourcesGroup.getSafetySources();
        for (int i = 0; i < safetySources.size(); i++) {
            SafetySource safetySource = safetySources.get(i);

            if (safetySource.getType() == SafetySource.SAFETY_SOURCE_TYPE_STATIC) {
                continue;
            }

            Key key = Key.of(safetySource.getId(), safetySource.getPackageName(), userId);
            SafetySourceData safetySourceData = mSafetySourceDataForKey.get(key);
            if (safetySourceData == null) {
                continue;
            }

            List<SafetySourceIssue> safetySourceIssues = safetySourceData.getIssues();
            for (int j = 0; j < safetySourceIssues.size(); j++) {
                SafetySourceIssue safetySourceIssue = safetySourceIssues.get(j);

                SafetyCenterIssue safetyCenterIssue = toSafetyCenterIssue(safetySourceIssue);
                maxSafetyCenterEntrySeverityLevel = Math.max(maxSafetyCenterEntrySeverityLevel,
                        issueToSafetyCenterEntryLevel(safetyCenterIssue.getSeverityLevel()));
                safetyCenterIssues.add(safetyCenterIssue);
            }
        }

        return maxSafetyCenterEntrySeverityLevel;
    }

    @NonNull
    private static SafetyCenterIssue toSafetyCenterIssue(
            @NonNull SafetySourceIssue safetySourceIssue) {
        List<SafetySourceIssue.Action> safetySourceIssueActions = safetySourceIssue.getActions();
        List<SafetyCenterIssue.Action> safetyCenterIssueActions = new ArrayList<>(
                safetySourceIssueActions.size());
        for (int i = 0; i < safetySourceIssueActions.size(); i++) {
            SafetySourceIssue.Action safetySourceIssueAction = safetySourceIssueActions.get(i);

            safetyCenterIssueActions.add(
                    new SafetyCenterIssue.Action.Builder()
                            .setLabel(safetySourceIssueAction.getLabel())
                            .setSuccessMessage(safetySourceIssueAction.getSuccessMessage())
                            .setPendingIntent(safetySourceIssueAction.getPendingIntent())
                            .build()
            );
        }

        // TODO(b/218817233): Add missing fields like: dismissible, shouldConfirmDismissal.
        return new SafetyCenterIssue.Builder(safetySourceIssue.getId())
                .setSeverityLevel(
                        sourceToSafetyCenterIssueSeverityLevel(
                                safetySourceIssue.getSeverityLevel()))
                .setTitle(safetySourceIssue.getTitle())
                .setSummary(safetySourceIssue.getSummary())
                .setSubtitle(safetySourceIssue.getSubtitle())
                .setActions(safetyCenterIssueActions)
                .build();
    }

    @SafetyCenterEntry.EntrySeverityLevel
    private int addSafetyCenterEntryGroup(
            @NonNull List<SafetyCenterEntryOrGroup> safetyCenterEntryOrGroups,
            @NonNull SafetySourcesGroup safetySourcesGroup,
            @UserIdInt int userId) {
        int maxSafetyCenterEntryLevel = SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_UNKNOWN;

        List<SafetySource> safetySources = safetySourcesGroup.getSafetySources();
        List<SafetyCenterEntry> entries = new ArrayList<>(safetySources.size());
        for (int i = 0; i < safetySources.size(); i++) {
            SafetySource safetySource = safetySources.get(i);

            SafetyCenterEntry safetyCenterEntry = toSafetyCenterEntry(safetySource, userId);
            if (safetyCenterEntry == null) {
                continue;
            }

            // TODO(b/219700241): Should we rely on ordering for severity levels?
            maxSafetyCenterEntryLevel = Math.max(maxSafetyCenterEntryLevel,
                    safetyCenterEntry.getSeverityLevel());
            entries.add(safetyCenterEntry);
        }

        // TODO(b/218817233): Add missing fields like: statelessIconType.
        safetyCenterEntryOrGroups.add(
                new SafetyCenterEntryOrGroup(
                        new SafetyCenterEntryGroup.Builder(safetySourcesGroup.getId())
                                .setSeverityLevel(maxSafetyCenterEntryLevel)
                                .setTitle(mSafetyCenterConfigReader.readStringResource(
                                        safetySourcesGroup.getTitleResId()))
                                .setSummary(mSafetyCenterConfigReader.readStringResource(
                                        safetySourcesGroup.getSummaryResId()))
                                .setEntries(entries)
                                .build()
                )
        );

        return maxSafetyCenterEntryLevel;
    }

    @Nullable
    private SafetyCenterEntry toSafetyCenterEntry(@NonNull SafetySource safetySource,
            @UserIdInt int userId) {
        if (safetySource.getType() == SafetySource.SAFETY_SOURCE_TYPE_ISSUE_ONLY) {
            Log.w(TAG, "Issue only safety source found in collapsible group");
            return null;
        }

        Key key = Key.of(safetySource.getId(), safetySource.getPackageName(), userId);
        SafetySourceStatus safetySourceStatus = getSafetySourceStatus(
                mSafetySourceDataForKey.get(key));
        SafetyCenterEntry.Builder safetyCenterEntryBuilder = new SafetyCenterEntry.Builder(
                safetySource.getId());
        // TODO(b/218817233): Add missing fields like: iconAction, statelessIconType.
        if (safetySourceStatus != null) {
            safetyCenterEntryBuilder
                    .setSeverityLevel(sourceToSafetyCenterEntrySeverityLevel(
                            safetySourceStatus.getStatusLevel()))
                    .setTitle(safetySourceStatus.getTitle())
                    .setSummary(safetySourceStatus.getSummary())
                    .setEnabled(safetySourceStatus.isEnabled())
                    .setPendingIntent(safetySourceStatus.getPendingIntent());
        } else {
            PendingIntent pendingIntent = toPendingIntent(safetySource.getIntentAction(),
                    safetySource.getPackageName());
            if (pendingIntent == null) {
                // TODO(b/218817241): We may make the PendingIntent nullable, in which case
                //  we won't want to skip here.
                return null;
            }

            // TODO(b/218817233): Add missing fields like: enabled.
            safetyCenterEntryBuilder
                    .setSeverityLevel(SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_NONE)
                    .setTitle(mSafetyCenterConfigReader.readStringResource(
                            safetySource.getTitleResId()))
                    .setSummary(mSafetyCenterConfigReader.readStringResource(
                            safetySource.getSummaryResId()))
                    .setPendingIntent(pendingIntent);
        }

        return safetyCenterEntryBuilder.build();
    }

    private void addSafetyCenterStaticEntryGroup(
            @NonNull List<SafetyCenterStaticEntryGroup> safetyCenterStaticEntryGroups,
            @NonNull SafetySourcesGroup safetySourcesGroup) {
        List<SafetySource> safetySources = safetySourcesGroup.getSafetySources();
        List<SafetyCenterStaticEntry> staticEntries = new ArrayList<>(safetySources.size());
        for (int i = 0; i < safetySources.size(); i++) {
            SafetySource safetySource = safetySources.get(i);

            if (safetySource.getType() != SafetySource.SAFETY_SOURCE_TYPE_STATIC) {
                Log.w(TAG, "Non-static safety source found in rigid group");
                continue;
            }

            PendingIntent pendingIntent = toPendingIntent(safetySource.getIntentAction(),
                    null);
            if (pendingIntent == null) {
                // TODO(b/218817241): We may make the PendingIntent nullable, in which case we
                //  won't want to skip here.
                continue;
            }

            staticEntries.add(
                    new SafetyCenterStaticEntry(
                            mSafetyCenterConfigReader.readStringResource(
                                    safetySource.getTitleResId()),
                            mSafetyCenterConfigReader.readStringResource(
                                    safetySource.getSummaryResId()),
                            pendingIntent
                    )
            );
        }

        safetyCenterStaticEntryGroups.add(
                new SafetyCenterStaticEntryGroup(
                        mSafetyCenterConfigReader.readStringResource(
                                safetySourcesGroup.getTitleResId()),
                        staticEntries
                )
        );
    }

    @Nullable
    private PendingIntent toPendingIntent(
            @Nullable String intentAction,
            @Nullable String packageName) {
        if (intentAction == null) {
            return null;
        }

        Context context;
        if (packageName == null) {
            context = mContext;
        } else {
            try {
                context = mContext.createPackageContext(packageName, 0);
            } catch (NameNotFoundException e) {
                Log.w(TAG, String.format("Package name %s not found", packageName), e);
                return null;
            }
        }

        // TODO(b/219699223): Is it safe to create a PendingIntent as system server here?
        final long identity = Binder.clearCallingIdentity();
        try {
            // TODO(b/218816518): May need to create a unique requestCode per PendingIntent.
            return PendingIntent.getActivity(
                    context,
                    0,
                    new Intent(intentAction),
                    PendingIntent.FLAG_IMMUTABLE);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @NonNull
    private static SafetyCenterData getDefaultSafetyCenterData() {
        return new SafetyCenterData(
                new SafetyCenterStatus.Builder()
                        .setSeverityLevel(SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_UNKNOWN)
                        .setTitle(getSafetyCenterStatusTitle(
                                SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_UNKNOWN))
                        .setSummary(getSafetyCenterStatusSummary(
                                SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_UNKNOWN))
                        .build(),
                emptyList(),
                emptyList(),
                emptyList()
        );
    }

    @Nullable
    private static SafetySourceStatus getSafetySourceStatus(
            @Nullable SafetySourceData safetySourceData) {
        if (safetySourceData == null) {
            return null;
        }

        return safetySourceData.getStatus();
    }

    @SafetyCenterStatus.OverallSeverityLevel
    private static int entryToSafetyCenterStatusOverallLevel(
            @SafetyCenterEntry.EntrySeverityLevel int safetyCenterEntrySeverityLevel) {
        switch (safetyCenterEntrySeverityLevel) {
            case SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_UNKNOWN:
                return SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_UNKNOWN;
            case SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_NONE:
            case SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_OK:
                return SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_OK;
            case SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_RECOMMENDATION:
                return SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_RECOMMENDATION;
            case SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_CRITICAL_WARNING:
                return SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_CRITICAL_WARNING;
        }

        throw new IllegalArgumentException(
                String.format("Unexpected SafetyCenterEntry.EntrySeverityLevel: %s",
                        safetyCenterEntrySeverityLevel));
    }

    @SafetyCenterEntry.EntrySeverityLevel
    private static int issueToSafetyCenterEntryLevel(
            @SafetyCenterIssue.IssueSeverityLevel int safetyCenterIssueSeverityLevel) {
        switch (safetyCenterIssueSeverityLevel) {
            case SafetyCenterIssue.ISSUE_SEVERITY_LEVEL_OK:
                return SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_OK;
            case SafetyCenterIssue.ISSUE_SEVERITY_LEVEL_RECOMMENDATION:
                return SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_RECOMMENDATION;
            case SafetyCenterIssue.ISSUE_SEVERITY_LEVEL_CRITICAL_WARNING:
                return SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_CRITICAL_WARNING;
        }

        throw new IllegalArgumentException(
                String.format("Unexpected SafetyCenterIssue.IssueSeverityLevel: %s",
                        safetyCenterIssueSeverityLevel));
    }

    @SafetyCenterEntry.EntrySeverityLevel
    private static int sourceToSafetyCenterEntrySeverityLevel(
            @SafetySourceStatus.StatusLevel int safetySourceStatusLevel) {
        switch (safetySourceStatusLevel) {
            case SafetySourceStatus.STATUS_LEVEL_NONE:
                return SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_NONE;
            case SafetySourceStatus.STATUS_LEVEL_OK:
                return SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_OK;
            case SafetySourceStatus.STATUS_LEVEL_RECOMMENDATION:
                return SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_RECOMMENDATION;
            case SafetySourceStatus.STATUS_LEVEL_CRITICAL_WARNING:
                return SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_CRITICAL_WARNING;
        }

        throw new IllegalArgumentException(
                String.format("Unexpected SafetySourceStatus.StatusLevel: %s",
                        safetySourceStatusLevel));
    }

    @SafetyCenterIssue.IssueSeverityLevel
    private static int sourceToSafetyCenterIssueSeverityLevel(
            @SafetySourceIssue.SeverityLevel int safetySourceIssueSeverityLevel) {
        switch (safetySourceIssueSeverityLevel) {
            case SafetySourceIssue.SEVERITY_LEVEL_INFORMATION:
                return SafetyCenterIssue.ISSUE_SEVERITY_LEVEL_OK;
            case SafetySourceIssue.SEVERITY_LEVEL_RECOMMENDATION:
                return SafetyCenterIssue.ISSUE_SEVERITY_LEVEL_RECOMMENDATION;
            case SafetySourceIssue.SEVERITY_LEVEL_CRITICAL_WARNING:
                return SafetyCenterIssue.ISSUE_SEVERITY_LEVEL_CRITICAL_WARNING;
        }

        throw new IllegalArgumentException(
                String.format("Unexpected SafetySourceIssue.SeverityLevel: %s",
                        safetySourceIssueSeverityLevel));
    }

    // TODO(b/218801295): Use the right strings and localize them.
    private static String getSafetyCenterStatusTitle(
            @SafetyCenterStatus.OverallSeverityLevel int overallSeverityLevel) {
        switch (overallSeverityLevel) {
            case SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_UNKNOWN:
                return "Unknown";
            case SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_OK:
                return "All good";
            case SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_RECOMMENDATION:
                return "Some warnings";
            case SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_CRITICAL_WARNING:
                return "Uh-oh";
        }

        throw new IllegalArgumentException(
                String.format("Unexpected SafetyCenterStatus.OverallSeverityLevel: %s",
                        overallSeverityLevel));
    }

    // TODO(b/218801295): Use the right strings and localize them.
    private static String getSafetyCenterStatusSummary(
            @SafetyCenterStatus.OverallSeverityLevel int overallSeverityLevel) {
        switch (overallSeverityLevel) {
            case SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_UNKNOWN:
                return "Unknown safety status";
            case SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_OK:
                return "No problemo maestro";
            case SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_RECOMMENDATION:
                return "Careful there";
            case SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_CRITICAL_WARNING:
                return "Code red";
        }

        throw new IllegalArgumentException(
                String.format("Unexpected SafetyCenterStatus.OverallSeverityLevel: %s",
                        overallSeverityLevel));
    }

    /**
     * A key for {@link SafetySourceData} updates; based on the {@code safetySourceId}, {@code
     * packageName} and {@code userId}.
     */
    // TODO(b/219697341): Look into using AutoValue for this data class.
    private static final class Key {
        @NonNull
        private final String mSafetySourceId;
        @NonNull
        private final String mPackageName;
        @UserIdInt
        private final int mUserId;

        private Key(
                @NonNull String safetySourceId,
                @NonNull String packageName,
                @UserIdInt int userId) {
            mSafetySourceId = safetySourceId;
            mPackageName = packageName;
            mUserId = userId;
        }

        @NonNull
        private static Key of(
                @NonNull String safetySourceId,
                @NonNull String packageName,
                @UserIdInt int userId) {
            return new Key(safetySourceId, packageName, userId);
        }

        @Override
        public String toString() {
            return "Key{"
                    + "mSafetySourceId='" + mSafetySourceId + '\''
                    + ", mPackageName='" + mPackageName + '\''
                    + ", mUserId=" + mUserId + '\''
                    + '}';
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key)) return false;
            Key key = (Key) o;
            return mSafetySourceId.equals(key.mSafetySourceId) && mPackageName.equals(
                    key.mPackageName) && mUserId == key.mUserId;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mSafetySourceId, mPackageName, mUserId);
        }
    }
}
