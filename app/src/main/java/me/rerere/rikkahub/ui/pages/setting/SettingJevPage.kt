package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ModelType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.jev.JevConfig
import me.rerere.rikkahub.jev.JevPreferences
import me.rerere.rikkahub.jev.JevRule
import me.rerere.rikkahub.jev.JevRuleAction
import me.rerere.rikkahub.jev.JevRuleTrigger
import me.rerere.rikkahub.jev.JevService
import me.rerere.rikkahub.jev.JevUsageTotals
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings → Jev. Everything about TypeSafe's Jev decision model: key and connection test,
 * the features that use it (each independently switchable, with its confidence threshold),
 * the router's model pair, user rules, usage and a live log of what Jev decided.
 */
@Composable
fun SettingJevPage(vm: SettingVM = koinViewModel()) {
    val prefs: JevPreferences = koinInject()
    val jev: JevService = koinInject()
    val config by prefs.config.collectAsStateWithLifecycle()
    val usage by prefs.usageFlow.collectAsStateWithLifecycle(initialValue = JevUsageTotals())
    val decisions by jev.recentDecisions.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val update: ((JevConfig) -> JevConfig) -> Unit = { prefs.updateAsync(it) }
    var editingRule by remember { mutableStateOf<JevRule?>(null) }

    editingRule?.let { rule ->
        JevRuleDialog(
            initial = rule,
            providers = settings.providers,
            onDismiss = { editingRule = null },
            onSave = { saved ->
                update { c ->
                    val i = c.rules.indexOfFirst { it.id == saved.id }
                    c.copy(rules = if (i >= 0) c.rules.toMutableList().apply { set(i, saved) } else c.rules + saved)
                }
                editingRule = null
            },
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.jev_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("intro") {
                Text(
                    stringResource(R.string.jev_page_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item("connection") { JevConnectionCard(config, jev, update) }
            item("guard") { JevGuardCard(config, update) }
            item("router") { JevRouterCard(config, settings.providers, update) }
            item("assist") { JevAssistCard(config, update) }
            item("rules_header") {
                JevSection(stringResource(R.string.jev_rules_title), stringResource(R.string.jev_rules_desc)) {
                    OutlinedButton(onClick = { editingRule = JevRule() }) {
                        Icon(HugeIcons.Add01, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.jev_rules_add))
                    }
                }
            }
            items(config.rules, key = { it.id }) { rule ->
                JevRuleRow(
                    rule = rule,
                    onToggle = { on -> update { c -> c.copy(rules = c.rules.map { if (it.id == rule.id) it.copy(enabled = on) else it }) } },
                    onEdit = { editingRule = rule },
                    onDelete = { update { c -> c.copy(rules = c.rules.filterNot { it.id == rule.id }) } },
                )
            }
            item("usage") { JevUsageCard(usage, prefs) }
            item("log_header") {
                JevSection(stringResource(R.string.jev_log_title), stringResource(R.string.jev_log_desc)) {
                    if (decisions.isNotEmpty()) {
                        TextButton(onClick = { jev.clearLog() }) { Text(stringResource(R.string.jev_log_clear)) }
                    }
                }
            }
            if (decisions.isEmpty()) {
                item("log_empty") {
                    Text(
                        stringResource(R.string.jev_log_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // No stable key: two decisions can share a millisecond, feature and summary.
            items(decisions.size) { i ->
                val d = decisions[i]
                val time = remember(d.atMillis) { SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(d.atMillis)) }
                Text(
                    "$time · ${d.feature} · ${d.summary}" + if (d.latencyMs > 0) " · ${d.latencyMs} ms" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (d.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

// ------------------------------------------------------------------ building blocks

@Composable
private fun JevCard(content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

@Composable
private fun JevSection(title: String, description: String?, trailing: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            description?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing()
    }
}

@Composable
private fun JevSwitch(title: String, description: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    JevSection(title, description) {
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** Slider over 50–99 % that only writes when the finger lifts. */
@Composable
private fun JevThreshold(label: String, value: Float, onChange: (Float) -> Unit) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Column {
        Text("$label: ${(local * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onChange((kotlin.math.round(local * 100) / 100f)) },
            valueRange = 0.5f..0.99f,
        )
    }
}

/** Text field that keeps its own editing state and writes through on every change. */
@Composable
private fun JevTextField(
    label: String,
    value: String,
    secret: Boolean = false,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    var local by remember { mutableStateOf(value) }
    OutlinedTextField(
        value = local,
        onValueChange = { local = it; onChange(it) },
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
    )
}

// ------------------------------------------------------------------ cards

@Composable
private fun JevConnectionCard(config: JevConfig, jev: JevService, update: ((JevConfig) -> JevConfig) -> Unit) {
    val scope = rememberCoroutineScope()
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val okText = stringResource(R.string.jev_test_ok)
    JevCard {
        JevSwitch(
            stringResource(R.string.jev_enable),
            stringResource(R.string.jev_enable_desc),
            config.enabled,
        ) { on -> update { it.copy(enabled = on) } }
        JevTextField(stringResource(R.string.jev_api_key), config.apiKey, secret = true) { v -> update { it.copy(apiKey = v.trim()) } }
        JevTextField(stringResource(R.string.jev_model), config.model) { v -> update { it.copy(model = v.trim()) } }
        JevTextField(stringResource(R.string.jev_base_url), config.baseUrl) { v ->
            update { it.copy(baseUrl = v.trim().ifBlank { JevConfig.DEFAULT_BASE_URL }) }
        }
        JevTextField(
            stringResource(R.string.jev_timeout),
            config.timeoutMs.toString(),
            keyboardType = KeyboardType.Number,
        ) { v -> v.toLongOrNull()?.let { ms -> update { it.copy(timeoutMs = ms.coerceIn(500, 30_000)) } } }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                enabled = !testing && config.apiKey.isNotBlank(),
                onClick = {
                    testing = true
                    testResult = null
                    scope.launch {
                        testResult = runCatching { jev.test(config) }.fold(
                            onSuccess = { r -> "$okText ${r.model}, ${r.latencyMs} ms, ${r.usage.inputTokens} tokens" },
                            onFailure = { e -> "✗ ${e.message}" },
                        )
                        testing = false
                    }
                },
            ) { Text(stringResource(if (testing) R.string.jev_testing else R.string.jev_test)) }
            testResult?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun JevGuardCard(config: JevConfig, update: ((JevConfig) -> JevConfig) -> Unit) {
    val g = config.toolGuard
    fun set(transform: (me.rerere.rikkahub.jev.ToolGuardConfig) -> me.rerere.rikkahub.jev.ToolGuardConfig) =
        update { it.copy(toolGuard = transform(it.toolGuard)) }
    JevCard {
        JevSection(stringResource(R.string.jev_guard_title), stringResource(R.string.jev_guard_desc))
        JevSwitch(stringResource(R.string.jev_guard_escalate), stringResource(R.string.jev_guard_escalate_desc), g.escalateRisky) { v -> set { it.copy(escalateRisky = v) } }
        if (g.escalateRisky) JevThreshold(stringResource(R.string.jev_guard_risk_threshold), g.riskThreshold) { v -> set { it.copy(riskThreshold = v) } }
        JevSwitch(stringResource(R.string.jev_guard_auto), stringResource(R.string.jev_guard_auto_desc), g.autoApproveSafe) { v -> set { it.copy(autoApproveSafe = v) } }
        if (g.autoApproveSafe) JevThreshold(stringResource(R.string.jev_guard_safe_threshold), g.safeThreshold) { v -> set { it.copy(safeThreshold = v) } }
        JevSwitch(stringResource(R.string.jev_guard_all_tools), stringResource(R.string.jev_guard_all_tools_desc), g.allTools) { v -> set { it.copy(allTools = v) } }
        JevSwitch(stringResource(R.string.jev_guard_unattended), stringResource(R.string.jev_guard_unattended_desc), g.blockRiskyWhenUnattended) { v -> set { it.copy(blockRiskyWhenUnattended = v) } }
        JevTextField(stringResource(R.string.jev_guard_excluded), g.excludedTools.joinToString(", ")) { v ->
            set { it.copy(excludedTools = v.split(',').map { s -> s.trim() }.filter { s -> s.isNotEmpty() }) }
        }
    }
}

@Composable
private fun JevRouterCard(
    config: JevConfig,
    providers: List<me.rerere.ai.provider.ProviderSetting>,
    update: ((JevConfig) -> JevConfig) -> Unit,
) {
    val r = config.modelRouter
    fun set(transform: (me.rerere.rikkahub.jev.ModelRouterConfig) -> me.rerere.rikkahub.jev.ModelRouterConfig) =
        update { it.copy(modelRouter = transform(it.modelRouter)) }
    JevCard {
        JevSwitch(stringResource(R.string.jev_router_title), stringResource(R.string.jev_router_desc), r.enabled) { v -> set { it.copy(enabled = v) } }
        Text(stringResource(R.string.jev_router_fast), style = MaterialTheme.typography.bodySmall)
        ModelSelector(
            modelId = r.fastModelId,
            providers = providers,
            type = ModelType.CHAT,
            allowClear = true,
            onSelect = { m -> set { it.copy(fastModelId = m.id.takeIf { m.modelId.isNotBlank() }) } },
        )
        Text(stringResource(R.string.jev_router_strong), style = MaterialTheme.typography.bodySmall)
        ModelSelector(
            modelId = r.strongModelId,
            providers = providers,
            type = ModelType.CHAT,
            allowClear = true,
            onSelect = { m -> set { it.copy(strongModelId = m.id.takeIf { m.modelId.isNotBlank() }) } },
        )
        JevThreshold(stringResource(R.string.jev_router_threshold), r.threshold) { v -> set { it.copy(threshold = v) } }
        JevTextField(stringResource(R.string.jev_router_simple), r.simpleCriteria, singleLine = false) { v -> set { it.copy(simpleCriteria = v) } }
        JevTextField(stringResource(R.string.jev_router_complex), r.complexCriteria, singleLine = false) { v -> set { it.copy(complexCriteria = v) } }
        JevSwitch(stringResource(R.string.jev_router_respect_manual), stringResource(R.string.jev_router_respect_manual_desc), r.respectManualChatModel) { v -> set { it.copy(respectManualChatModel = v) } }
        HorizontalDivider()
        JevSwitch(stringResource(R.string.jev_skill_title), stringResource(R.string.jev_skill_desc), config.skillHint.enabled) { v ->
            update { it.copy(skillHint = it.skillHint.copy(enabled = v)) }
        }
        if (config.skillHint.enabled) {
            JevThreshold(stringResource(R.string.jev_threshold), config.skillHint.threshold) { v ->
                update { it.copy(skillHint = it.skillHint.copy(threshold = v)) }
            }
        }
    }
}

@Composable
private fun JevAssistCard(config: JevConfig, update: ((JevConfig) -> JevConfig) -> Unit) {
    JevCard {
        JevSwitch(stringResource(R.string.jev_browser_title), stringResource(R.string.jev_browser_desc), config.browser.enabled) { v ->
            update { it.copy(browser = it.browser.copy(enabled = v)) }
        }
        if (config.browser.enabled) {
            JevThreshold(stringResource(R.string.jev_act_threshold), config.browser.actThreshold) { v ->
                update { it.copy(browser = it.browser.copy(actThreshold = v)) }
            }
        }
        HorizontalDivider()
        JevSwitch(stringResource(R.string.jev_phone_title), stringResource(R.string.jev_phone_desc), config.phone.enabled) { v ->
            update { it.copy(phone = it.phone.copy(enabled = v)) }
        }
        if (config.phone.enabled) {
            JevThreshold(stringResource(R.string.jev_act_threshold), config.phone.actThreshold) { v ->
                update { it.copy(phone = it.phone.copy(actThreshold = v)) }
            }
        }
        HorizontalDivider()
        JevSwitch(stringResource(R.string.jev_decide_title), stringResource(R.string.jev_decide_desc), config.decideTool) { v ->
            update { it.copy(decideTool = v) }
        }
    }
}

@Composable
private fun JevUsageCard(usage: JevUsageTotals, prefs: JevPreferences) {
    val scope = rememberCoroutineScope()
    JevCard {
        JevSection(stringResource(R.string.jev_usage_title), null) {
            TextButton(onClick = { scope.launch { prefs.resetUsage() } }) { Text(stringResource(R.string.jev_usage_reset)) }
        }
        Text(
            stringResource(
                R.string.jev_usage_summary,
                usage.calls,
                usage.failures,
                usage.averageLatencyMs,
                usage.inputTokens,
                String.format(Locale.US, "%.4f", usage.estimatedCostUsd),
            ),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

// ------------------------------------------------------------------ rules

@Composable
private fun JevRuleRow(rule: JevRule, onToggle: (Boolean) -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(
        onClick = onEdit,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(rule.name.ifBlank { rule.question }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${ruleTriggerLabel(rule.trigger)} → ${ruleActionLabel(rule.action)} · ${(rule.threshold * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = rule.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onDelete) { Icon(HugeIcons.Delete01, stringResource(R.string.jev_rule_delete)) }
        }
    }
}

@Composable
private fun ruleTriggerLabel(t: JevRuleTrigger) = stringResource(
    when (t) {
        JevRuleTrigger.USER_MESSAGE -> R.string.jev_rule_trigger_message
        JevRuleTrigger.BEFORE_TOOL -> R.string.jev_rule_trigger_tool
    }
)

@Composable
private fun ruleActionLabel(a: JevRuleAction) = stringResource(
    when (a) {
        JevRuleAction.ADD_INSTRUCTION -> R.string.jev_rule_action_instruction
        JevRuleAction.USE_MODEL -> R.string.jev_rule_action_model
        JevRuleAction.REQUIRE_APPROVAL -> R.string.jev_rule_action_approval
        JevRuleAction.BLOCK_TOOL -> R.string.jev_rule_action_block
    }
)

private fun actionsFor(trigger: JevRuleTrigger) = when (trigger) {
    JevRuleTrigger.USER_MESSAGE -> listOf(JevRuleAction.ADD_INSTRUCTION, JevRuleAction.USE_MODEL)
    JevRuleTrigger.BEFORE_TOOL -> listOf(JevRuleAction.REQUIRE_APPROVAL, JevRuleAction.BLOCK_TOOL)
}

@Composable
private fun JevRuleDialog(
    initial: JevRule,
    providers: List<me.rerere.ai.provider.ProviderSetting>,
    onDismiss: () -> Unit,
    onSave: (JevRule) -> Unit,
) {
    var rule by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.jev_rule_edit)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = rule.name,
                    onValueChange = { rule = rule.copy(name = it) },
                    label = { Text(stringResource(R.string.jev_rule_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.jev_rule_when), style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    JevRuleTrigger.entries.forEach { t ->
                        FilterChip(
                            selected = rule.trigger == t,
                            onClick = {
                                rule = rule.copy(
                                    trigger = t,
                                    action = if (rule.action in actionsFor(t)) rule.action else actionsFor(t).first(),
                                )
                            },
                            label = { Text(ruleTriggerLabel(t)) },
                        )
                    }
                }
                if (rule.trigger == JevRuleTrigger.BEFORE_TOOL) {
                    OutlinedTextField(
                        value = rule.toolFilter,
                        onValueChange = { rule = rule.copy(toolFilter = it) },
                        label = { Text(stringResource(R.string.jev_rule_tool_filter)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = rule.question,
                    onValueChange = { rule = rule.copy(question = it) },
                    label = { Text(stringResource(R.string.jev_rule_question)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                JevThreshold(stringResource(R.string.jev_threshold), rule.threshold) { rule = rule.copy(threshold = it) }
                Text(stringResource(R.string.jev_rule_then), style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    actionsFor(rule.trigger).forEach { a ->
                        FilterChip(selected = rule.action == a, onClick = { rule = rule.copy(action = a) }, label = { Text(ruleActionLabel(a)) })
                    }
                }
                when (rule.action) {
                    JevRuleAction.ADD_INSTRUCTION, JevRuleAction.BLOCK_TOOL -> OutlinedTextField(
                        value = rule.text,
                        onValueChange = { rule = rule.copy(text = it) },
                        label = {
                            Text(stringResource(if (rule.action == JevRuleAction.BLOCK_TOOL) R.string.jev_rule_reason else R.string.jev_rule_instruction))
                        },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    JevRuleAction.USE_MODEL -> ModelSelector(
                        modelId = rule.modelId,
                        providers = providers,
                        type = ModelType.CHAT,
                        allowClear = true,
                        onSelect = { m -> rule = rule.copy(modelId = m.id.takeIf { m.modelId.isNotBlank() }) },
                    )

                    JevRuleAction.REQUIRE_APPROVAL -> Unit
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = rule.question.isNotBlank() && (rule.action != JevRuleAction.USE_MODEL || rule.modelId != null),
                onClick = { onSave(rule) },
            ) { Text(stringResource(R.string.jev_rule_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.jev_rule_cancel)) } },
    )
}
