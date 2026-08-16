/*
 * Copyright (C) 2013 The Android Open Source Project
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

package org.futo.inputmethod.latin.inputlogic;

import android.os.SystemClock;

/**
 * Decides whether a new input (tap or swipe) starts a fresh word, extends the current
 * word-window, or commits a pending window word before starting a new one.
 *
 * <p>The arbiter is a small, dependency-light single-thread state holder (consumed on the IME
 * UI thread only). It is fed the wall-clock "now" of the new input and the previous input's
 * end time, plus the user-configured combine gaps in ms (the swipe gap and the tap gap; swipe
 * gap {@code <= 0} = feature off ⇒ stock behavior). Decision rules (in order):
 * <ul>
 *   <li>{@code swipeGap <= 0}: always {@link Decision#START_FRESH} and no state is mutated — the
 *       consumer keeps stock behavior and the arbiter stays inert.</li>
 *   <li>No open window: {@link Decision#START_FRESH} (and a new window is opened with this
 *       input as its first unit).</li>
 *   <li>Pure-tap window with {@code tapGap <= 0} ("Enable auto-space when tapping" off):
 *       always {@link Decision#EXTEND} — slow tapping never commits.</li>
 *   <li>Open window and the new input started within the applicable gap (swipe gap once a swipe
 *       is in the window, otherwise the tap gap) of the previous input's end:
 *       {@link Decision#EXTEND}.</li>
 *   <li>Open window and the new input started {@code >= gap} after the previous input's end:
 *       {@link Decision#COMMIT_THEN_START}. The pending word is not committed by silence: it
 *       stays composing until the next input first commits it with one space, then begins a
 *       new window.</li>
 * </ul>
 *
 * <p>A "window" is the run of consecutive inputs that are part of one word. Whether the window
 * is decoded the stock way (letters in {@link org.futo.inputmethod.latin.WordComposer}) or as a
 * single swipe-unit is gated by {@link #windowHasSwipe()}: as soon as any swipe is in the
 * window, the consumer must feed the whole accumulated pointer path (taps as micro-swipes +
 * swipes) to the swipe decoder; until then, pure-tap windows append letters the stock way.
 *
 * <p><b>Two gaps.</b> The consumer supplies both a swipe gap (used once the window contains a
 * completed swipe) and a tap gap (used while the window is still pure-tap). A tap gap of
 * {@code <= 0} keeps the original behavior: pure-tap windows never arm the timer, so typing
 * slowly ("h a l") never commits mid-word and the first swipe always extends a slow tap prefix.
 * With a positive tap gap ("Enable auto-space when tapping"), the timer arms from the first input
 * on, so a pure-tap word also auto-commits once the (doubled) tap gap passes.
 *
 * <p><b>Arming model.</b> {@link #windowHasSwipe()} flips to true only when a swipe actually
 * completes ({@link #recordInputEnd(long, boolean)} with {@code isSwipe == true}), never at swipe
 * start. The timer reference is armed by every completed input that counts toward the window; a
 * cancelled swipe (whose {@code recordInputEnd} is never called) therefore leaves no stale timer.
 * Which gap applies to the next decision is read from {@link #windowHasSwipe()}: the swipe gap
 * once any swipe has completed, the tap gap while the window is still pure-tap.
 *
 * <p>Consumer contract for {@link Decision#COMMIT_THEN_START}: the pending window's state
 * (including {@link #windowHasSwipe()}) stays observable so the consumer can pick the right
 * commit path (swipe-window-decoded vs normal typed-word auto-correction). After committing,
 * the consumer MUST call {@link #restartWindowForNewInput()} to close the pending window and
 * open a fresh one with the new input as its first unit, then call
 * {@link #recordInputEnd(long, boolean)} once the new input has completed.
 */
final class WordWindowArbiter {
    enum Decision { START_FRESH, EXTEND, COMMIT_THEN_START }

    /** Sentinel for "no previous input yet". */
    private static final long NO_PREVIOUS = -1L;

    private long mLastInputEndTime = NO_PREVIOUS;
    private boolean mWindowOpen = false;
    private boolean mWindowHasSwipe = false;

    private final LongSupplier mClock;

    /** A clock supplier so tests can drive the gap deterministically without Thread.sleep. */
    @FunctionalInterface
    interface LongSupplier {
        long now();
    }

    WordWindowArbiter() {
        this(() -> SystemClock.uptimeMillis());
    }

    WordWindowArbiter(final LongSupplier clock) {
        mClock = clock;
    }

    /**
     * Decide what the new input should do, measuring the gap against the arbiter's own clock.
     * Callers that already have a timestamp (e.g. a swipe's first down-event time) should use
     * {@link #onStartInput(int, int, long, boolean)} instead.
     *
     * @param swipeGap combine gap in ms once the window contains a swipe; {@code <= 0} ⇒ always
     *     {@link Decision#START_FRESH} (feature off).
     * @param tapGap combine gap in ms while the window is pure-tap; {@code <= 0} ⇒ pure-tap
     *     windows always {@link Decision#EXTEND} (auto-space while tapping off).
     * @param isSwipe true if the new input is a swipe; false if a tap.
     * @return the decision; never null.
     */
    Decision onStartInput(final int swipeGap, final int tapGap, final boolean isSwipe) {
        return onStartInput(swipeGap, tapGap, mClock.now(), isSwipe);
    }

