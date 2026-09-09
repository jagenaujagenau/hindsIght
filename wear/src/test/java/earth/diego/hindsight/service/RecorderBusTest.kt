package earth.diego.hindsight.service

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RecorderBusTest {
    @Before fun reset() { RecorderBus.publish(SaveState.Idle) }

    private fun saved() = SaveState.Saved("clip.m4a", 58_000, 1000)

    @Test fun `a result emitted without an activity is available on the next collection`() = runBlocking {
        RecorderBus.publish(saved())
        assertEquals(saved(), RecorderBus.save.first())
    }

    @Test fun `local save is not reported as phone delivery`() {
        RecorderBus.publish(saved())
        assertFalse((RecorderBus.save.value as SaveState.Saved).delivered)
    }

    @Test fun `only the matching phone ack confirms the latest save`() {
        RecorderBus.publish(saved())
        RecorderBus.acknowledge("older.m4a")
        assertEquals(saved(), RecorderBus.save.value)
        RecorderBus.acknowledge("clip.m4a")
        assertEquals(saved().copy(delivered = true), RecorderBus.save.value)
        RecorderBus.acknowledge("clip.m4a")
        assertEquals(saved().copy(delivered = true), RecorderBus.save.value)
    }

    @Test fun `an older ack cannot overwrite an in-flight save or its failure`() {
        RecorderBus.publish(SaveState.Saving)
        RecorderBus.acknowledge("clip.m4a")
        assertEquals(SaveState.Saving, RecorderBus.save.value)
        val failure = SaveState.Failed("Free storage and retry")
        RecorderBus.publish(failure)
        RecorderBus.acknowledge("clip.m4a")
        assertEquals(failure, RecorderBus.save.value)
    }
}
