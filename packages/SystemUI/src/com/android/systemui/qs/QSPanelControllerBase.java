/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.qs;

import static com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import static com.android.systemui.qs.dagger.QSFragmentModule.QS_USING_MEDIA_PLAYER;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.res.Configuration;
import android.content.res.Configuration.Orientation;
import android.database.ContentObserver;
import android.metrics.LogMaker;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.systemui.Dumpable;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.media.controls.ui.MediaHost;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.qs.QSTileView;
import com.android.systemui.qs.customize.QSCustomizerController;
import com.android.systemui.qs.external.CustomTile;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileViewImpl;
import com.android.systemui.util.LargeScreenUtils;
import com.android.systemui.util.ViewController;
import com.android.systemui.util.animation.DisappearParameters;
import com.android.systemui.util.settings.SystemSettings;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.inject.Named;

import kotlin.Unit;
import kotlin.jvm.functions.Function1;

/**
 * Controller for QSPanel views.
 *
 * @param <T> Type of QSPanel.
 */
public abstract class QSPanelControllerBase<T extends QSPanel> extends ViewController<T>
        implements Dumpable{
    private static final String TAG = "QSPanelControllerBase";
    protected final QSHost mHost;
    private final QSCustomizerController mQsCustomizerController;
    private final boolean mUsingMediaPlayer;
    protected final MediaHost mMediaHost;
    protected final MetricsLogger mMetricsLogger;
    private final UiEventLogger mUiEventLogger;
    protected final QSLogger mQSLogger;
    private final DumpManager mDumpManager;
    protected final ArrayList<TileRecord> mRecords = new ArrayList<>();
    protected boolean mShouldUseSplitNotificationShade;

    @Nullable
    private Consumer<Boolean> mMediaVisibilityChangedListener;
    @Orientation
    private int mLastOrientation;
    private String mCachedSpecs = "";
    @Nullable
    private QSTileRevealController mQsTileRevealController;
    private float mRevealExpansion;

    private final QSHost.Callback mQSHostCallback = this::setTiles;

    @VisibleForTesting
    protected final QSPanel.OnConfigurationChangedListener mOnConfigurationChangedListener =
            new QSPanel.OnConfigurationChangedListener() {
                @Override
                public void onConfigurationChange(Configuration newConfig) {
                    final boolean previousSplitShadeState = mShouldUseSplitNotificationShade;
                    final int previousOrientation = mLastOrientation;
                    mShouldUseSplitNotificationShade =
                            LargeScreenUtils.shouldUseSplitNotificationShade(getResources());
                    mLastOrientation = newConfig.orientation;

                    mQSLogger.logOnConfigurationChanged(
                        /* oldOrientation= */ previousOrientation,
                        /* newOrientation= */ mLastOrientation,
                        /* oldShouldUseSplitShade= */ previousSplitShadeState,
                        /* newShouldUseSplitShade= */ mShouldUseSplitNotificationShade,
                        /* containerName= */ mView.getDumpableTag());

                    switchTileLayoutIfNeeded();
                    onConfigurationChanged();
                    if (previousSplitShadeState != mShouldUseSplitNotificationShade) {
                        onSplitShadeChanged(mShouldUseSplitNotificationShade);
                    }
                    if (mView.getTileLayout() != null) {
                        mView.getTileLayout().updateSettings();
                        setTiles();
                    }
                }
            };

    protected void onConfigurationChanged() { }

    protected void onSplitShadeChanged(boolean shouldUseSplitNotificationShade) { }

    private final Function1<Boolean, Unit> mMediaHostVisibilityListener = (visible) -> {
        if (mMediaVisibilityChangedListener != null) {
            mMediaVisibilityChangedListener.accept(visible);
        }
        switchTileLayout(false);
        return null;
    };

    private boolean mUsingHorizontalLayout;

    @Nullable
    private Runnable mUsingHorizontalLayoutChangedListener;

    protected final SystemSettings mSystemSettings;
    private final ContentObserver mSettingsObserver;

    protected QSPanelControllerBase(
            T view,
            QSHost host,
            QSCustomizerController qsCustomizerController,
            @Named(QS_USING_MEDIA_PLAYER) boolean usingMediaPlayer,
            MediaHost mediaHost,
            MetricsLogger metricsLogger,
            UiEventLogger uiEventLogger,
            QSLogger qsLogger,
            DumpManager dumpManager,
	    @Main Handler mainHandler,
	    SystemSettings systemSettings
    ) {
        super(view);
        mHost = host;
        mQsCustomizerController = qsCustomizerController;
        mUsingMediaPlayer = usingMediaPlayer;
        mMediaHost = mediaHost;
        mMetricsLogger = metricsLogger;
        mUiEventLogger = uiEventLogger;
        mQSLogger = qsLogger;
        mDumpManager = dumpManager;
        mShouldUseSplitNotificationShade =
                LargeScreenUtils.shouldUseSplitNotificationShade(getResources());
        mSystemSettings = systemSettings;
	mSettingsObserver = new ContentObserver(mainHandler) {
            @Override
            public void onChange(boolean selfChange, @Nullable Uri uri) {
                     if (uri == null) return;
		     final String key = uri.getLastPathSegment();
		     if (key == null) return;
		     handleSettingsChange(key);
	    }
	};
    }

    protected boolean handleSettingsChange(@NonNull String key) {
    	//to-do add required functionalities in future here
        return false;
    }

    @Override
    protected void onInit() {
        mView.initialize(mQSLogger);
        mQSLogger.logAllTilesChangeListening(mView.isListening(), mView.getDumpableTag(), "");
    }

    /**
     * @return the media host for this panel
     */
    public MediaHost getMediaHost() {
        return mMediaHost;
    }

    public void setSquishinessFraction(float squishinessFraction) {
        mView.setSquishinessFraction(squishinessFraction);
    }

    @Override
    protected void onViewAttached() {
        registerObserver(Settings.System.QS_TILE_LABEL_HIDE);
        registerObserver(Settings.System.QS_TILE_VERTICAL_LAYOUT);
        registerObserver(Settings.System.QS_TILE_LABEL_SIZE);
        registerObserver(Settings.System.QS_TILE_SECONDARY_LABEL_SIZE);
        mQsTileRevealController = createTileRevealController();
        if (mQsTileRevealController != null) {
            mQsTileRevealController.setExpansion(mRevealExpansion);
        }

        mMediaHost.addVisibilityChangeListener(mMediaHostVisibilityListener);
        mView.addOnConfigurationChangedListener(mOnConfigurationChangedListener);
        mHost.addCallback(mQSHostCallback);
        if (mView.getTileLayout() != null) {
           setTiles();
        }
        mLastOrientation = getResources().getConfiguration().orientation;
        mQSLogger.logOnViewAttached(mLastOrientation, mView.getDumpableTag());
        switchTileLayout(true);

        mDumpManager.registerDumpable(mView.getDumpableTag(), this);
    }

    protected void registerObserver(String key) {
        mSystemSettings.registerContentObserverForUser(
            key, mSettingsObserver, UserHandle.USER_ALL);
    }

    @Override
    protected void onViewDetached() {
        mQSLogger.logOnViewDetached(mLastOrientation, mView.getDumpableTag());
        mView.removeOnConfigurationChangedListener(mOnConfigurationChangedListener);
        mHost.removeCallback(mQSHostCallback);

        mView.getTileLayout().setListening(false, mUiEventLogger);

        mMediaHost.removeVisibilityChangeListener(mMediaHostVisibilityListener);

	if (areThereTiles()) {
            for (TileRecord record : mRecords) {
                record.tile.removeCallbacks();
            }
            mRecords.clear();
        }
        mDumpManager.unregisterDumpable(mView.getDumpableTag());
    }

    @Nullable
    protected QSTileRevealController createTileRevealController() {
        return null;
    }

    /** */
    public void setTiles() {
        if (mHost != null && mHost.getTiles() == null) return;
        setTiles(mHost.getTiles(), false);
    }

    /** */
    public void setTiles(Collection<QSTile> tiles, boolean collapsedView) {
        // TODO(b/168904199): move this logic into QSPanelController.
        if (!collapsedView && mQsTileRevealController != null) {
            mQsTileRevealController.updateRevealedTiles(tiles);
        }

        for (QSPanelControllerBase.TileRecord record : mRecords) {
            mView.removeTile(record);
            record.tile.removeCallback(record.callback);
        }
        mRecords.clear();
        mCachedSpecs = "";
        for (QSTile tile : tiles) {
            addTile(tile, collapsedView);
        }
    }

    /** */
    public void refreshAllTiles() {
    	if (!areThereTiles()) return;
        for (QSPanelControllerBase.TileRecord r : mRecords) {
            if (!r.tile.isListening()) {
                // Only refresh tiles that were not already in the listening state. Tiles that are
                // already listening is as if they are already expanded (for example, tiles that
                // are both in QQS and QS).
                r.tile.refreshState();
            }
        }
    }

    private void addTile(final QSTile tile, boolean collapsedView) {
        final TileRecord r =
                new TileRecord(tile, mHost.createTileView(getContext(), tile, collapsedView));
        // TODO(b/250618218): Remove the QSLogger in QSTileViewImpl once we know the root cause of
        // b/250618218.
        try {
            QSTileViewImpl qsTileView = (QSTileViewImpl) (r.tileView);
            if (qsTileView != null) {
                qsTileView.setQsLogger(mQSLogger);
            }
        } catch (ClassCastException e) {
            Log.e(TAG, "Failed to cast QSTileView to QSTileViewImpl", e);
        }
        mView.addTile(r);
        mRecords.add(r);
        mCachedSpecs = getTilesSpecs();
    }

    /** */
    public void clickTile(ComponentName tile) {
        final String spec = CustomTile.toSpec(tile);
        for (TileRecord record : mRecords) {
            if (record.tile.getTileSpec().equals(spec)) {
                record.tile.click(null /* view */);
                break;
            }
        }
    }

    boolean areThereTiles() {
        return !mRecords.isEmpty();
    }

    @Nullable
    QSTileView getTileView(QSTile tile) {
        for (QSPanelControllerBase.TileRecord r : mRecords) {
            if (r.tile == tile) {
                return r.tileView;
            }
        }
        return null;
    }

    QSTileView getTileView(String spec) {
        for (QSPanelControllerBase.TileRecord r : mRecords) {
            if (Objects.equals(r.tile.getTileSpec(), spec)) {
                return r.tileView;
            }
        }
        return null;
    }

    private String getTilesSpecs() {
        return mRecords.stream()
                .map(tileRecord ->  tileRecord.tile.getTileSpec())
                .collect(Collectors.joining(","));
    }

    /** */
    public void setExpanded(boolean expanded) {
        if (mView.isExpanded() == expanded) {
            return;
        }
        mQSLogger.logPanelExpanded(expanded, mView.getDumpableTag());

        mView.setExpanded(expanded);
        mMetricsLogger.visibility(MetricsEvent.QS_PANEL, expanded);
        if (!expanded) {
            mUiEventLogger.log(mView.closePanelEvent());
            closeDetail();
        } else {
            mUiEventLogger.log(mView.openPanelEvent());
            logTiles();
        }
    }

    /** */
    public void closeDetail() {
        if (mQsCustomizerController.isShown()) {
            mQsCustomizerController.hide();
            return;
        }
    }

    void setListening(boolean listening) {
        if (mView.isListening() == listening) return;
        mView.setListening(listening);

        if (mView.getTileLayout() != null) {
            mQSLogger.logAllTilesChangeListening(listening, mView.getDumpableTag(), mCachedSpecs);
            mView.getTileLayout().setListening(listening, mUiEventLogger);
        }

        if (mView.isListening()) {
            refreshAllTiles();
        }
    }

    private void switchTileLayoutIfNeeded() {
        switchTileLayout(/* force= */ false);
    }

    boolean switchTileLayout(boolean force) {
        /* Whether or not the panel currently contains a media player. */
        boolean horizontal = shouldUseHorizontalLayout();
        if (horizontal != mUsingHorizontalLayout || force) {
            mQSLogger.logSwitchTileLayout(horizontal, mUsingHorizontalLayout, force,
                    mView.getDumpableTag());
            mUsingHorizontalLayout = horizontal;
            mView.setUsingHorizontalLayout(mUsingHorizontalLayout, mMediaHost.getHostView(), force);
            updateMediaDisappearParameters();
            if (mUsingHorizontalLayoutChangedListener != null) {
                mUsingHorizontalLayoutChangedListener.run();
            }
            return true;
        }
        return false;
    }

    /**
     * Update the way the media disappears based on if we're using the horizontal layout
     */
    void updateMediaDisappearParameters() {
        if (!mUsingMediaPlayer) {
            return;
        }
        DisappearParameters parameters = mMediaHost.getDisappearParameters();
        if (mUsingHorizontalLayout) {
            // Only height remaining
            parameters.getDisappearSize().set(0.0f, 0.4f);
            // Disappearing on the right side on the top
            parameters.getGonePivot().set(1.0f, 0.0f);
            // translating a bit horizontal
            parameters.getContentTranslationFraction().set(0.25f, 1.0f);
            parameters.setDisappearEnd(0.6f);
        } else {
            // Only width remaining
            parameters.getDisappearSize().set(1.0f, 0.0f);
            // Disappearing on the top
            parameters.getGonePivot().set(0.0f, 0.0f);
            // translating a bit vertical
            parameters.getContentTranslationFraction().set(0.0f, 1f);
            parameters.setDisappearEnd(0.95f);
        }
        parameters.setFadeStartPosition(0.95f);
        parameters.setDisappearStart(0.0f);
        mMediaHost.setDisappearParameters(parameters);
    }

    boolean shouldUseHorizontalLayout() {
        if (mShouldUseSplitNotificationShade)  {
            return false;
        }
        return mUsingMediaPlayer && mMediaHost.getVisible()
                && mLastOrientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private void logTiles() {
        for (int i = 0; i < mRecords.size(); i++) {
            QSTile tile = mRecords.get(i).tile;
            mMetricsLogger.write(tile.populate(new LogMaker(tile.getMetricsCategory())
                    .setType(MetricsEvent.TYPE_OPEN)));
        }
    }

    /** Set the expansion on the associated {@link QSTileRevealController}. */
    public void setRevealExpansion(float expansion) {
        mRevealExpansion = expansion;
        if (mQsTileRevealController != null) {
            mQsTileRevealController.setExpansion(expansion);
        }
    }

    @Override
    public void dump(PrintWriter pw, String[] args) {
        pw.println(getClass().getSimpleName() + ":");
        pw.println("  Tile records:");
        for (QSPanelControllerBase.TileRecord record : mRecords) {
            if (record.tile instanceof Dumpable) {
                pw.print("    "); ((Dumpable) record.tile).dump(pw, args);
                pw.print("    "); pw.println(record.tileView.toString());
            }
        }
        if (mMediaHost != null) {
            pw.println("  media bounds: " + mMediaHost.getCurrentBounds());
            pw.println("  horizontal layout: " + mUsingHorizontalLayout);
            pw.println("  last orientation: " + mLastOrientation);
        }
        pw.println("  mShouldUseSplitNotificationShade: " + mShouldUseSplitNotificationShade);
    }

    public QSPanel.QSTileLayout getTileLayout() {
        return mView.getTileLayout();
    }

    /**
     * Add a listener for when the media visibility changes.
     */
    public void setMediaVisibilityChangedListener(@NonNull Consumer<Boolean> listener) {
        mMediaVisibilityChangedListener = listener;
    }

    /**
     * Add a listener when the horizontal layout changes
     */
    public void setUsingHorizontalLayoutChangeListener(Runnable listener) {
        mUsingHorizontalLayoutChangedListener = listener;
    }

    @Nullable
    public View getBrightnessView() {
        return mView.getBrightnessView();
    }

    /**
     * Set a listener to collapse/expand QS.
     * @param action
     */
    public void setCollapseExpandAction(Runnable action) {
        mView.setCollapseExpandAction(action);
    }

    /** Sets whether we are currently on lock screen. */
    public void setIsOnKeyguard(boolean isOnKeyguard) {
        boolean isOnSplitShadeLockscreen = mShouldUseSplitNotificationShade && isOnKeyguard;
        // When the split shade is expanding on lockscreen, the media container transitions from the
        // lockscreen to QS.
        // We have to prevent the media container position from moving during the transition to have
        // a smooth translation animation without stuttering.
        mView.setShouldMoveMediaOnExpansion(!isOnSplitShadeLockscreen);
    }

    /** */
    public static final class TileRecord {
        public TileRecord(QSTile tile, com.android.systemui.plugins.qs.QSTileView tileView) {
            this.tile = tile;
            this.tileView = tileView;
        }

        public QSTile tile;
        public com.android.systemui.plugins.qs.QSTileView tileView;
        public boolean scanState;
        @Nullable
        public QSTile.Callback callback;
    }
}
