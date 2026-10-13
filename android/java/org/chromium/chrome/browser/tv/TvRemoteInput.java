// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

package org.chromium.chrome.browser.tv;

/** Remote interaction policy; the target owns Android focus and Chromium input delivery. */
public final class TvRemoteInput {
    private static final long EDGE_HOLD_MS = 450;
    private static final long SELECT_HOLD_MS = 650;

    public enum Mode {
        CONTROLS,
        ELEMENTS,
        POINTER,
        SCROLL
    }

    public enum Key {
        UP,
        DOWN,
        LEFT,
        RIGHT,
        SELECT,
        OTHER
    }

    public interface Target {
        int getWidth();

        int getHeight();

        /** Visible top, inclusive physical pixels in the pointer's coordinate space. */
        int getTop();

        /** Visible bottom, inclusive physical pixels in the pointer's coordinate space. */
        int getBottom();

        /** Draw the pointer; edgeDirection is -1 (up), 0 (off), or 1 (down). */
        void showPointer(int x, int y, Mode mode, int edgeDirection);

        void click(int x, int y);

        void showControls();

        void scroll(int x, int y, int dx, int dy);

        void showNavigation();

        void navigate(Key direction);

        void selectElement();

        void toggleNavigation();
    }

    private final Target mTarget;
    private final int mStep;
    private Mode mMode = Mode.CONTROLS;
    private int mX;
    private int mY;
    private boolean mSelectPressed;
    private long mSelectSince = -1;
    private boolean mPositioned;
    private Key mHeldDirection = Key.OTHER;
    private long mEdgeSince = -1;
    private int mWidth;
    private int mHeight;
    private int mTop;
    private int mBottom;

    public TvRemoteInput(Target target, int step) {
        mTarget = target;
        mStep = step;
    }

    public boolean activate(Mode mode) {
        if (mTarget.getWidth() <= 0 || mTarget.getHeight() <= 0) return false;
        cancelPress();
        mMode = mode;
        if (!mPositioned) {
            mX = mTarget.getWidth() / 2;
            mY = mTarget.getHeight() / 2;
            mPositioned = true;
        }
        clampPosition();
        showPointer();
        return true;
    }

    public boolean back() {
        if (mMode == Mode.CONTROLS) return false;
        mMode = Mode.CONTROLS;
        cancelPress();
        mTarget.showControls();
        return true;
    }

    public void cancelPress() {
        mSelectPressed = false;
        mSelectSince = -1;
        mHeldDirection = Key.OTHER;
        mEdgeSince = -1;
    }

    public Mode getMode() {
        return mMode;
    }

    /** Called by the adapter's hold timer, or by a repeat/release on remotes without repeats. */
    public boolean onSelectHeld(long eventTime) {
        if (!mSelectPressed
                || mMode == Mode.SCROLL
                || mSelectSince < 0
                || eventTime - mSelectSince < SELECT_HOLD_MS) return false;
        cancelPress();
        mTarget.toggleNavigation();
        return true;
    }

    /** Share an external pointer position with subsequent physical input and viewport recovery. */
    public boolean positionPointer(int x, int y) {
        if (mTarget.getWidth() <= 0 || mTarget.getHeight() <= 0) return false;
        cancelPress();
        mMode = Mode.POINTER;
        mX = x;
        mY = y;
        mPositioned = true;
        clampPosition();
        showPointer();
        return true;
    }

    /**
     * Resume page input after native UI, canceling held OK and clamping to the current viewport.
     *
     * @return True on success; false, with state unchanged, in controls mode or with no viewport.
     */
    public boolean resume() {
        return mMode != Mode.CONTROLS && activate(mMode);
    }

    /**
     * Handle a remote key with Android's repeat count and monotonic event time in milliseconds.
     * Directional repeats require a fresh down (repeat zero) after any interruption.
     */
    public boolean onKey(Key key, boolean down, int repeat, boolean canceled, long eventTime) {
        if (mMode == Mode.CONTROLS) return false;
        if (key == Key.OTHER) {
            if (canceled) cancelPress();
            return false;
        }
        if (mTarget.getWidth() <= 0 || mTarget.getHeight() <= 0) {
            back();
            return false;
        }
        if (clampPosition()) showPointer();
        if (canceled) {
            cancelPress();
            showPointer();
            return true;
        }
        if (key == Key.SELECT) {
            mHeldDirection = Key.OTHER;
            mEdgeSince = -1;
            showPointer();
            if (down) {
                if (repeat == 0) {
                    mSelectPressed = true;
                    mSelectSince = eventTime;
                } else {
                    onSelectHeld(eventTime);
                }
            } else if (mSelectPressed) {
                if (onSelectHeld(eventTime)) return true;
                mSelectPressed = false;
                mSelectSince = -1;
                if (mMode == Mode.SCROLL) {
                    activate(Mode.POINTER);
                } else if (mMode == Mode.ELEMENTS) {
                    mTarget.selectElement();
                } else {
                    mTarget.click(mX, mY);
                }
            }
            return true;
        }
        if (!down) {
            if (key == mHeldDirection) {
                mHeldDirection = Key.OTHER;
                mEdgeSince = -1;
                showPointer();
            }
            return true;
        }
        int dx = 0;
        int dy = 0;
        switch (key) {
            case UP:
                dy = -1;
                break;
            case DOWN:
                dy = 1;
                break;
            case LEFT:
                dx = -1;
                break;
            case RIGHT:
                dx = 1;
                break;
            default:
                break;
        }
        if (mMode == Mode.SCROLL) {
            mTarget.scroll(mX, mY, dx, dy);
            return true;
        }
        if (repeat == 0) {
            mHeldDirection = key;
            mEdgeSince = -1;
        } else if (key != mHeldDirection) {
            return true;
        }
        if (mSelectPressed) return true;
        if (mMode == Mode.ELEMENTS) {
            mTarget.navigate(key);
            return true;
        }
        boolean atEdge = (dy < 0 && mY == mTop) || (dy > 0 && mY == mBottom);
        if (atEdge) {
            if (mEdgeSince < 0) mEdgeSince = eventTime;
            if (eventTime - mEdgeSince >= EDGE_HOLD_MS) {
                mTarget.scroll(mX, mY, 0, dy);
            }
        }
        mX += dx * mStep;
        mY += dy * mStep;
        clampPosition();
        // Start the grace period on arrival, never from time spent crossing the page.
        if (mEdgeSince < 0 && ((dy < 0 && mY == mTop) || (dy > 0 && mY == mBottom))) {
            mEdgeSince = eventTime;
        }
        showPointer();
        return true;
    }

    private boolean clampPosition() {
        int width = mTarget.getWidth();
        int height = mTarget.getHeight();
        int top = Math.max(0, Math.min(mTarget.getTop(), height - 1));
        int bottom = Math.max(top, Math.min(mTarget.getBottom(), height - 1));
        boolean changed = width != mWidth || height != mHeight || top != mTop || bottom != mBottom;
        if (changed) {
            mHeldDirection = Key.OTHER;
            mEdgeSince = -1;
            mWidth = width;
            mHeight = height;
            mTop = top;
            mBottom = bottom;
        }
        mX = Math.max(0, Math.min(mX, width - 1));
        mY = Math.max(top, Math.min(mY, bottom));
        return changed;
    }

    private void showPointer() {
        if (mMode == Mode.ELEMENTS) {
            mTarget.showNavigation();
            return;
        }
        int edgeDirection = mEdgeSince < 0 ? 0 : mHeldDirection == Key.UP ? -1 : 1;
        mTarget.showPointer(mX, mY, mMode, edgeDirection);
    }
}
