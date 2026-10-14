package dev.puck

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.puck.core.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskRepositoryTest {
    @Test fun durableGateAllowsOnlyOneConcurrentClaim() = runTest {
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PuckDatabase::class.java).build()
        try {
            val repository = TaskRepository(database.tasks())
            val task = PuckTask("id", WorkspaceRequest.Confirmation("id", DeviceAction.Camera), TaskPhase.AWAITING_APPROVAL)
            assertTrue(repository.insert(task))
            assertFalse(repository.insert(task.copy(request = WorkspaceRequest.Confirmation("id", DeviceAction.Navigate("elsewhere")))))
            val claims = (1..8).map { async { repository.update("id") { if (TaskMachine.canDispatch(it)) TaskMachine.move(it, TaskPhase.DISPATCHING, "Opening") else null } } }.awaitAll()
            assertEquals(1, claims.count { it != null })
            repository.recoverInterrupted()
            assertEquals(TaskPhase.FAILED, repository.get("id")!!.phase)
            assertTrue(repository.get("id")!!.note.contains("not replayed"))
        } finally { database.close() }
    }
    @Test fun approvedTaskAndDuplicateIdsSurviveReopeningDatabase() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "puck-persistence-test.db"
        context.deleteDatabase(name)
        val original = PuckTask("persistent-id", WorkspaceRequest.Confirmation("persistent-id", DeviceAction.Message("Draft body", "+12345")), TaskPhase.AWAITING_APPROVAL)
        var database = Room.databaseBuilder(context, PuckDatabase::class.java, name).build()
        try {
            assertTrue(TaskRepository(database.tasks()).insert(original))
            database.close()
            database = Room.databaseBuilder(context, PuckDatabase::class.java, name).build()
            val repository = TaskRepository(database.tasks())
            assertEquals(original, repository.get("persistent-id"))
            assertFalse(repository.insert(original.copy(request = WorkspaceRequest.Confirmation("persistent-id", DeviceAction.Camera))))
            repository.clear()
            assertFalse(repository.insert(original)) // Archived IDs still prevent replay.
        } finally { database.close(); context.deleteDatabase(name) }
    }

}