    /**
     * Variant with an explicit "now" for callers that already have a timestamp they want to use
     * (e.g. the swipe's first down-event time, {@code BatchInputArbiter.sGestureFirstDownTime}).
     */
    Decision onStartInput(final int swipeGap, final int tapGap, final long now, final boolean isSwipe) {
        if (swipeGap <= 0) {
            // Feature off: every input starts fresh. Do NOT mutate state so the arbiter stays
            // inert and stock behavior is byte-identical to the no-feature build.
            return Decision.START_FRESH;
        }
        if (!mWindowOpen) {
            // First input of a new window — open it with this input as its first unit.
            mWindowOpen = true;
            return Decision.START_FRESH;
        }
        if (!mWindowHasSwipe && tapGap <= 0) {
            // Pure-tap window with tap auto-commit off: always extend — slow typing never
            // commits, and the first swipe extends a slow tap prefix no matter how late it
            // starts.
            return Decision.EXTEND;
        }
        // The window has a completed swipe (swipe-gap rules) or a positive tap gap (tap-gap
        // rules); in both cases the timer reference is armed from the previous input's end.
        // Nothing to measure against only before the window's first input has completed.
        if (mLastInputEndTime == NO_PREVIOUS) {
            return Decision.EXTEND;
        }
        final int gap = mWindowHasSwipe ? swipeGap : tapGap;
        if ((now - mLastInputEndTime) < gap) {
            // Within the gap ⇒ this input extends the still-open window.
            return Decision.EXTEND;
        }
        // Gap exceeded ⇒ the pending window commits lazily on this, the next input. Do NOT
        // mutate here: the consumer must observe the pending window's windowHasSwipe() to
        // pick the correct commit path. After committing it calls
        // {@link #restartWindowForNewInput()} to open the new window.
        return Decision.COMMIT_THEN_START;
    }

    /**
     * Called by the consumer after a {@link Decision#COMMIT_THEN_START} has been handled: the
     * pending word was committed, and now the new input (whose start triggered the commit)
     * becomes the first unit of a fresh window. Equivalent to {@link #onWindowReset()} followed
     * by re-opening, in one call.
     */
    void restartWindowForNewInput() {
        mWindowOpen = true;
        mWindowHasSwipe = false;
        // Reset the timer reference: a fresh window starts unarmed. The consumer's
        // recordInputEnd() re-arms it once the new first input completes.
        mLastInputEndTime = NO_PREVIOUS;
    }

    /**
     * Close the window unconditionally. Called at every normal commit site (space/separator/
     * external commit) — the user pressing space/separator always commits normally, so the next
     * input must start a fresh window. Also called when the input method is reset.
     */
    void onWindowReset() {
        mWindowOpen = false;
        mWindowHasSwipe = false;
        mLastInputEndTime = NO_PREVIOUS;
    }

    /**
     * Record the end time of the just-completed input so the next {@link #onStartInput} can
     * measure against the gap. For:
     * <ul>
     *   <li>a tap ({@code isSwipe == false}): the {@code now} of {@code onCodeInput} (visually a
     *       tap ends when it starts);</li>
     *   <li>a swipe ({@code isSwipe == true}): the up-event time of {@code onEndBatchInput}.</li>
     * </ul>
     * The consumer MUST call this once per input that should be counted toward the window.
     *
     * <p>A completed swipe is what turns the window into a has-swipe window; every completed
     * input — tap or swipe — then arms the timer reference from its end time. In a pure-tap
     * window that reference only drives a decision when the tap gap is positive
     * ({@link #onStartInput} returns early for {@code tapGap <= 0}); with the tap gap off, slow
     * tapping never commits and the first swipe always extends a slow tap prefix. A cancelled
     * swipe never calls this method, so it leaves no stale timer.
     */
    void recordInputEnd(final long now, final boolean isSwipe) {
        if (isSwipe) {
            mWindowHasSwipe = true;
        }
        mLastInputEndTime = now;
    }

    boolean isWindowOpen() {
        return mWindowOpen;
    }

    /**
     * Whether the current window contains any swipe. Consumers gate "decode whole path as one
     * swipe-unit" on this: {@code false} ⇒ pure-tap window ⇒ stock letter-append via
     * {@link org.futo.inputmethod.latin.WordComposer}; {@code true} ⇒ taps within this window
     * must be synthesized as micro-swipe segments and the whole accumulated path re-decoded
     * together as one word.
     */
    boolean windowHasSwipe() {
        return mWindowHasSwipe;
    }
}