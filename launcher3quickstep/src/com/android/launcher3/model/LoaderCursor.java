/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.launcher3.model;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.CursorWrapper;
import android.os.UserHandle;
import android.provider.BaseColumns;
import android.text.TextUtils;
import android.util.Log;
import android.util.LongSparseArray;

import com.android.launcher3.InvariantDeviceProfile;
import com.android.launcher3.ItemInfo;
import com.android.launcher3.LauncherAppState;
import com.android.launcher3.LauncherSettings;
import com.android.launcher3.Utilities;
import com.android.launcher3.util.ContentWriter;
import com.android.launcher3.util.GridOccupancy;
import com.android.launcher3.util.IntArray;
import com.android.launcher3.util.IntSparseArrayMap;

import java.net.URISyntaxException;

/**
 * Extension of {@link Cursor} with utility methods for workspace loading.
 */
public class LoaderCursor extends CursorWrapper {

    private static final String TAG = "LoaderCursor";

    public final LongSparseArray<UserHandle> allUsers = new LongSparseArray<>();

    private final Context mContext;
    private final InvariantDeviceProfile mIDP;

    private final IntArray itemsToRemove = new IntArray();
    private final IntArray restoredRows = new IntArray();
    private final IntSparseArrayMap<GridOccupancy> occupied = new IntSparseArrayMap<>();

    public final int titleIndex;

    private final int idIndex;
    private final int containerIndex;
    private final int itemTypeIndex;
    private final int screenIndex;
    private final int cellXIndex;
    private final int cellYIndex;
    private final int profileIdIndex;
    private final int restoredIndex;
    private final int intentIndex;

    // Properties loaded per iteration
    public long serialNumber;
    public UserHandle user;
    public int id;
    public int container;
    public int itemType;
    public int restoreFlag;

    public LoaderCursor(Cursor c, LauncherAppState app) {
        super(c);
        mContext = app.getContext();
        mIDP = app.getInvariantDeviceProfile();

        // Init column indices
        titleIndex = getColumnIndexOrThrow(LauncherSettings.Favorites.TITLE);

        idIndex = getColumnIndexOrThrow(LauncherSettings.Favorites._ID);
        containerIndex = getColumnIndexOrThrow(LauncherSettings.Favorites.CONTAINER);
        itemTypeIndex = getColumnIndexOrThrow(LauncherSettings.Favorites.ITEM_TYPE);
        screenIndex = getColumnIndexOrThrow(LauncherSettings.Favorites.SCREEN);
        cellXIndex = getColumnIndexOrThrow(LauncherSettings.Favorites.CELLX);
        cellYIndex = getColumnIndexOrThrow(LauncherSettings.Favorites.CELLY);
        profileIdIndex = getColumnIndexOrThrow(LauncherSettings.Favorites.PROFILE_ID);
        restoredIndex = getColumnIndexOrThrow(LauncherSettings.Favorites.RESTORED);
        intentIndex = getColumnIndexOrThrow(LauncherSettings.Favorites.INTENT);
    }

    @Override
    public boolean moveToNext() {
        boolean result = super.moveToNext();
        if (result) {
            // Load common properties.
            itemType = getInt(itemTypeIndex);
            container = getInt(containerIndex);
            id = getInt(idIndex);
            serialNumber = getInt(profileIdIndex);
            user = allUsers.get(serialNumber);
            restoreFlag = getInt(restoredIndex);
        }
        return result;
    }

    public Intent parseIntent() {
        String intentDescription = getString(intentIndex);
        try {
            return TextUtils.isEmpty(intentDescription) ?
                    null : Intent.parseUri(intentDescription, 0);
        } catch (URISyntaxException e) {
            Log.e(TAG, "Error parsing Intent");
            return null;
        }
    }

    /**
     * Returns a {@link ContentWriter} which can be used to update the current item.
     */
    public ContentWriter updater() {
       return new ContentWriter(mContext, new ContentWriter.CommitParams(
               BaseColumns._ID + "= ?", new String[]{Integer.toString(id)}));
    }

    /**
     * Marks the current item for removal
     */
    public void markDeleted() {
        itemsToRemove.add(id);
    }

    /**
     * Marks the current item as restored
     */
    public void markRestored() {
        if (restoreFlag != 0) {
            restoredRows.add(id);
            restoreFlag = 0;
        }
    }

    public boolean hasRestoreFlag(int flagMask) {
        return (restoreFlag & flagMask) != 0;
    }

    public void commitRestoredItems() {
        if (restoredRows.size() > 0) {
            // Update restored items that no longer require special handling
            ContentValues values = new ContentValues();
            values.put(LauncherSettings.Favorites.RESTORED, 0);
            mContext.getContentResolver().update(LauncherSettings.Favorites.CONTENT_URI, values,
                    Utilities.createDbSelectionQuery(
                            LauncherSettings.Favorites._ID, restoredRows), null);
        }
    }

    /**
     * Returns true is the item is on workspace
     */
    public boolean isOnWorkspace() {
        return container == LauncherSettings.Favorites.CONTAINER_DESKTOP;
    }

    /**
     * Applies the following properties:
     * {@link ItemInfo#id}
     * {@link ItemInfo#container}
     * {@link ItemInfo#screenId}
     * {@link ItemInfo#cellX}
     * {@link ItemInfo#cellY}
     */
    public void applyCommonProperties(ItemInfo info) {
        info.id = id;
        info.container = container;
        info.screenId = getInt(screenIndex);
        info.cellX = getInt(cellXIndex);
        info.cellY = getInt(cellYIndex);
    }

    /**
     * Adds the {@param info} to {@param dataModel} if it does not overlap with any other item,
     * otherwise marks it for deletion.
     */
    public void checkAndAddItem(ItemInfo info, BgDataModel dataModel) {
        if (checkItemPlacement(info)) {
            dataModel.addItem(mContext, info, false);
        } else {
            markDeleted();
        }
    }

    /**
     * check & update map of what's occupied; used to discard overlapping/invalid items
     */
    protected boolean checkItemPlacement(ItemInfo item) {
        int containerIndex = item.screenId;
        if (item.container != LauncherSettings.Favorites.CONTAINER_DESKTOP) {
            // Skip further checking if it is not the hotseat or workspace container
            return true;
        }

        final int countX = mIDP.numColumns;
        final int countY = mIDP.numRows;
        if (item.container == LauncherSettings.Favorites.CONTAINER_DESKTOP &&
                item.cellX < 0 || item.cellY < 0 ||
                item.cellX + item.spanX > countX || item.cellY + item.spanY > countY) {
            Log.e(TAG, "Error loading shortcut " + item
                    + " into cell (" + containerIndex + "-" + item.screenId + ":"
                    + item.cellX + "," + item.cellY
                    + ") out of screen bounds ( " + countX + "x" + countY + ")");
            return false;
        }

        if (!occupied.containsKey(item.screenId)) {
            GridOccupancy screen = new GridOccupancy(countX + 1, countY + 1);
            occupied.put(item.screenId, screen);
        }
        final GridOccupancy occupancy = occupied.get(item.screenId);

        // Check if any workspace icons overlap with each other
        if (occupancy.isRegionVacant(item.cellX, item.cellY, item.spanX, item.spanY)) {
            occupancy.markCells(item, true);
            return true;
        } else {
            Log.e(TAG, "Error loading shortcut " + item
                    + " into cell (" + containerIndex + "-" + item.screenId + ":"
                    + item.cellX + "," + item.cellX + "," + item.spanX + "," + item.spanY
                    + ") already occupied");
            return false;
        }
    }
}
