package com.termux.terminal;

import junit.framework.TestCase;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;

public class ByteQueueAdmissionTest extends TestCase {

    public void testTryWriteCopiesWrappedEventsAtomically() {
        ByteQueue q = new ByteQueue(6);
        byte[] source = {0, 1, 2, 3, 4};
        assertEquals(ByteQueue.WriteResult.ACCEPTED, q.tryWrite(source, 1, 4));
        source[1] = 99;
        byte[] first = new byte[3];
        assertEquals(3, q.read(first, false));
        assertArrayEquals(new byte[]{1, 2, 3}, first);
        assertEquals(ByteQueue.WriteResult.ACCEPTED, q.tryWrite(new byte[]{5, 6, 7, 8, 9}, 0, 5));
        assertEquals(ByteQueue.WriteResult.FULL, q.tryWrite(new byte[]{99}, 0, 1));
        byte[] result = new byte[6];
        assertEquals(6, q.read(result, false));
        assertArrayEquals(new byte[]{4, 5, 6, 7, 8, 9}, result);
    }

    public void testTryWriteRejectsInsufficientSpaceAndOversizeWithoutChangingQueue() {
        ByteQueue q = new ByteQueue(4);
        assertEquals(ByteQueue.WriteResult.ACCEPTED, q.tryWrite(new byte[]{1, 2}, 0, 2));
        assertEquals(ByteQueue.WriteResult.FULL, q.tryWrite(new byte[]{3, 4, 5}, 0, 3));
        assertEquals(ByteQueue.WriteResult.FULL, q.tryWrite(new byte[5], 0, 5));
        byte[] result = new byte[4];
        assertEquals(2, q.read(result, false));
        assertArrayEquals(new byte[]{1, 2, 0, 0}, result);
        assertEquals(0, q.read(result, false));
    }

    public void testTryWriteEmptyClosedAndOverflowSafeRangeChecks() {
        ByteQueue q = new ByteQueue(1);
        q.close();
        assertEquals(ByteQueue.WriteResult.ACCEPTED, q.tryWrite(new byte[0], 0, 0));
        assertEquals(ByteQueue.WriteResult.CLOSED, q.tryWrite(new byte[]{1}, 0, 1));
        int[][] invalid = {{-1, 0}, {0, -1}, {2, 0}, {0, 2}, {1, Integer.MAX_VALUE}};
        for (int[] range : invalid) {
            try {
                q.tryWrite(new byte[1], range[0], range[1]);
                fail("Expected invalid range to be rejected");
            } catch (IllegalArgumentException expected) {
                assertEquals("Invalid offset or length", expected.getMessage());
            }
        }
        assertEquals(-1, q.read(new byte[1], false));
    }

