package com.kennyb1201.kbstream.ui.addons

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text

@Composable
fun AddonsScreen(
    onBack: () -> Unit,
    viewModel: AddonsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val addons by viewModel.addons.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    var urlInput by remember { mutableStateOf("") }

    Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Column {
            Text("Manage Add-ons", modifier = Modifier.padding(bottom = 16.dp))

            BasicTextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                singleLine = true,
                textStyle = TextStyle(color = Color.White),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1B3A57))
                    .padding(12.dp)
            )

            Card(
                onClick = { viewModel.addAddon(urlInput); urlInput = "" },
                colors = CardDefaults.colors(containerColor = Color(0xFF4FC3F7), contentColor = Color.Black),
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
            ) {
                Text(if (isLoading) "Adding..." else "Add Addon", modifier = Modifier.padding(12.dp))
            }

            error?.let {
                Text("Error: $it", modifier = Modifier.padding(bottom = 16.dp))
            }

            LazyColumn {
                items(addons) { addon ->
                    Card(
                        onClick = { viewModel.removeAddon(addon.id) },
                        colors = CardDefaults.colors(containerColor = Color(0xFF1B3A57), contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(addon.name)
                            Text(addon.resources.joinToString(", "))
                        }
                    }
                }
            }

            Card(
                onClick = onBack,
                colors = CardDefaults.colors(containerColor = Color(0xFF1B3A57), contentColor = Color.White),
                modifier = Modifier.padding(top = 16.dp)
            ) {
                Text("Back", modifier = Modifier.padding(12.dp))
            }
        }
    }
}
