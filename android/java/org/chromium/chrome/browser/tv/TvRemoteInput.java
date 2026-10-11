// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

package org.chromium.chrome.browser.tv;

/** Remote interaction policy; the target owns Android focus and Chromium input delivery. */
public final class TvRemoteInput {
    public enum Mode {
        CONTROLS,
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

        void showPointer(int x, int y, Mode mode);

        void click(int x, int y);

        void showControls();

        void scroll(int x, int y, int dx, int dy);
    }

    private final Target mTarget;
    private final int mStep;
    private Mode mMode = Mode.CONTROLS;
    private int mX;
    private int mY;
    private boolean mSelectPressed;
    private boolean mPositioned;

    public TvRemoteInput(Target target, int step) {
        mTarget = target;
        mStep = step;
    }

    public boolean activate(Mode mode) {
        if (mTarget.getWidth() <= 0 || mTarget.getHeight() <= 0) return false;
        mSelectPressed = false;
        mMode = mode;
        if (!mPositioned) {
            mX = mTarget.getWidth() / 2;
            mY = mTarget.getHeight() / 2;
            mPositioned = true;
        }
        clampPosition();
        mTarget.showPointer(mX, mY, mMode);
        return true;
    }

    public boolean back() {
        if (mMode == Mode.CONTROLS) return false;
        mMode = Mode.CONTROLS;
        mSelectPressed = false;
        mTarget.showControls();
        return true;
    }

    public void cancelPress() {
        mSelectPressed = false;
    }

    /**
     * Resume page input after native UI, canceling held OK and clamping to the current viewport.
     *
     * @return True on success; false, with state unchanged, in controls mode or with no viewport.
     */
    public boolean resume() {
        return mMode != Mode.CONTROLS && activate(mMode);
    }

    public boolean onKey(Key key, boolean down, int repeat, boolean canceled) {
        if (mMode == Mode.CONTROLS || key == Key.OTHER) return false;
        if (mTarget.getWidth() <= 0 || mTarget.getHeight() <= 0) {
            back();
            return false;
        }
        clampPosition();
        if (key == Key.SELECT) {
            if (canceled) {
                mSelectPressed = false;
            } else if (down) {
                if (repeat == 0) mSelectPressed = true;
            } else if (mSelectPressed) {
                mSelectPressed = false;
                if (mMode == Mode.SCROLL) {
                    activate(Mode.POINTER);
                } else {
                    mTarget.click(mX, mY);
                }
            }
            return true;
        }
        if (down) {
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
            mX += dx * mStep;
            mY += dy * mStep;
            clampPosition();
            mTarget.showPointer(mX, mY, mMode);
        }
        return true;
    }

    private void clampPosition() {
        mX = Math.max(0, Math.min(mX, mTarget.getWidth() - 1));
        mY = Math.max(0, Math.min(mY, mTarget.getHeight() - 1));
    }
}
