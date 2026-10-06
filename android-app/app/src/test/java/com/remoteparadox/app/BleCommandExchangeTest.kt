package com.remoteparadox.app

import com.remoteparadox.app.data.BleCommandExchange
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BleCommandExchangeTest {
    private val armed = """{"connected":true,"partitions":[{"armed":true}]}"""
    private val disarmed = """{"connected":true,"partitions":[{"armed":false}]}"""

    @Test
    fun `cancelled BLE read retains old reply slot until complete response drains`() = runTest {
        val exchange = BleCommandExchange()
        val gatt = Any()
        exchange.connected(gatt)
        var writes = 0
        val old = async {
            exchange.execute(
                1_000,
                {
                    writes++
                    true
                },
                { fail("Drained reply must not quarantine") },
            )
        }
        runCurrent()
        old.cancel()
        val resumed = async {
            exchange.execute(
                1_000,
                {
                    writes++
                    true
                },
                { fail("Fresh reply must not quarantine") },
            )
        }
        runCurrent()
        assertEquals(1, writes)
        assertNull(exchange.receive(gatt, armed.take(20)))
        assertFalse(resumed.isCompleted)
        assertEquals(armed, exchange.receive(gatt, armed.drop(20)))
        runCurrent()
        assertEquals(2, writes)
        assertFalse(resumed.isCompleted)
        exchange.receive(gatt, disarmed)
        assertEquals(disarmed, resumed.await())
        old.join()
        assertTrue(old.isCancelled)
    }

    @Test
    fun `timeout quarantines connection and old GATT reply cannot satisfy reconnected read`() =
        runTest {
            val exchange = BleCommandExchange()
            val oldGatt = Any()
            val newGatt = Any()
            exchange.connected(oldGatt)
            var writes = 0
            var closes = 0
            val old = async {
                exchange.execute(
                    1_000,
                    {
                        writes++
                        true
                    },
                    {
                        assertSame(oldGatt, it)
                        closes++
                    },
                )
            }
            runCurrent()
            advanceTimeBy(1_001)
            runCurrent()
            assertNull(old.await())
            assertEquals(1, closes)
            assertNull(exchange.receive(oldGatt, armed))
            assertNull(
                exchange.execute(
                    1_000,
                    {
                        writes++
                        true
                    },
                    { fail("Must remain quarantined") },
                )
            )
            assertEquals(1, writes)
            exchange.connected(newGatt)
            val fresh = async {
                exchange.execute(
                    1_000,
                    {
                        assertSame(newGatt, it)
                        writes++
                        true
                    },
                    { fail("Fresh reply") },
                )
            }
            runCurrent()
            assertNull(exchange.receive(oldGatt, armed))
            exchange.disconnected(oldGatt)
            assertFalse(fresh.isCompleted)
            exchange.receive(newGatt, disarmed)
            assertEquals(disarmed, fresh.await())
            assertEquals(2, writes)
        }

    @Test
    fun `partial JSON never releases waiter or leaks into another request`() = runTest {
        val exchange = BleCommandExchange()
        val gatt = Any()
        exchange.connected(gatt)
        var closes = 0
        val result = async { exchange.execute(1_000, { true }, { closes++ }) }
        runCurrent()
        exchange.receive(gatt, "{\"connected\":true,")
        advanceTimeBy(500)
        assertFalse(result.isCompleted)
        advanceTimeBy(501)
        runCurrent()
        assertNull(result.await())
        assertEquals(1, closes)
        assertNull(exchange.receive(gatt, "\"partitions\":[]}"))
    }

    @Test
    fun `replacement connection does not inherit or get closed by outstanding old read`() =
        runTest {
            val exchange = BleCommandExchange()
            val oldGatt = Any()
            val newGatt = Any()
            exchange.connected(oldGatt)
            val old = async {
                exchange.execute(1_000, { true }, { fail("Do not close replacement") })
            }
            runCurrent()
            exchange.connected(newGatt)
            runCurrent()
            assertNull(old.await())
            val fresh = async { exchange.execute(1_000, { true }, { fail("Fresh reply") }) }
            runCurrent()
            assertNull(exchange.receive(oldGatt, armed))
            exchange.receive(newGatt, disarmed)
            assertEquals(disarmed, fresh.await())
        }

    @Test
    fun `reply slot exists before synchronous write callback arrives`() = runTest {
        val exchange = BleCommandExchange()
        val gatt = Any()
        exchange.connected(gatt)
        assertEquals(
            disarmed,
            exchange.execute(
                1_000,
                {
                    exchange.receive(it, disarmed)
                    true
                },
                { fail("Reply was delivered") },
            ),
        )
    }

    @Test
    fun `rejected write quarantines once and does not replay`() = runTest {
        val exchange = BleCommandExchange()
        exchange.connected(Any())
        var writes = 0
        var closes = 0
        assertNull(
            exchange.execute(
                1_000,
                {
                    writes++
                    false
                },
                { closes++ },
            )
        )
        assertNull(
            exchange.execute(
                1_000,
                {
                    writes++
                    true
                },
                { closes++ },
            )
        )
        assertEquals(1, writes)
        assertEquals(1, closes)
    }
}
