package com.azsaidi.shasha.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.azsaidi.shasha.R
import com.azsaidi.shasha.data.Account
import com.azsaidi.shasha.data.AuthInfo
import com.azsaidi.shasha.data.Session
import com.azsaidi.shasha.data.Store
import com.azsaidi.shasha.data.XtreamApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    canGoBack: Boolean,
    onBack: () -> Unit,
    onLoggedIn: (Account, AuthInfo) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var server by rememberSaveable { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun submit() {
        if (loading) return
        var user = username.trim()
        var pass = password
        val raw = server.trim()

        // Accept a full M3U link (get.php?username=...&password=...) and pull the login out of it.
        val parsed = (if (raw.contains("://")) raw else "http://$raw").toHttpUrlOrNull()
        if (parsed != null) {
            if (user.isEmpty()) parsed.queryParameter("username")?.let { user = it }
            if (pass.isEmpty()) parsed.queryParameter("password")?.let { pass = it }
        }

        val normalized = XtreamApi.normalizeServer(raw)
        if (normalized == null) {
            error = "Enter the server address your provider gave you"
            return
        }
        if (user.isEmpty() || pass.isEmpty()) {
            error = "Enter your username and password"
            return
        }

        val account = Account(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { parsed?.host ?: "My playlist" },
            server = normalized,
            username = user,
            password = pass,
        )
        loading = true
        error = null
        scope.launch {
            try {
                val auth = XtreamApi(account).login()
                Store.addAccount(account)
                onLoggedIn(account, auth)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = friendlyError(e)
            } finally {
                loading = false
            }
        }
    }

    Scaffold(
        topBar = {
            if (canGoBack) {
                TopAppBar(
                    title = { Text("Add account") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(if (canGoBack) 0.dp else 32.dp))
            Image(
                painter = painterResource(R.drawable.ic_logo),
                contentDescription = null,
                modifier = Modifier.size(120.dp),
            )
            Text("Shasha", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "Sign in with the Xtream Codes details from your IPTV provider.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            val fieldModifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 480.dp)
            val shape = RoundedCornerShape(14.dp)

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Playlist name") },
                singleLine = true,
                shape = shape,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = fieldModifier,
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                singleLine = true,
                shape = shape,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                modifier = fieldModifier,
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                shape = shape,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (showPassword) "Hide password" else "Show password",
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                modifier = fieldModifier,
            )
            OutlinedTextField(
                value = server,
                onValueChange = { server = it },
                label = { Text("URL, domain or server") },
                placeholder = { Text("http://example.com:8080") },
                singleLine = true,
                shape = shape,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = fieldModifier,
            )

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            Button(
                onClick = { submit() },
                enabled = !loading,
                shape = shape,
                modifier = fieldModifier.height(52.dp),
            ) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    Text("Log in", style = MaterialTheme.typography.titleMedium)
                }
            }
            Text(
                "Tip: you can also paste a full M3U link from your provider into the server field.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(
    onOpen: (Account) -> Unit,
    onAdd: () -> Unit,
    onEmpty: () -> Unit,
) {
    var accounts by remember { mutableStateOf(Store.accounts()) }
    var toDelete by remember { mutableStateOf<Account?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Accounts") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add account") },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            items(accounts) { account ->
                ListItem(
                    headlineContent = { Text(account.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text("${account.username} · ${account.server}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    leadingContent = { Icon(Icons.Filled.AccountCircle, contentDescription = null) },
                    trailingContent = {
                        IconButton(onClick = { toDelete = account }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Remove")
                        }
                    },
                    modifier = Modifier.clickable { onOpen(account) },
                )
            }
        }
    }

    toDelete?.let { account ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Remove ${account.name}?") },
            text = { Text("Its login details and favorites will be deleted from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    Store.removeAccount(account.id)
                    if (Session.account?.id == account.id) Session.close()
                    accounts = Store.accounts()
                    toDelete = null
                    if (accounts.isEmpty()) onEmpty()
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }
}
