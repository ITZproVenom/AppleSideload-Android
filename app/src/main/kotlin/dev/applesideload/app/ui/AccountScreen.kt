package dev.applesideload.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.applesideload.app.TwoFactorPrompt
import dev.applesideload.app.UiState
import dev.applesideload.apple.AppleSession
import dev.applesideload.apple.DeveloperAppId

/** The Account tab: the Apple ID that signs, its team and its certificate. */
@Composable
fun AccountScreen(
    state: UiState,
    lastAppleId: String,
    onSignIn: (String, String) -> Unit,
    onSubmitCode: (String) -> Unit,
    onRequestPhoneCode: (Int) -> Unit,
    onSelectTeam: (String) -> Unit,
    onSignOut: () -> Unit,
    onRevoke: () -> Unit,
    appIds: List<DeveloperAppId>? = null,
    onLoadAppIds: () -> Unit = {},
    onDeleteAppId: (String) -> Unit = {}
) = ScreenColumn {
    val idle = state.busy == null
    val account = state.account
    if (account == null) {
        val prompt = state.twoFactor
        if (prompt != null) {
            TwoFactorCard(prompt, idle, onSubmitCode, onRequestPhoneCode, onCancel = onSignOut)
        } else {
            SignInCard(lastAppleId, idle, onSignIn)
        }
        Hint(
            "Apps are signed with a certificate Apple issues to your account. A free Apple ID " +
                "works: each app is signed for 7 days, 3 at a time on the iPhone.",
            modifier = Modifier.fillMaxWidth()
        )
        return@ScreenColumn
    }
    ProfileCard(account, onSignOut)
    TeamCard(state, idle, onSelectTeam)
    CertificateCard(idle, onRevoke)
    AppIdsCard(appIds, idle, onLoadAppIds, onDeleteAppId)
}

