package com.jamina.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jamina.AppContainer
import com.jamina.core.db.FloorEntity
import com.jamina.core.db.ProjectEntity
import com.jamina.core.model.fmt
import com.jamina.ui.components.EmptyState
import com.jamina.ui.components.Fmt
import com.jamina.ui.components.NumberField
import com.jamina.ui.components.appViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProjectsViewModel(val c: AppContainer) : ViewModel() {
    val projects = c.dao.projects().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    suspend fun create(name: String): ProjectEntity {
        val p = c.repo.createProject(name)
        c.repo.createFloor(p.id, "Ground floor", 0)
        return p
    }
    fun delete(id: String) { viewModelScope.launch { c.repo.deleteProject(id) } }
    suspend fun importBackup(text: String) = c.repo.importProject(text)
}

/** Lists projects; used as the root of Survey, Planner and Reports tabs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectListScreen(title: String, subtitle: String, onOpen: (String) -> Unit, allowCreate: Boolean = true) {
    val vm = appViewModel { ProjectsViewModel(it) }
    val projects by vm.projects.collectAsState()
    var create by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<ProjectEntity?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val text = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() } ?: error("Cannot read file")
                val p = vm.importBackup(text)
                Toast.makeText(ctx, "Restored ${p.name}", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(ctx, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(title) }, actions = {
                IconButton(onClick = { importer.launch(arrayOf("application/json", "application/octet-stream", "*/*")) }) { Icon(Icons.Default.FileOpen, "Restore backup") }
            })
        },
        floatingActionButton = {
            if (allowCreate) ExtendedFloatingActionButton(onClick = { create = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("New building") })
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp)) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 6.dp))
            if (projects.isEmpty()) EmptyState("No buildings yet. Create one, or use Quick Survey on the Dashboard.")
            LazyColumn {
                items(projects, key = { it.id }) { p ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onOpen(p.id) }) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(p.name, style = MaterialTheme.typography.titleMedium)
                                Text("Updated ${Fmt.dateTime(p.updatedAt)}", style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { confirmDelete = p }) { Icon(Icons.Default.Delete, "Delete") }
                        }
                    }
                }
            }
        }
    }
    if (create) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { create = false },
            title = { Text("New building") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { create = false; scope.launch { onOpen(vm.create(name).id) } }) { Text("Create") } },
            dismissButton = { TextButton(onClick = { create = false }) { Text("Cancel") } },
        )
    }
    confirmDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${p.name}?") },
            text = { Text("All floors, drawings, surveys and measurements in this building will be permanently deleted. Export a backup from Reports first if you may need them.") },
            confirmButton = { TextButton(onClick = { vm.delete(p.id); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

class ProjectViewModel(val c: AppContainer, val projectId: String) : ViewModel() {
    val project = c.dao.projectFlow(projectId).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val floors = c.dao.floors(projectId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val sessions = c.dao.sessionsForProject(projectId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    fun addFloor(name: String, level: Int, copyFrom: FloorEntity?) { viewModelScope.launch { c.repo.createFloor(projectId, name, level, copyFrom) } }
    fun deleteFloor(id: String) { viewModelScope.launch { c.repo.deleteFloor(id) } }
    fun rename(name: String) { viewModelScope.launch { project.value?.let { c.dao.upsertProject(it.copy(name = name, updatedAt = System.currentTimeMillis())) } } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectScreen(projectId: String, onBack: () -> Unit, onOpenFloor: (String, String?) -> Unit, onPlanner: () -> Unit, onReports: () -> Unit) {
    val vm = appViewModel(key = "project-$projectId") { ProjectViewModel(it, projectId) }
    val project by vm.project.collectAsState()
    val floors by vm.floors.collectAsState()
    val sessions by vm.sessions.collectAsState()
    var addFloor by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<FloorEntity?>(null) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(project?.name ?: "", Modifier.clickable { rename = true }) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp)) {
            Row(Modifier.padding(vertical = 6.dp)) {
                FilledTonalButton(onClick = { addFloor = true }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Add floor") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onPlanner) { Text("Planner") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onReports) { Text("Reports") }
            }
            LazyColumn {
                items(floors, key = { it.id }) { f ->
                    val n = sessions.count { it.floorId == f.id }
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onOpenFloor(f.id, null) }) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${f.name}  (level ${f.level})", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${if (f.imagePath != null) "Imported plan" else "Drawn blueprint"} • ${f.widthM.fmt(1)} × ${f.heightM.fmt(1)} m${if (!f.calibrated && f.imagePath != null) " (uncalibrated)" else ""} • $n survey(s)",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            TextButton(onClick = { onOpenFloor(f.id, "draw") }) { Text("Edit") }
                            IconButton(onClick = { confirmDelete = f }) { Icon(Icons.Default.Delete, "Delete floor") }
                        }
                    }
                }
            }
        }
    }
    if (addFloor) {
        var name by remember { mutableStateOf(if (floors.isEmpty()) "Ground floor" else "Floor ${floors.size}") }
        var level by remember { mutableStateOf(((floors.maxOfOrNull { it.level } ?: -1) + 1).toString()) }
        var copy by remember { mutableStateOf(floors.isNotEmpty()) }
        AlertDialog(
            onDismissRequest = { addFloor = false },
            title = { Text("Add floor") },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    NumberField("Level", level, { level = it })
                    if (floors.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(copy, { copy = it }); Text("Copy size, scale and exterior walls from the floor below")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val lvl = level.toIntOrNull() ?: floors.size
                    val below = floors.filter { it.level < lvl }.maxByOrNull { it.level } ?: floors.firstOrNull()
                    vm.addFloor(name, lvl, if (copy) below else null); addFloor = false
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { addFloor = false }) { Text("Cancel") } },
        )
    }
    if (rename) {
        var name by remember { mutableStateOf(project?.name ?: "") }
        AlertDialog(
            onDismissRequest = { rename = false },
            title = { Text("Rename building") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.rename(name); rename = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { rename = false }) { Text("Cancel") } },
        )
    }
    confirmDelete?.let { f ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${f.name}?") },
            text = { Text("The plan, walls, planned APs and all surveys on this floor will be permanently deleted.") },
            confirmButton = { TextButton(onClick = { vm.deleteFloor(f.id); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}
