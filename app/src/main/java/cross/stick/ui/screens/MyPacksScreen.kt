package cross.stick.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cross.stick.viewmodel.MainViewModel

@Composable
fun MyPacksScreen(viewModel: MainViewModel) {
    val savedPacks by viewModel.savedPacks.collectAsState()
    val context = LocalContext.current

    val waLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_CANCELED) {
            val error = result.data?.getStringExtra("validation_error")
            Toast.makeText(context, error ?: "Sticker pack not added", Toast.LENGTH_LONG).show()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("My Packs", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(8.dp))

        if (savedPacks.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No packs yet", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(savedPacks) { pack ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(pack.name, style = MaterialTheme.typography.titleMedium)
                                Text("${pack.stickerCount} stickers", style = MaterialTheme.typography.bodySmall)
                            }
                            Button(onClick = {
                                val errors = viewModel.validatePackForWhatsApp(pack.id)
                                if (errors.isEmpty()) {
                                    waLauncher.launch(viewModel.getWhatsAppIntent(pack.id))
                                } else {
                                    Toast.makeText(context, errors.joinToString("\n"), Toast.LENGTH_LONG).show()
                                }
                            }) {
                                Text("Add")
                            }
                        }
                    }
                }
            }
        }
    }
}
