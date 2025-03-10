/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.keyguard;

import static com.android.systemui.statusbar.StatusBarState.KEYGUARD;

import android.view.View;
import android.view.ViewPropertyAnimator;

import com.android.systemui.animation.Interpolators;
import com.android.systemui.plugins.log.LogBuffer;
import com.android.systemui.plugins.log.LogLevel;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.notification.AnimatableProperty;
import com.android.systemui.statusbar.notification.PropertyAnimator;
import com.android.systemui.statusbar.notification.stack.AnimationProperties;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.phone.ScreenOffAnimationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import com.google.errorprone.annotations.CompileTimeConstant;

import com.android.systemui.superior.AmbientText;
import com.android.systemui.superior.AmbientCustomImage;

import com.android.systemui.R;


/**
 * Helper class for updating visibility of keyguard views based on keyguard and status bar state.
 * This logic is shared by both the keyguard status view and the keyguard user switcher.
 */
public class KeyguardVisibilityHelper {
    private static final String TAG = "KeyguardVisibilityHelper";

    private View mView;
    private final KeyguardStateController mKeyguardStateController;
    private final DozeParameters mDozeParameters;
    private final ScreenOffAnimationController mScreenOffAnimationController;
    private boolean mAnimateYPos;
    private boolean mKeyguardViewVisibilityAnimating;
    private boolean mLastOccludedState = false;
    private final AnimationProperties mAnimationProperties = new AnimationProperties();
    private final LogBuffer mLogBuffer;
    // Ambient Customization
    private AmbientText mAmbientText;
    private AmbientCustomImage mAmbientCustomImage;

    public KeyguardVisibilityHelper(View view,
            KeyguardStateController keyguardStateController,
            DozeParameters dozeParameters,
            ScreenOffAnimationController screenOffAnimationController,
            boolean animateYPos,
            LogBuffer logBuffer) {
        mView = view;
        mKeyguardStateController = keyguardStateController;
        mDozeParameters = dozeParameters;
        mScreenOffAnimationController = screenOffAnimationController;
        mAnimateYPos = animateYPos;
        mAmbientText = (AmbientText) mView.findViewById(R.id.text_container);
        mAmbientCustomImage = (AmbientCustomImage) mView.findViewById(R.id.image_container);
        mLogBuffer = logBuffer;
    }

    private void log(@CompileTimeConstant String message) {
        if (mLogBuffer != null) {
            mLogBuffer.log(TAG, LogLevel.DEBUG, message);
        }
    }

    public boolean isVisibilityAnimating() {
        return mKeyguardViewVisibilityAnimating;
    }

