package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.telegram.account.TelegramAccountClient
import me.rerere.rikkahub.data.telegram.account.TgAuthState
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject

/**
 * Settings → Telegram account: log the agent into the user's own Telegram account (TDLib),
 * as opposed to the bot integration. API id/hash → phone → code → (2FA password) → ready.
 */
@Composable
fun SettingTelegramAccountPage() {
    val client: TelegramAccountClient = koinInject()
    val state by client.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var apiId by remember { mutableStateOf("") }
    var apiHash by remember { mutableStateOf("") }
    var input by remember(state) { mutableStateOf("") }
    var input2 by remember(state) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val (id, hash) = client.credentialsFlow.first()
        apiId = id
        apiHash = hash
    }

    fun step(block: suspend () -> Result<Unit>) {
        busy = true
        error = null
        scope.launch {
            block().onFailure { error = it.message }
            busy = false
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.tg_account_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.tg_account_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val s = state
                    Text(
                        when (s) {
                            is TgAuthState.Ready -> stringResource(R.string.tg_account_connected, s.name.ifBlank { "…" }) +
                                (s.username?.let { " (@$it)" } ?: "")
                            is TgAuthState.Unavailable -> stringResource(R.string.tg_account_unavailable, s.reason)
                            else -> stringResource(R.string.tg_account_status, s::class.simpleName ?: "")
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    when (s) {
                        is TgAuthState.Unavailable -> Unit
                        is TgAuthState.NotConfigured, TgAuthState.Stopped, is TgAuthState.Error -> {
                            OutlinedTextField(
                                value = apiId, onValueChange = { apiId = it },
                                label = { Text(stringResource(R.string.tg_account_api_id)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = apiHash, onValueChange = { apiHash = it },
                                label = { Text(stringResource(R.string.tg_account_api_hash)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Button(
                                enabled = !busy && apiId.toIntOrNull() != null && apiHash.length >= 16,
                                onClick = {
                                    step {
                                        client.saveCredentials(apiId, apiHash)
                                        client.start()
                                        Result.success(Unit)
                                    }
                                },
                            ) { Text(stringResource(R.string.tg_account_connect)) }
                        }

                        TgAuthState.Starting, TgAuthState.LoggingOut -> Text(stringResource(R.string.tg_account_wait))
                        TgAuthState.WaitPhone -> {
                            OutlinedTextField(
                                value = input, onValueChange = { input = it },
                                label = { Text(stringResource(R.string.tg_account_phone)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Button(enabled = !busy && input.length > 5, onClick = { step { client.submitPhone(input) } }) {
                                Text(stringResource(R.string.tg_account_next))
                            }
                        }

                        is TgAuthState.WaitCode -> {
                            Text(stringResource(R.string.tg_account_code_sent, s.codeType, s.sentTo), style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(
                                value = input, onValueChange = { input = it },
                                label = { Text(stringResource(R.string.tg_account_code)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Button(enabled = !busy && input.isNotBlank(), onClick = { step { client.submitCode(input) } }) {
                                Text(stringResource(R.string.tg_account_next))
                            }
                        }

                        is TgAuthState.WaitPassword -> {
                            if (s.hint.isNotBlank()) Text(stringResource(R.string.tg_account_password_hint, s.hint), style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(
                                value = input, onValueChange = { input = it },
                                label = { Text(stringResource(R.string.tg_account_password)) },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Button(enabled = !busy && input.isNotEmpty(), onClick = { step { client.submitPassword(input) } }) {
                                Text(stringResource(R.string.tg_account_next))
                            }
                        }

                        TgAuthState.WaitEmail -> {
                            OutlinedTextField(
                                value = input, onValueChange = { input = it },
                                label = { Text(stringResource(R.string.tg_account_email)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Button(enabled = !busy && input.contains('@'), onClick = { step { client.submitEmail(input) } }) {
                                Text(stringResource(R.string.tg_account_next))
                            }
                        }

                        is TgAuthState.WaitEmailCode -> {
                            Text(s.pattern, style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(
                                value = input, onValueChange = { input = it },
                                label = { Text(stringResource(R.string.tg_account_code)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Button(enabled = !busy && input.isNotBlank(), onClick = { step { client.submitEmailCode(input) } }) {
                                Text(stringResource(R.string.tg_account_next))
                            }
                        }

                        TgAuthState.WaitRegistration -> {
                            OutlinedTextField(value = input, onValueChange = { input = it }, label = { Text(stringResource(R.string.tg_account_first_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(value = input2, onValueChange = { input2 = it }, label = { Text(stringResource(R.string.tg_account_last_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            Button(enabled = !busy && input.isNotBlank(), onClick = { step { client.submitRegistration(input, input2) } }) {
                                Text(stringResource(R.string.tg_account_next))
                            }
                        }

                        is TgAuthState.Ready -> {
                            s.phone?.let { Text("+$it", style = MaterialTheme.typography.bodySmall) }
                            Text(stringResource(R.string.tg_account_ready_hint), style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(enabled = !busy, onClick = { step { client.logOut() } }) {
                                Text(stringResource(R.string.tg_account_logout))
                            }
                        }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}