@Composable
private fun SignInCard(lastAppleId: String, idle: Boolean, onSignIn: (String, String) -> Unit) {
    var appleId by rememberSaveable { mutableStateOf(lastAppleId) }
    // Not saveable on purpose: a password does not go into the saved state.
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    val canSubmit = idle && appleId.isNotBlank() && password.isNotEmpty()
    val submit = { if (canSubmit) onSignIn(appleId.trim(), password) }

    SectionCard("Sign in with your Apple ID", icon = Icons.Filled.Person) {
        OutlinedTextField(
            value = appleId,
            onValueChange = { appleId = it },
            label = { Text("Apple ID") },
            placeholder = { Text("name@example.com") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(
                        if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (showPassword) "Hide password" else "Show password"
                    )
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = submit, enabled = canSubmit, modifier = Modifier.fillMaxWidth()) { Text("Sign in") }
        IconLine(
            Icons.Filled.Lock,
            "The password is checked with SRP, the exchange Xcode uses, so it is never sent. It is " +
                "not saved either: you type it again at the next sign-in.",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            textColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TwoFactorCard(
    prompt: TwoFactorPrompt,
    idle: Boolean,
    onSubmitCode: (String) -> Unit,
    onRequestPhoneCode: (Int) -> Unit,
    onCancel: () -> Unit
) = SectionCard("Two-factor authentication", icon = Icons.Filled.Shield) {
    if (prompt.phoneNumbers.isNotEmpty() && prompt.numberId == null) {
        Text(
            "Apple will text a code to one of your trusted numbers. Choose which.",
            style = MaterialTheme.typography.bodyMedium
        )
        prompt.phoneNumbers.forEach { number ->
            OutlinedButton(
                onClick = { onRequestPhoneCode(number.id) },
                enabled = idle,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Sms, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(number.maskedNumber)
            }
        }
    } else {
        var code by remember(prompt.numberId) { mutableStateOf("") }
        val submit = { if (idle && code.length == 6) onSubmitCode(code) }
        Text(
            if (prompt.numberId != null) {
                "Enter the code Apple sent by text message."
            } else {
                "Enter the 6-digit code shown on your iPhone, iPad or Mac."
            },
            style = MaterialTheme.typography.bodyMedium
        )
        OutlinedTextField(
            value = code,
            onValueChange = { typed -> code = typed.filter(Char::isDigit).take(6) },
            label = { Text("Verification code") },
            singleLine = true,
            textStyle = TextStyle(fontSize = 22.sp, letterSpacing = 6.sp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = submit, enabled = idle && code.length == 6, modifier = Modifier.fillMaxWidth()) {
            Text("Continue")
        }
    }
    TextButton(onClick = onCancel) { Text("Cancel sign-in") }
}

@Composable
private fun ProfileCard(account: AppleSession, onSignOut: () -> Unit) = SectionCard(null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LetterAvatar(account.appleId, account.appleId, size = 56.dp)
        Column(Modifier.weight(1f)) {
            Text(
                "Signed in",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                account.appleId,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    OutlinedButton(onClick = onSignOut) {
        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Sign out")
    }
}

@Composable
private fun TeamCard(state: UiState, idle: Boolean, onSelectTeam: (String) -> Unit) =
    SectionCard("Development team", icon = Icons.Filled.Badge) {
        val teams = state.teams
        when {
            teams.isEmpty() -> IconLine(
                Icons.Filled.WarningAmber,
                "Apple returned no development team for this account, so nothing can be signed with it.",
                tint = AppColors.status.warning
            )
            teams.size == 1 -> ListRow(
                title = teams[0].name,
                subtitle = "Team ID ${teams[0].teamId}",
                leading = { LetterAvatar(teams[0].name, teams[0].teamId) }
            )
            else -> {
                Hint("Apps are signed for the team chosen here.")
                teams.forEach { team ->
                    val chosen = state.selectedTeam?.teamId == team.teamId
                    ListRow(
                        title = team.name,
                        subtitle = "Team ID ${team.teamId}",
                        modifier = Modifier.selectable(
                            selected = chosen,
                            enabled = idle,
                            role = Role.RadioButton,
                            onClick = { onSelectTeam(team.teamId) }
                        ),
                        leading = { RadioButton(selected = chosen, onClick = null, enabled = idle) }
                    )
                }
            }
        }
    }

@Composable
private fun CertificateCard(idle: Boolean, onRevoke: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    SectionCard("Development certificate", icon = Icons.Filled.VerifiedUser) {
        Text(
            "Apps are signed with a certificate this phone makes and Apple issues for your team. " +
                "A new one is made at the next install if there is none.",
            style = MaterialTheme.typography.bodyMedium
        )
        Hint(
            "Revoking it stops every app signed with it from opening, including apps installed " +
                "from another computer."
        )
        OutlinedButton(
            onClick = { confirming = true },
            enabled = idle,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
        ) { Text("Revoke certificate") }
    }
    if (confirming) {
        ConfirmDialog(
            title = "Revoke the certificate?",
            text = "Every app signed with it stops opening until it is installed again with a new one.",
            confirmLabel = "Revoke",
            onConfirm = onRevoke,
            onDismiss = { confirming = false }
        )
    }
}

@Composable
private fun AppIdsCard(
    appIds: List<DeveloperAppId>?,
    idle: Boolean,
    onLoad: () -> Unit,
    onDelete: (String) -> Unit
) {
    var pending by remember { mutableStateOf<DeveloperAppId?>(null) }
    SectionCard("App IDs", icon = Icons.Filled.Badge) {
        Text(
            "A free account can register 10 App IDs in 7 days, and an app with extensions uses " +
                "one for each. You can delete the ones you no longer need.",
            style = MaterialTheme.typography.bodyMedium
        )
        Hint(
            "Deleting an App ID stops the app that uses it from opening until it is installed " +
                "again. Apple may still count it toward the 7-day limit."
        )
        if (appIds == null) {
            OutlinedButton(onClick = onLoad, enabled = idle) { Text("Show App IDs") }
        } else {
            Text("${appIds.size} registered", style = MaterialTheme.typography.labelLarge)
            appIds.forEach { appId ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            appId.identifier,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        appId.expiration?.let { millis ->
                            Text(
                                "Expires " + java.text.DateFormat.getDateInstance().format(java.util.Date(millis)),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    TextButton(
                        onClick = { pending = appId },
                        enabled = idle,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("Delete") }
                }
            }
            OutlinedButton(onClick = onLoad, enabled = idle) { Text("Refresh") }
        }
    }
    pending?.let { target ->
        ConfirmDialog(
            title = "Delete this App ID?",
            text = "${target.identifier} is removed from your account. The app that uses it stops " +
                "opening until it is installed again.",
            confirmLabel = "Delete",
            onConfirm = {
                pending = null
                onDelete(target.appIdId)
            },
            onDismiss = { pending = null }
        )
    }
}