    /**
     * Set the visibility of a keyguard view based on some new state.
     */
    public void setViewVisibility(
            int statusBarState,
            boolean keyguardFadingAway,
            boolean goingToFullShade,
            int oldStatusBarState) {
        mView.animate().cancel();
        boolean isOccluded = mKeyguardStateController.isOccluded();
        mKeyguardViewVisibilityAnimating = false;

        if ((!keyguardFadingAway && oldStatusBarState == KEYGUARD
                && statusBarState != KEYGUARD) || goingToFullShade) {
            mKeyguardViewVisibilityAnimating = true;
            mView.animate()
                    .alpha(0f)
                    .setStartDelay(0)
                    .setDuration(160)
                    .setInterpolator(Interpolators.ALPHA_OUT)
                    .withEndAction(
                            mAnimateKeyguardStatusViewGoneEndRunnable);
            if (mAmbientCustomImage != null) {
                mAmbientCustomImage.animate()
                    .alpha(0f)
                    .setStartDelay(0)
                    .setDuration(160);
            }
            if (mAmbientText != null) {
                mAmbientText.animate()
                    .alpha(0f)
                    .setStartDelay(0)
                    .setDuration(160);
            }
            if (keyguardFadingAway) {
                mView.animate()
                        .setStartDelay(mKeyguardStateController.getKeyguardFadingAwayDelay())
                        .setDuration(mKeyguardStateController.getShortenedFadingAwayDuration())
                        .start();
                if (mAmbientCustomImage != null) {
                    mAmbientCustomImage.animate()
                        .setStartDelay(mKeyguardStateController.getKeyguardFadingAwayDelay())
                        .setDuration(mKeyguardStateController.getShortenedFadingAwayDuration())
                        .start();
                }
                if (mAmbientText != null) {
                    mAmbientText.animate()
                        .setStartDelay(mKeyguardStateController.getKeyguardFadingAwayDelay())
                        .setDuration(mKeyguardStateController.getShortenedFadingAwayDuration())
                        .start();
                }
            }
        } else if (oldStatusBarState == StatusBarState.SHADE_LOCKED && statusBarState == KEYGUARD) {
            mView.setVisibility(View.VISIBLE);
            mKeyguardViewVisibilityAnimating = true;
            mView.setAlpha(0f);
            mView.animate()
                    .alpha(1f)
                    .setStartDelay(0)
                    .setDuration(320)
                    .setInterpolator(Interpolators.ALPHA_IN)
                    .withEndAction(mAnimateKeyguardStatusViewVisibleEndRunnable);
            if (mAmbientCustomImage != null) {
                mAmbientCustomImage.setAlpha(0f);
                mAmbientCustomImage.animate()
                    .alpha(1f)
                    .setStartDelay(0)
                    .setDuration(320);
            }
            if (mAmbientText != null) {
                mAmbientText.setAlpha(0f);
                mAmbientText.animate()
                    .alpha(1f)
                    .setStartDelay(0)
                    .setDuration(320);
            }
        } else if (statusBarState == KEYGUARD) {
            if (keyguardFadingAway) {
                mKeyguardViewVisibilityAnimating = true;
                ViewPropertyAnimator animator = mView.animate()
                        .alpha(0)
                        .setInterpolator(Interpolators.FAST_OUT_LINEAR_IN)
                        .withEndAction(mAnimateKeyguardStatusViewInvisibleEndRunnable);
                if (mAnimateYPos) {
                    float target = mView.getY() - mView.getHeight() * 0.05f;
                    int delay = 0;
                    int duration = 125;
                    // We animate the Y property separately using the PropertyAnimator, as the panel
                    // view also needs to update the end position.
                    mAnimationProperties.setDuration(duration).setDelay(delay);
                    PropertyAnimator.cancelAnimation(mView, AnimatableProperty.Y);
                    PropertyAnimator.setProperty(mView, AnimatableProperty.Y, target,
                            mAnimationProperties,
                            true /* animate */);
                    animator.setDuration(duration)
                            .setStartDelay(delay);
                    log("keyguardFadingAway transition w/ Y Aniamtion");
                } else {
                    log("keyguardFadingAway transition w/o Y Animation");
                }
                animator.start();
                if (mAmbientCustomImage != null) {
                    mAmbientCustomImage.animate().alpha(0).setDuration(125)
                        .setStartDelay(0).start();
                }
                if (mAmbientText != null) {
                    mAmbientText.animate().alpha(0).setDuration(125)
                        .setStartDelay(0).start();
                }
            } else if (mScreenOffAnimationController.shouldAnimateInKeyguard()) {
                log("ScreenOff transition");
                mKeyguardViewVisibilityAnimating = true;

                // Ask the screen off animation controller to animate the keyguard visibility for us
                // since it may need to be cancelled due to keyguard lifecycle events.
                mScreenOffAnimationController.animateInKeyguard(
                        mView, mAnimateKeyguardStatusViewVisibleEndRunnable);
            } else {
                log("Direct set Visibility to VISIBLE");
                mView.setVisibility(View.VISIBLE);
                    if (mAmbientCustomImage != null) {
                        mAmbientCustomImage.setAlpha(1f);
                    }
                    if (mAmbientText != null) {
                        mAmbientText.setAlpha(1f);
                 }
            }
        } else {
            log("Direct set Visibility to GONE");
            mView.setVisibility(View.GONE);
            mView.setAlpha(1f);
            if (mAmbientCustomImage != null) {
                mAmbientCustomImage.setAlpha(1f);
            }
            if (mAmbientText != null) {
                mAmbientText.setAlpha(1f);
            }
        }

        mLastOccludedState = isOccluded;
    }

    private final Runnable mAnimateKeyguardStatusViewInvisibleEndRunnable = () -> {
        mKeyguardViewVisibilityAnimating = false;
        mView.setVisibility(View.INVISIBLE);
        log("Callback Set Visibility to INVISIBLE");
    };

    private final Runnable mAnimateKeyguardStatusViewGoneEndRunnable = () -> {
        mKeyguardViewVisibilityAnimating = false;
        mView.setVisibility(View.GONE);
        log("CallbackSet Visibility to GONE");
    };

    private final Runnable mAnimateKeyguardStatusViewVisibleEndRunnable = () -> {
        mKeyguardViewVisibilityAnimating = false;
        mView.setVisibility(View.VISIBLE);
        log("Callback Set Visibility to VISIBLE");
    };
}