    public void testTryWriteDoesNotWaitForFullQueueConsumer() throws Exception {
        ByteQueue q = new ByteQueue(1);
        assertTrue(q.write(new byte[]{1}, 0, 1));
        ExecutorService producer = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        try {
            Future<ByteQueue.WriteResult> result = producer.submit(() -> {
                entered.countDown();
                return q.tryWrite(new byte[]{2}, 0, 1);
            });
            // Watchdogs bound a stuck regression; there is no consumer and no latency target.
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(ByteQueue.WriteResult.FULL, result.get(5, TimeUnit.SECONDS));
            byte[] stored = new byte[1];
            assertEquals(1, q.read(stored, false));
            assertArrayEquals(new byte[]{1}, stored);
        } finally {
            q.close();
            producer.shutdownNow();
            assertTrue(producer.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    public void testCloseWakesBlockingReaderAndLegacyWriter() throws Exception {
        ByteQueue empty = new ByteQueue(1), full = new ByteQueue(1);
        assertTrue(full.write(new byte[]{1}, 0, 1));
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch entered = new CountDownLatch(2);
        try {
            Future<Integer> reader = workers.submit(() -> {
                entered.countDown();
                return empty.read(new byte[1], true);
            });
            Future<Boolean> writer = workers.submit(() -> {
                entered.countDown();
                return full.write(new byte[]{2}, 0, 1);
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            empty.close();
            full.close();
            assertEquals(Integer.valueOf(-1), reader.get(5, TimeUnit.SECONDS));
            assertFalse(writer.get(5, TimeUnit.SECONDS));
        } finally {
            empty.close();
            full.close();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    public void testConcurrentTryWriteEventsDoNotInterleave() throws Exception {
        ByteQueue q = new ByteQueue(8);
        ExecutorService producers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<ByteQueue.WriteResult> first = producers.submit(() -> {
                if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("Start watchdog");
                return q.tryWrite(new byte[]{1, 1, 1, 1}, 0, 4);
            });
            Future<ByteQueue.WriteResult> second = producers.submit(() -> {
                if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("Start watchdog");
                return q.tryWrite(new byte[]{2, 2, 2, 2}, 0, 4);
            });
            start.countDown();
            assertEquals(ByteQueue.WriteResult.ACCEPTED, first.get(5, TimeUnit.SECONDS));
            assertEquals(ByteQueue.WriteResult.ACCEPTED, second.get(5, TimeUnit.SECONDS));
            byte[] stored = new byte[8];
            assertEquals(8, q.read(stored, false));
            assertTrue(stored[0] == 1 || stored[0] == 2);
            for (int i = 0; i < 4; i++) assertEquals(stored[0], stored[i]);
            for (int i = 4; i < 8; i++) assertEquals(3 - stored[0], stored[i]);
        } finally {
            start.countDown();
            q.close();
            producers.shutdownNow();
            assertTrue(producers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    public void testReservationAdmissionIsAtomicAndReleaseUsesOnlyOwnedRingSpace() {
        ByteQueue q = new ByteQueue(8);
        ByteQueue.Reservation held = new ByteQueue.Reservation(q, 3);
        assertEquals(ByteQueue.WriteResult.ACCEPTED, q.tryWriteAndReserve(new byte[]{1, 2}, 0, 2, held));
        assertEquals(ByteQueue.WriteResult.ACCEPTED, q.tryWrite(new byte[]{3, 4, 5}, 0, 3));
        assertEquals(ByteQueue.WriteResult.FULL, q.tryWrite(new byte[]{99}, 0, 1));
        assertEquals(ByteQueue.WriteResult.ACCEPTED, q.tryWriteReserved(new byte[]{6, 7}, 0, 2, held));
        assertEquals(ByteQueue.WriteResult.CLOSED, q.tryWriteReserved(new byte[]{99}, 0, 1, held));
        // The same spent token must never free capacity twice.
        q.cancel(held);
        q.cancel(held);
        assertEquals(ByteQueue.WriteResult.ACCEPTED, q.tryWrite(new byte[]{8}, 0, 1));
        assertEquals(ByteQueue.WriteResult.FULL, q.tryWrite(new byte[]{99}, 0, 1));
        byte[] actual = new byte[8];
        assertEquals(8, q.read(actual, false));
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}, actual);
    }

    public void testInsufficientCombinedSpaceAndInvalidReleaseLeaveQueueAndPromiseIntact() {
        ByteQueue q = new ByteQueue(5), other = new ByteQueue(5);
        ByteQueue.Reservation tooLarge = new ByteQueue.Reservation(q, Integer.MAX_VALUE);
        assertEquals(ByteQueue.WriteResult.FULL, q.tryWriteAndReserve(new byte[]{1}, 0, 1, tooLarge));
        assertEquals(0, q.read(new byte[5], false));
        ByteQueue.Reservation held = new ByteQueue.Reservation(q, 3);
        assertEquals(ByteQueue.WriteResult.ACCEPTED, q.tryWriteAndReserve(new byte[]{1, 2}, 0, 2, held));
        try {
            q.tryWriteReserved(new byte[4], 0, 4, held);
            fail("Oversized release must not consume or append");
        } catch (IllegalArgumentException expected) { }
        try {
            other.cancel(held);
            fail("A different queue must not free the owner's promise");
        } catch (IllegalArgumentException expected) { }
        assertTrue(q.isActive(held));
        assertEquals(ByteQueue.WriteResult.FULL, q.tryWrite(new byte[]{99}, 0, 1));
        assertEquals(ByteQueue.WriteResult.ACCEPTED, q.tryWriteReserved(new byte[]{3, 4, 5}, 0, 3, held));
        byte[] actual = new byte[5];
        assertEquals(5, q.read(actual, false));
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, actual);
        try {
            q.tryWriteAndReserve(new byte[]{99}, 0, 1, held);
            fail("A spent promise must not be resurrected");
        } catch (IllegalArgumentException expected) { }
    }

    public void testCancelledPromiseIsNotBindableAgain() {
        ByteQueue q = new ByteQueue(4);
        ByteQueue.Reservation held = new ByteQueue.Reservation(q, 2);
        assertEquals(ByteQueue.WriteResult.ACCEPTED, q.tryWriteAndReserve(new byte[]{1}, 0, 1, held));
        q.cancel(held);
        assertFalse(q.isActive(held));
        assertEquals(ByteQueue.WriteResult.CLOSED, q.tryWriteReserved(new byte[]{2}, 0, 1, held));
        try {
            q.tryWriteAndReserve(new byte[]{3}, 0, 1, held);
            fail("A cancelled promise must not be bound again");
        } catch (IllegalArgumentException expected) { }
        assertEquals(ByteQueue.WriteResult.ACCEPTED, q.tryWrite(new byte[]{3, 4, 5}, 0, 3));
    }

    public void testCancelWakesLegacyProducerAndCloseInvalidatesLateTokens() throws Exception {
        ByteQueue q = new ByteQueue(1);
        ByteQueue.Reservation held = new ByteQueue.Reservation(q, 1);
        assertEquals(ByteQueue.WriteResult.ACCEPTED, q.tryWriteAndReserve(new byte[0], 0, 0, held));
        ExecutorService producer = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Thread> writingThread = new java.util.concurrent.atomic.AtomicReference<>();
        try {
            Future<Boolean> done = producer.submit(() -> {
                writingThread.set(Thread.currentThread());
                entered.countDown();
                return q.write(new byte[]{7}, 0, 1);
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            // Observe the real ByteQueue.wait(), not merely a submitted or idle executor task.
            // This watchdog is test-only; no timing target, sleep or production seam is added.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (writingThread.get().getState() != Thread.State.WAITING) {
                assertFalse("Legacy writer bypassed the held capacity", done.isDone());
                if (System.nanoTime() - deadline >= 0) fail("Writer did not enter ByteQueue.wait");
                Thread.yield();
            }
            assertFalse("An idle executor is not a blocked writer", done.isDone());
            q.cancel(held);
            q.cancel(held);
            assertTrue(done.get(5, TimeUnit.SECONDS));
            assertFalse(q.isActive(held));
            byte[] actual = new byte[1];
            assertEquals(1, q.read(actual, false));
            assertArrayEquals(new byte[]{7}, actual);
            ByteQueue.Reservation closing = new ByteQueue.Reservation(q, 1);
            assertEquals(ByteQueue.WriteResult.ACCEPTED, q.tryWriteAndReserve(new byte[0], 0, 0, closing));
            q.close();
            q.cancel(closing);
            q.cancel(closing);
            assertFalse(q.isActive(closing));
            assertEquals(ByteQueue.WriteResult.CLOSED, q.tryWriteReserved(new byte[]{9}, 0, 1, closing));
            java.lang.reflect.Field reserved = ByteQueue.class.getDeclaredField("mReservedBytes");
            reserved.setAccessible(true);
            assertEquals(0, reserved.getInt(q));
        } finally {
            q.close();
            producer.shutdownNow();
            assertTrue(producer.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

}
