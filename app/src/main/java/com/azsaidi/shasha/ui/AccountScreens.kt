package com.azsaidi.shasha.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
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

/**
 * Sign-in form. With [editing] set, it edits that playlist instead (same account, so favourites
 * and watch history are kept).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    canGoBack: Boolean,
    onBack: () -> Unit,
    onLoggedIn: (Account, AuthInfo) -> Unit,
    editing: Account? = null,
) {
    val context = LocalContext.current
    var name by rememberSaveable { mutableStateOf(editing?.name.orEmpty()) }
    var username by rememberSaveable { mutableStateOf(editing?.username.orEmpty()) }
    var password by rememberSaveable { mutableStateOf(editing?.password.orEmpty()) }
    var server by rememberSaveable { mutableStateOf(editing?.server.orEmpty()) }
    var showPassword by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val isTv = rememberIsTv()

    fun submit() {
        if (loading) return
        loading = true
        error = null
        scope.launch {
            when (val result = performLogin(context, name, username, password, server, editing?.id)) {
                is LoginResult.Success -> onLoggedIn(result.account, result.auth)
                is LoginResult.Error -> error = result.message
            }
            loading = false
        }
    }

    Scaffold(
        topBar = {
            if (canGoBack) {
                TopAppBar(
                    title = { Text(stringResource(if (editing != null) R.string.edit_account else R.string.add_account)) },
                    navigationIcon = {
                        IconButton(onClick = onBack, modifier = Modifier.focusRing(CircleShape)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            }
        },
    ) { padding ->
        Row(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            if (isTv && editing == null) {
                PhoneLoginPanel(
                    onLoggedIn = onLoggedIn,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Spacer(Modifier.height(if (canGoBack) 0.dp else 32.dp))
                if (editing == null) {
                    Image(
                        painter = painterResource(R.drawable.ic_logo),
                        contentDescription = null,
                        modifier = Modifier.size(120.dp),
                    )
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                }
                Text(
                    stringResource(if (editing != null) R.string.edit_tip else R.string.login_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))

                val fieldModifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 480.dp)
                val shape = RoundedCornerShape(14.dp)

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.playlist_name)) },
                    singleLine = true,
                    shape = shape,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = fieldModifier,
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.username)) },
                    singleLine = true,
                    shape = shape,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                    modifier = fieldModifier,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.password)) },
                    singleLine = true,
                    shape = shape,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }, modifier = Modifier.focusRing(CircleShape)) {
                            Icon(
                                if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = stringResource(if (showPassword) R.string.hide_password else R.string.show_password),
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                    modifier = fieldModifier,
                )
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it },
                    label = { Text(stringResource(R.string.server_field)) },
                    placeholder = { Text(stringResource(R.string.server_placeholder)) },
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
                    modifier = fieldModifier
                        .height(52.dp)
                        .focusRing(shape),
                ) {
                    if (loading) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(if (editing != null) R.string.save else R.string.log_in), style = MaterialTheme.typography.titleMedium)
                    }
                }
                if (editing == null) {
                    Text(
                        stringResource(R.string.login_tip),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

internal sealed class LoginResult {
    data class Success(val account: Account, val auth: AuthInfo) : LoginResult()
    data class Error(val message: String) : LoginResult()
}

/** Checks the details with the provider and saves the account if they work. */
internal suspend fun performLogin(
    context: Context,
    name: String,
    username: String,
    password: String,
    server: String,
    existingId: String? = null,
): LoginResult {
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
        ?: return LoginResult.Error(context.getString(R.string.err_server))
    if (user.isEmpty() || pass.isEmpty()) return LoginResult.Error(context.getString(R.string.err_credentials))

    val account = Account(
        id = existingId ?: UUID.randomUUID().toString(),
        name = name.trim().ifEmpty { parsed?.host ?: context.getString(R.string.default_playlist) },
        server = normalized,
        username = user,
        password = pass,
    )
    return try {
        val auth = XtreamApi(account).login()
        Store.addAccount(account)
        LoginResult.Success(account, auth)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        LoginResult.Error(friendlyError(context, e))
    }
}

/** Shows a short confirmation after saving an edited playlist. */
internal fun toastSaved(context: Context) {
    Toast.makeText(context, context.getString(R.string.saved_changes), Toast.LENGTH_SHORT).show()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AccountsScreen(
    onOpen: (Account) -> Unit,
    onAdd: () -> Unit,
    onEdit: (Account) -> Unit,
    onEmpty: () -> Unit,
) {
    val context = LocalContext.current
    var accounts by remember { mutableStateOf(Store.accounts()) }
    var toDelete by remember { mutableStateOf<Account?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.accounts)) }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                modifier = Modifier.focusRing(RoundedCornerShape(16.dp)),
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.add_account)) },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            items(accounts) { account ->
                // The remote moves right from the row to the edit and delete buttons and back.
                val rowFocus = remember { FocusRequester() }
                val editFocus = remember { FocusRequester() }
                val deleteFocus = remember { FocusRequester() }
                ListItem(
                    headlineContent = { Text(account.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text("${account.username} · ${account.server}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    leadingContent = { Icon(Icons.Filled.AccountCircle, contentDescription = null) },
                    trailingContent = {
                        Row {
                            IconButton(
                                onClick = { onEdit(account) },
                                modifier = Modifier
                                    .focusRequester(editFocus)
                                    .focusProperties {
                                        start = rowFocus
                                        end = deleteFocus
                                    }
                                    .focusRing(CircleShape),
                            ) {
                                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.edit_playlist))
                            }
                            IconButton(
                                onClick = { toDelete = account },
                                modifier = Modifier
                                    .focusRequester(deleteFocus)
                                    .focusProperties { start = editFocus }
                                    .focusRing(CircleShape),
                            ) {
                                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.remove))
                            }
                        }
                    },
                    modifier = Modifier
                        .focusRequester(rowFocus)
                        .focusProperties { end = editFocus }
                        .focusRing(RoundedCornerShape(10.dp))
                        .clip(RoundedCornerShape(10.dp))
                        .combinedClickable(
                            onClick = { onOpen(account) },
                            onLongClick = {
                                showAccountMenu(
                                    account.name,
                                    context,
                                    onOpen = { onOpen(account) },
                                    onEdit = { onEdit(account) },
                                    onRemove = { toDelete = account },
                                )
                            },
                        ),
                )
            }
        }
    }

    toDelete?.let { account ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(stringResource(R.string.remove_account_title, account.name)) },
            text = { Text(stringResource(R.string.remove_account_body)) },
            confirmButton = {
                TextButton(onClick = {
                    Store.removeAccount(account.id)
                    if (Session.account?.id == account.id) Session.close()
                    accounts = Store.accounts()
                    toDelete = null
                    if (accounts.isEmpty()) onEmpty()
                }) { Text(stringResource(R.string.remove)) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
