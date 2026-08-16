/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Pure-logic tests for {@link WordWindowArbiter}. Uses a controllable clock so the gap can be
 * driven deterministically instead of via {@code Thread.sleep}. The arbiter has no Android
 * dependencies; the AndroidJUnit4 runner is used only to follow the repo convention (all tests
 * live in the instrumented source set).
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class WordWindowArbiterTests {

    private static final class FakeClock implements WordWindowArbiter.LongSupplier {
        long t;
        FakeClock(final long start) { t = start; }
        @Override public long now() { return t; }
    }

    private static WordWindowArbiter newArbiter(final long t0) {
        return new WordWindowArbiter(new FakeClock(t0));
    }

    @Test
    public void testGapZeroAlwaysStartFreshAndInert() {
        final FakeClock clock = new FakeClock(1000L);
        final WordWindowArbiter a = new WordWindowArbiter(clock);
        // First tap.
        assertEquals(WordWindowArbiter.Decision.START_FRESH, a.onStartInput(0, 0, false));
        assertFalse(a.isWindowOpen());
        assertFalse(a.windowHasSwipe());
        // Even after recording an end time, the next input is still START_FRESH and the window
        // never opens: gap == 0 ⇒ arbiter must stay inert (stock behavior).
        a.recordInputEnd(1000L, false);
        clock.t = 1050L; // well within any positive gap
        assertEquals(WordWindowArbiter.Decision.START_FRESH, a.onStartInput(0, 0, true));
        assertFalse(a.isWindowOpen());
    }

    @Test
    public void testNegativeGapTreatedAsOff() {
        final WordWindowArbiter a = newArbiter(1000L);
        assertEquals(WordWindowArbiter.Decision.START_FRESH, a.onStartInput(-1, 0, false));
        assertFalse(a.isWindowOpen());
    }

    @Test
    public void testFirstInputOpensWindowStartFresh() {
        final WordWindowArbiter a = newArbiter(1000L);
        assertFalse(a.isWindowOpen());
        assertEquals(WordWindowArbiter.Decision.START_FRESH, a.onStartInput(200, 0, false));
        assertTrue(a.isWindowOpen());
        assertFalse(a.windowHasSwipe());                 // first input was a tap
        a.recordInputEnd(1000L, false);
        // First swipe when no window open: opens with START_FRESH, and becomes a has-swipe
        // window only when the swipe completes (recordInputEnd with isSwipe=true).
        final WordWindowArbiter b = newArbiter(2000L);
        assertEquals(WordWindowArbiter.Decision.START_FRESH, b.onStartInput(200, 0, true));
        assertTrue(b.isWindowOpen());
        assertFalse(b.windowHasSwipe());                 // in-flight swipe: not armed yet
        b.recordInputEnd(2000L, true);
        assertTrue(b.windowHasSwipe());
    }

    @Test
    public void testExtendWithinGap() {
        final FakeClock clock = new FakeClock(1000L);
        final WordWindowArbiter a = new WordWindowArbiter(clock);
        a.onStartInput(200, 0, false);                      // tap, opens window
        a.recordInputEnd(1000L, false);
        clock.t = 1100L;                                 // 100 < 200 ⇒ EXTEND
        assertEquals(WordWindowArbiter.Decision.EXTEND, a.onStartInput(200, 0, false));
        assertTrue(a.isWindowOpen());
        assertFalse(a.windowHasSwipe());
        a.recordInputEnd(1100L, false);
        clock.t = 1190L;                                 // 90 < 200 ⇒ EXTEND with a swipe
        assertEquals(WordWindowArbiter.Decision.EXTEND, a.onStartInput(200, 0, true));
        // The swipe EXTENDs but is still in flight: hasSwipe only flips on completion.
        assertFalse(a.windowHasSwipe());
        a.recordInputEnd(1190L, true);                   // swipe completes → has-swipe, timer armed
        assertTrue(a.windowHasSwipe());
        // A subsequent tap still extends: windowHasSwipe remains true (latch).
        clock.t = 1250L;
        assertEquals(WordWindowArbiter.Decision.EXTEND, a.onStartInput(200, 0, false));
        assertTrue(a.windowHasSwipe());
    }

    @Test
    public void testSlowTapsNeverCommit() {
        final FakeClock clock = new FakeClock(1000L);
        final WordWindowArbiter a = new WordWindowArbiter(clock);
        // 'h'
        assertEquals(WordWindowArbiter.Decision.START_FRESH, a.onStartInput(200, 0, false));
        a.recordInputEnd(1000L, false);
        // 'a' typed very slowly (way past gap): pure-tap windows never arm the timer, so this
        // must EXTEND, never COMMIT_THEN_START.
        clock.t = 5000L;
        assertEquals(WordWindowArbiter.Decision.EXTEND, a.onStartInput(200, 0, false));
        assertFalse(a.windowHasSwipe());
        a.recordInputEnd(5000L, false);
        // 'l' typed even slower: still EXTEND, still no timer.
        clock.t = 10000L;
        assertEquals(WordWindowArbiter.Decision.EXTEND, a.onStartInput(200, 0, false));
        assertFalse(a.windowHasSwipe());
    }

    @Test
    public void testFirstSwipeExtendsSlowTapPrefix() {
        final FakeClock clock = new FakeClock(1000L);
        final WordWindowArbiter a = new WordWindowArbiter(clock);
        // Slowly tap "h a l" (each tap way past the gap — the timer never arms for taps).
        assertEquals(WordWindowArbiter.Decision.START_FRESH, a.onStartInput(200, 0, false));
        a.recordInputEnd(1000L, false);
        clock.t = 5000L;
        assertEquals(WordWindowArbiter.Decision.EXTEND, a.onStartInput(200, 0, false));
        a.recordInputEnd(5000L, false);
        clock.t = 9000L;
        assertEquals(WordWindowArbiter.Decision.EXTEND, a.onStartInput(200, 0, false));
        a.recordInputEnd(9000L, false);
        // The swipe "lo" starts long after the last tap but the window is still pure-tap: the
        // first swipe MUST extend (combining to "hallo"), never commit the tap prefix. It is
        // still in flight, so hasSwipe has not flipped yet.
        clock.t = 13000L;
        assertEquals(WordWindowArbiter.Decision.EXTEND, a.onStartInput(200, 0, true));
        assertFalse(a.windowHasSwipe());
        // The swipe completes: now the window is has-swipe and the timer is armed from its end.
        a.recordInputEnd(13000L, true);
        assertTrue(a.windowHasSwipe());
        // A next input within the gap extends further...
        clock.t = 13100L;
        assertEquals(WordWindowArbiter.Decision.EXTEND, a.onStartInput(200, 0, false));
        // ...and one past the gap lazily commits the whole "hallo" window.
        clock.t = 14000L;
        assertEquals(WordWindowArbiter.Decision.COMMIT_THEN_START, a.onStartInput(200, 0, false));
    }

    @Test
    public void testCancelledFirstSwipeStaysPureTap() {
        final FakeClock clock = new FakeClock(1000L);
        final WordWindowArbiter a = new WordWindowArbiter(clock);
        // Build a pure-tap prefix "h" (timer never armed).
        assertEquals(WordWindowArbiter.Decision.START_FRESH, a.onStartInput(200, 0, false));
        a.recordInputEnd(1000L, false);
        // A swipe starts (the window's first swipe)...
        clock.t = 10000L;
        assertEquals(WordWindowArbiter.Decision.EXTEND, a.onStartInput(200, 0, true));
        // ...and is cancelled before completing: recordInputEnd(true) is never called, so the
        // window stays pure-tap — has-swipe never flipped and the timer stays unarmed. No revert
        // API needed.
        assertFalse(a.windowHasSwipe());
        // A subsequent tap, even very late, still extends (no timer armed).
        clock.t = 20000L;
        assertEquals(WordWindowArbiter.Decision.EXTEND, a.onStartInput(200, 0, false));
        assertFalse(a.windowHasSwipe());
    }

    @Test
    public void testCancelledSecondSwipeKeepsFirstSwipe() {
        final FakeClock clock = new FakeClock(1000L);
        final WordWindowArbiter a = new WordWindowArbiter(clock);
        // A completed first swipe arms the window.
        assertEquals(WordWindowArbiter.Decision.START_FRESH, a.onStartInput(200, 0, true));
        a.recordInputEnd(1000L, true);
        // A second swipe starts within the gap (extends, still in flight)...
        clock.t = 1100L;
        assertEquals(WordWindowArbiter.Decision.EXTEND, a.onStartInput(200, 0, true));
        assertTrue(a.windowHasSwipe());                  // first swipe already completed
        // ...and is cancelled: the first completed swipe remains part of the window (no revert).
        assertTrue(a.windowHasSwipe());
        // The timer reference from the first swipe is retained.
        clock.t = 1500L;                                 // 500 >= 200 past the first swipe's end
        assertEquals(WordWindowArbiter.Decision.COMMIT_THEN_START, a.onStartInput(200, 0, false));
    }

    @Test
    public void testCommitThenStartWhenGapExceeded() {
        final FakeClock clock = new FakeClock(1000L);
        final WordWindowArbiter a = new WordWindowArbiter(clock);
        // Build a pending swipe-window: swipe then tap within gap.
        assertEquals(WordWindowArbiter.Decision.START_FRESH, a.onStartInput(200, 0, true));
        a.recordInputEnd(1000L, true);
        clock.t = 1150L;
        assertEquals(WordWindowArbiter.Decision.EXTEND, a.onStartInput(200, 0, false));
        assertTrue(a.windowHasSwipe());
        a.recordInputEnd(1150L, false);
        // Next input far beyond the gap: pending window must commit lazily.
        clock.t = 5000L;                                 // 3850 >= 200
        assertEquals(WordWindowArbiter.Decision.COMMIT_THEN_START,
                a.onStartInput(200, 0, false));
        // State NOT mutated by COMMIT_THEN_START: consumer can still observe that the pending
        // window was a swipe-window so it picks the right commit path.
        assertTrue(a.isWindowOpen());
        assertTrue(a.windowHasSwipe());
        // Consumer commits, then reopens a fresh window with this input as the first unit.
        a.restartWindowForNewInput();
        assertTrue(a.isWindowOpen());
        assertFalse(a.windowHasSwipe());                 // new window is pure-tap
        a.recordInputEnd(5000L, false);
    }

    @Test
    public void testOnWindowResetClosesEverything() {
        final FakeClock clock = new FakeClock(1000L);
        final WordWindowArbiter a = new WordWindowArbiter(clock);
        a.onStartInput(200, 0, true);                        // opens swipe window
        a.recordInputEnd(1000L, true);
        assertTrue(a.isWindowOpen());
        assertTrue(a.windowHasSwipe());
        a.onWindowReset();
        assertFalse(a.isWindowOpen());
        assertFalse(a.windowHasSwipe());
        // After reset, an immediate next input opens a fresh window (not an extend).
        clock.t = 1010L;                                  // tiny delta — would extend if not reset
        assertEquals(WordWindowArbiter.Decision.START_FRESH, a.onStartInput(200, 0, false));
        assertFalse(a.windowHasSwipe());
    }

    @Test
    public void testExactGapBoundaryCommits() {
        final FakeClock clock = new FakeClock(1000L);
        final WordWindowArbiter a = new WordWindowArbiter(clock);
        // The timer only arms once a swipe is in the window; open with a swipe so the boundary
        // below actually exercises the gap logic (pure-tap windows never commit).
        a.onStartInput(200, 0, true);
        a.recordInputEnd(1000L, true);
        // exactly gap ⇒ specify's "at least gap" ⇒ COMMIT_THEN_START ("within gap" is
        // strict; equal to gap is a new word).
        clock.t = 1200L;
        assertEquals(WordWindowArbiter.Decision.COMMIT_THEN_START, a.onStartInput(200, 0, false));
    }

    @Test
    public void testJustUnderGapExtends() {
        final FakeClock clock = new FakeClock(1000L);
        final WordWindowArbiter a = new WordWindowArbiter(clock);
        a.onStartInput(200, 0, true);                       // arm the timer with a swipe
        a.recordInputEnd(1000L, true);
        clock.t = 1199L;                                  // 199 < 200 ⇒ EXTEND
        assertEquals(WordWindowArbiter.Decision.EXTEND, a.onStartInput(200, 0, false));
    }

    @Test
    public void testTogglingFeatureOffMidWindowResetsToStock() {
        final FakeClock clock = new FakeClock(1000L);
        final WordWindowArbiter a = new WordWindowArbiter(clock);
        a.onStartInput(200, 0, true);                        // builds a swipe-window
        a.recordInputEnd(1000L, true);
        // User turns the setting off (gap → 0). The arbiter must now always answer
        // START_FRESH regardless of the in-flight window state.
        clock.t = 1050L;
        assertEquals(WordWindowArbiter.Decision.START_FRESH, a.onStartInput(0, 0, true));
        // Existing window state remains but is no-op while gap == 0; a subsequent normal
        // commit (e.g. user presses space) calls onWindowReset to clean it up.
        a.onWindowReset();
        assertFalse(a.isWindowOpen());
    }

    @Test
    public void testTapGapOnArmsTimerForPureTapWindows() {
        final FakeClock clock = new FakeClock(1000L);
        final WordWindowArbiter a = new WordWindowArbiter(clock);
        // 'h' — opens the window. (swipeGap=200, tapGap=400 ⇒ "Enable auto-space when tapping".)
        assertEquals(WordWindowArbiter.Decision.START_FRESH, a.onStartInput(200, 400, false));
        a.recordInputEnd(1000L, false);
        // 'a' within the tap gap ⇒ EXTEND (still pure-tap).
        clock.t = 1300L;
        assertEquals(WordWindowArbiter.Decision.EXTEND, a.onStartInput(200, 400, false));
        a.recordInputEnd(1300L, false);
        // 'l' past the tap gap ⇒ the pending "ha" commits lazily before this tap begins.
        clock.t = 2000L;
        assertEquals(WordWindowArbiter.Decision.COMMIT_THEN_START, a.onStartInput(200, 400, false));
        assertFalse(a.windowHasSwipe());                   // the committed word was pure-tap
    }

    @Test
    public void testTapGapOnFirstSwipeAfterSlowTapPrefixCommits() {
        final FakeClock clock = new FakeClock(1000L);
        final WordWindowArbiter a = new WordWindowArbiter(clock);
        // 'h' typed, then a pause past the tap gap, then a swipe: the pending tap word must
        // commit before the swipe starts a fresh word.
        assertEquals(WordWindowArbiter.Decision.START_FRESH, a.onStartInput(200, 400, false));
        a.recordInputEnd(1000L, false);
        clock.t = 5000L;
        assertEquals(WordWindowArbiter.Decision.COMMIT_THEN_START, a.onStartInput(200, 400, true));
        assertFalse(a.windowHasSwipe());                   // committed word was pure-tap
    }

    @Test
    public void testTapGapOnFirstSwipeExtendsTapPrefixWithinGap() {
        final FakeClock clock = new FakeClock(1000L);
        final WordWindowArbiter a = new WordWindowArbiter(clock);
        // 'h', then a swipe within the tap gap: it extends the pure-tap prefix, still in flight.
        assertEquals(WordWindowArbiter.Decision.START_FRESH, a.onStartInput(200, 400, false));
        a.recordInputEnd(1000L, false);
        clock.t = 1200L;
        assertEquals(WordWindowArbiter.Decision.EXTEND, a.onStartInput(200, 400, true));
        assertFalse(a.windowHasSwipe());
        a.recordInputEnd(1200L, true);
        assertTrue(a.windowHasSwipe());
        // From here the swipe gap governs: within it extends...
        clock.t = 1300L;
        assertEquals(WordWindowArbiter.Decision.EXTEND, a.onStartInput(200, 400, false));
        // ...past it commits the whole window lazily.
        clock.t = 2000L;
        assertEquals(WordWindowArbiter.Decision.COMMIT_THEN_START, a.onStartInput(200, 400, false));
    }
}